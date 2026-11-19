package app.transmit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.transmit.data.ModelSlot
import app.transmit.demo.DemoViewModel
import app.transmit.gm.BridgeService
import app.transmit.messaging.OutgoingAttachment
import app.transmit.notify.ConversationShortcuts
import app.transmit.ui.access.PlansScreen
import app.transmit.ui.access.PlansViewModel
import app.transmit.ui.chat.ChatScreen
import app.transmit.ui.chat.ChatViewModel
import app.transmit.ui.home.HomeScreen
import app.transmit.ui.home.HomeViewModel
import app.transmit.ui.newchat.NewChatScreen
import app.transmit.ui.newchat.NewChatViewModel
import app.transmit.ui.onboarding.OnboardingScreen
import app.transmit.ui.pairing.PairingScreen
import app.transmit.ui.settings.ModelPickerScreen
import app.transmit.ui.settings.SettingsScreen
import app.transmit.ui.settings.SettingsViewModel
import app.transmit.ui.settings.UsageScreen
import app.transmit.ui.share.SharePickerScreen
import app.transmit.ui.share.SharePickerViewModel
import app.transmit.ui.theme.TransmitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/** Content handed to Transmit by another app (share sheet, sms: links). */
data class Share(
    val text: String?,
    val attachments: List<OutgoingAttachment>,
    /** Cancelling returns to the app it came from; false when Transmit shared to itself. */
    val fromOtherApp: Boolean = true,
) {
    val isEmpty get() = text.isNullOrBlank() && attachments.isEmpty()
}

sealed interface NavRequest {
    data class Thread(val threadId: Long) : NavRequest
    data class Compose(val recipients: List<String>, val share: Share) : NavRequest
    /** A share sent straight to a conversation: its shortcut was picked in the system share sheet. */
    data class ShareToThread(val threadId: Long, val share: Share) : NavRequest
    data class PickRecipient(val share: Share) : NavRequest
    /** From a trial or subscription notification. */
    data object Plans : NavRequest
}

/** A share on its way to a conversation; [threadId] is null while the user picks one. */
private data class PendingShare(val threadId: Long?, val share: Share)

/** Keeps a pending share when the activity is recreated; the files' read grants belong to the activity, not the process. */
private val PendingShareSaver = Saver<PendingShare?, ArrayList<Any?>>(
    save = { p ->
        p?.let {
            arrayListOf(
                it.threadId ?: -1L, it.share.text, it.share.fromOtherApp,
                ArrayList(it.share.attachments.map { a -> a.uri.toString() }),
                ArrayList(it.share.attachments.map { a -> a.mimeType }),
            )
        }
    },
    restore = { v ->
        val uris = v[3] as List<*>
        val types = v[4] as List<*>
        PendingShare(
            (v[0] as Long).takeIf { it >= 0 },
            Share(v[1] as String?, uris.indices.map { i -> OutgoingAttachment(Uri.parse(uris[i] as String), types[i] as String) }, v[2] as Boolean),
        )
    },
)

class MainActivity : ComponentActivity() {
    private val requests = MutableSharedFlow<NavRequest>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initial = if (savedInstanceState == null) parse(intent) else null
        setContent {
            TransmitTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    TransmitNav(app.container, initial, requests, onExit = ::finish, onLeave = { moveTaskToBack(true) })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Android only lets a visible app start the connection service; make sure it's running.
        BridgeService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // So the referrer read while parsing is this intent's.
        setIntent(intent)
        parse(intent)?.let { requests.tryEmit(it) }
    }

    private fun parse(intent: Intent?): NavRequest? {
        intent ?: return null
        return when (intent.action) {
            ACTION_VIEW_THREAD -> intent.getLongExtra(EXTRA_THREAD, -1).takeIf { it > 0 }?.let { NavRequest.Thread(it) }
            ACTION_OPEN_PLANS -> NavRequest.Plans
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val data = intent.data?.takeIf { it.scheme in SMS_SCHEMES }
                val recipients = data?.let(::recipientsOf).orEmpty()
                val text = intent.getStringExtra("sms_body")
                    ?: data?.let(::bodyOf)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val streams = when (intent.action) {
                    Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                    Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    else -> emptyList()
                }
                val share = Share(
                    text?.takeIf { it.isNotBlank() },
                    streams.map { OutgoingAttachment(it, contentResolver.getType(it) ?: intent.type ?: "application/octet-stream") },
                    fromOtherApp = referrer?.host != packageName,
                )
                // Set when the user picked one of the conversations in the system share sheet.
                val shortcutThread = ConversationShortcuts.threadIdOf(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
                when {
                    recipients.isNotEmpty() -> NavRequest.Compose(recipients, share)
                    shortcutThread != null && !share.isEmpty -> NavRequest.ShareToThread(shortcutThread, share)
                    !share.isEmpty || intent.action == Intent.ACTION_SENDTO -> NavRequest.PickRecipient(share)
                    else -> null
                }
            }
            else -> null
        }
    }

    companion object {
        const val ACTION_VIEW_THREAD = "app.transmit.VIEW_THREAD"
        const val ACTION_OPEN_PLANS = "app.transmit.OPEN_PLANS"
        const val EXTRA_THREAD = "thread_id"
        private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

        fun threadIntent(context: Context, threadId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_VIEW_THREAD)
                .putExtra(EXTRA_THREAD, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun plansIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_PLANS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** `smsto:+1555,+1666?body=hi` → numbers. */
        fun recipientsOf(uri: Uri): List<String> =
            Uri.decode(uri.schemeSpecificPart.orEmpty()).substringBefore('?')
                .split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

        fun bodyOf(uri: Uri): String? =
            uri.schemeSpecificPart?.substringAfter('?', "")?.split('&')
                ?.firstOrNull { it.startsWith("body=") }?.removePrefix("body=")?.let(Uri::decode)
    }
}

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHAT = "chat/{threadId}"
    const val NEW = "new?group={group}"
    const val SHARE = "share"
    const val SETTINGS = "settings?ownKey={ownKey}"
    const val MODELS = "models/{slot}"
    /** The paywall: plans, prices and purchase. */
    const val PLANS = "plans"
    const val USAGE = "usage"
    const val PAIR = "pair"
    /** The reviewer demo's inbox and chats, entered from onboarding. Leaving the inbox ends the demo. */
    const val DEMO = "demo"
    const val DEMO_CHAT = "demo/chat/{threadId}"
    fun chat(id: Long) = "chat/$id"
    fun demoChat(id: Long) = "demo/chat/$id"
    fun newChat(group: Boolean = false) = if (group) "new?group=true" else "new"
    fun models(slot: ModelSlot) = "models/${slot.name}"
    fun settings(ownKey: Boolean = false) = if (ownKey) "settings?ownKey=true" else "settings"
}

@Composable
private fun TransmitNav(c: AppContainer, initial: NavRequest?, requests: Flow<NavRequest>, onExit: () -> Unit, onLeave: () -> Unit) {
    val settings by c.settings.settings.collectAsState(initial = null)
    val loaded = settings ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }
    val start = remember { if (loaded.onboarded) Routes.HOME else Routes.ONBOARDING }
    val nav = rememberNavController()
    var pendingShare by rememberSaveable(stateSaver = PendingShareSaver) { mutableStateOf<PendingShare?>(null) }

    /** Opens [threadId] with [share] in the compose box. */
    fun openWithShare(threadId: Long, share: Share) {
        pendingShare = PendingShare(threadId, share).takeUnless { share.isEmpty }
        // A fresh screen even if the chat is already open, so it picks up the share.
        nav.openChat(threadId, fresh = !share.isEmpty)
    }

    /** Asks where [share] goes, replacing an earlier share still waiting in the picker. */
    fun pickFor(share: Share) {
        pendingShare = PendingShare(null, share)
        nav.navigate(Routes.SHARE) { popUpTo(Routes.SHARE) { inclusive = true } }
    }

    suspend fun handle(r: NavRequest) {
        when (r) {
            is NavRequest.Thread -> nav.openChat(r.threadId)
            is NavRequest.Compose -> {
                val id = runCatching { withContext(Dispatchers.IO) { c.messaging.threadIdFor(r.recipients) } }.getOrNull()
                if (id != null) {
                    openWithShare(id, r.share)
                } else {
                    // Not connected to the phone yet: let the user pick (and retry) from the new-chat screen.
                    pendingShare = PendingShare(null, r.share).takeUnless { r.share.isEmpty }
                    nav.navigate(Routes.newChat()) { launchSingleTop = true }
                }
            }
            is NavRequest.ShareToThread -> {
                // A shortcut can outlive its conversation; then the user picks another.
                val exists = runCatching { withContext(Dispatchers.IO) { c.messaging.recipientsForThread(r.threadId).isNotEmpty() } }.getOrDefault(false)
                if (exists) openWithShare(r.threadId, r.share) else pickFor(r.share)
            }
            NavRequest.Plans -> nav.navigate(Routes.PLANS) { launchSingleTop = true }
            is NavRequest.PickRecipient -> {
                if (!r.share.isEmpty) {
                    pickFor(r.share)
                } else {
                    pendingShare = null
                    nav.navigate(Routes.newChat()) { launchSingleTop = true }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        initial?.let { handle(it) }
        requests.collect { handle(it) }
    }
    // A trial or subscription that ends while the app is open brings up the plans straight away.
    val ended by c.access.pendingEnd.collectAsStateWithLifecycle()
    LaunchedEffect(ended) {
        if (ended != null) {
            nav.navigate(Routes.PLANS) { launchSingleTop = true }
            c.access.acknowledgeEnd()
        }
    }
    val openPlans = { nav.navigate(Routes.PLANS) { launchSingleTop = true } }

    NavHost(nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                viewModel { SettingsViewModel(c) }, onPair = { nav.navigate(Routes.PAIR) }, onPlans = openPlans,
                onDemo = { nav.navigate(Routes.DEMO) { launchSingleTop = true } },
            ) {
                nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(Routes.HOME) {
            HomeScreen(
                vm = viewModel { HomeViewModel(c.live) },
                onOpen = { nav.navigate(Routes.chat(it)) },
                onNew = { nav.navigate(Routes.newChat()) },
                onSettings = { nav.navigate(Routes.settings()) },
                onUsage = { nav.navigate(Routes.USAGE) },
                onPair = { nav.navigate(Routes.PAIR) },
                onPlans = openPlans,
                onOwnKey = { nav.navigate(Routes.settings(ownKey = true)) },
            )
        }
        composable(Routes.DEMO) {
            // Holds the demo while its inbox is on the back stack; leaving the inbox discards it.
            val demo = viewModel { DemoViewModel(c) }
            HomeScreen(
                vm = viewModel { HomeViewModel(demo.workspace) },
                onOpen = { nav.navigate(Routes.demoChat(it)) },
                onNew = {},
                onSettings = { nav.navigate(Routes.settings()) },
                onUsage = {},
                onPair = {},
                onPlans = openPlans,
                onOwnKey = { nav.navigate(Routes.settings(ownKey = true)) },
                onExitDemo = { nav.popBackStack(Routes.DEMO, inclusive = true) },
            )
        }
        composable(Routes.DEMO_CHAT, arguments = listOf(navArgument("threadId") { type = NavType.LongType })) { entry ->
            val threadId = entry.arguments?.getLong("threadId") ?: return@composable
            // The open demo's, or after the process was restarted here, a fresh one that the inbox below picks up.
            val workspace = remember { c.startDemo() }
            ChatScreen(
                viewModel { ChatViewModel(workspace, threadId, null, emptyList()) },
                onBack = { if (!nav.popBackStack()) onExit() }, onPair = {},
                onPlans = openPlans, onOwnKey = { nav.navigate(Routes.settings(ownKey = true)) },
            )
        }
        composable(Routes.PAIR) {
            val context = androidx.compose.ui.platform.LocalContext.current
            PairingScreen(
                c.bridge,
                onDone = {
                    BridgeService.start(context)
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("threadId") { type = NavType.LongType })) { entry ->
            val threadId = entry.arguments?.getLong("threadId") ?: return@composable
            // Only a share meant for this conversation, so one still being placed can't leak into it.
            val share = remember { pendingShare?.takeIf { it.threadId == threadId }?.share?.also { pendingShare = null } }
            val vm = viewModel { ChatViewModel(c.live, threadId, share?.text, share?.attachments.orEmpty()) }
            ChatScreen(
                vm, onBack = { if (!nav.popBackStack()) onExit() }, onPair = { nav.navigate(Routes.PAIR) },
                onPlans = openPlans, onOwnKey = { nav.navigate(Routes.settings(ownKey = true)) },
            )
        }
        composable(Routes.NEW, arguments = listOf(navArgument("group") { type = NavType.BoolType; defaultValue = false })) { entry ->
            // "New group" in the share picker: backing out returns there, share and all.
            val fromPicker = remember { nav.previousBackStackEntry?.destination?.route == Routes.SHARE }
            NewChatScreen(
                viewModel { NewChatViewModel(c) },
                share = pendingShare?.takeIf { it.threadId == null }?.share,
                startInGroupMode = entry.arguments?.getBoolean("group") == true,
                onBack = {
                    // Otherwise backing out abandons the share, so it can't leak into the next chat opened.
                    if (!fromPicker) pendingShare = null
                    if (!nav.popBackStack()) onExit()
                },
            ) { id ->
                pendingShare?.let { pendingShare = it.copy(threadId = id) }
                nav.openChat(id, fresh = true)
            }
        }
        composable(Routes.SHARE) {
            val share = pendingShare?.share
            // Nothing to place (it couldn't be restored): there's nothing to pick a chat for.
            val orphaned = remember { share == null }
            if (orphaned) {
                LaunchedEffect(Unit) { if (!nav.popBackStack()) onExit() }
                return@composable
            }
            SharePickerScreen(
                vm = viewModel { SharePickerViewModel(c) },
                share = share,
                onCancel = {
                    val external = pendingShare?.share?.fromOtherApp != false
                    pendingShare = null
                    if (!nav.popBackStack()) onExit()
                    // Like closing a share sheet: back to the app the share came from.
                    else if (external) onLeave()
                },
                onPick = { id ->
                    pendingShare?.let { pendingShare = it.copy(threadId = id) }
                    nav.openChat(id, fresh = true)
                },
                onNewGroup = { nav.navigate(Routes.newChat(group = true)) },
            )
        }
        composable(Routes.SETTINGS, arguments = listOf(navArgument("ownKey") { type = NavType.BoolType; defaultValue = false })) { entry ->
            SettingsScreen(
                vm = viewModel { SettingsViewModel(c) },
                onBack = { nav.popBackStack() },
                onModels = { nav.navigate(Routes.models(it)) },
                onUsage = { nav.navigate(Routes.USAGE) },
                onPair = { nav.navigate(Routes.PAIR) },
                onPlans = openPlans,
                openOwnKey = entry.arguments?.getBoolean("ownKey") == true,
            )
        }
        composable(Routes.PLANS) {
            PlansScreen(
                viewModel { PlansViewModel(c) },
                onBack = { if (!nav.popBackStack()) onExit() },
                onOwnKey = { nav.navigate(Routes.settings(ownKey = true)) { popUpTo(Routes.PLANS) { inclusive = true } } },
            )
        }
        composable(Routes.MODELS, arguments = listOf(navArgument("slot") { type = NavType.StringType })) { entry ->
            val slot = runCatching { ModelSlot.valueOf(entry.arguments?.getString("slot").orEmpty()) }.getOrDefault(ModelSlot.TRANSLATE)
            ModelPickerScreen(viewModel { SettingsViewModel(c) }, slot, onBack = { nav.popBackStack() })
        }
        composable(Routes.USAGE) {
            UsageScreen(viewModel { SettingsViewModel(c) }, onBack = { nav.popBackStack() })
        }
    }
}

/** [fresh] replaces the conversation's screen even when it's already open. */
private fun NavHostController.openChat(threadId: Long, fresh: Boolean = false) {
    navigate(Routes.chat(threadId)) {
        launchSingleTop = !fresh
        // Replace whatever was open above the conversation list.
        graph.findNode(Routes.HOME)?.let { popUpTo(Routes.HOME) }
    }
}
