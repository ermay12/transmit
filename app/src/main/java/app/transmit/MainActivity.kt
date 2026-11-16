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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.transmit.data.ModelSlot
import app.transmit.gm.BridgeService
import app.transmit.messaging.OutgoingAttachment
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
import app.transmit.ui.theme.TransmitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/** Content handed to Transmit by another app (share sheet, sms: links). */
data class Share(val text: String?, val attachments: List<OutgoingAttachment>) {
    val isEmpty get() = text.isNullOrBlank() && attachments.isEmpty()
}

sealed interface NavRequest {
    data class Thread(val threadId: Long) : NavRequest
    data class Compose(val recipients: List<String>, val share: Share) : NavRequest
    data class PickRecipient(val share: Share) : NavRequest
}

class MainActivity : ComponentActivity() {
    private val requests = MutableSharedFlow<NavRequest>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initial = if (savedInstanceState == null) parse(intent) else null
        setContent {
            TransmitTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    TransmitNav(app.container, initial, requests, onExit = ::finish)
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
        parse(intent)?.let { requests.tryEmit(it) }
    }

    private fun parse(intent: Intent?): NavRequest? {
        intent ?: return null
        return when (intent.action) {
            ACTION_VIEW_THREAD -> intent.getLongExtra(EXTRA_THREAD, -1).takeIf { it > 0 }?.let { NavRequest.Thread(it) }
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
                )
                when {
                    recipients.isNotEmpty() -> NavRequest.Compose(recipients, share)
                    !share.isEmpty || intent.action == Intent.ACTION_SENDTO -> NavRequest.PickRecipient(share)
                    else -> null
                }
            }
            else -> null
        }
    }

    companion object {
        const val ACTION_VIEW_THREAD = "app.transmit.VIEW_THREAD"
        const val EXTRA_THREAD = "thread_id"
        private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

        fun threadIntent(context: Context, threadId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_VIEW_THREAD)
                .putExtra(EXTRA_THREAD, threadId)
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
    const val NEW = "new"
    const val SETTINGS = "settings"
    const val MODELS = "models/{slot}"
    const val USAGE = "usage"
    const val PAIR = "pair"
    fun chat(id: Long) = "chat/$id"
    fun models(slot: ModelSlot) = "models/${slot.name}"
}

@Composable
private fun TransmitNav(c: AppContainer, initial: NavRequest?, requests: Flow<NavRequest>, onExit: () -> Unit) {
    val settings by c.settings.settings.collectAsState(initial = null)
    val loaded = settings ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }
    val start = remember { if (loaded.onboarded) Routes.HOME else Routes.ONBOARDING }
    val nav = rememberNavController()
    var pendingShare by remember { mutableStateOf<Share?>(null) }

    suspend fun handle(r: NavRequest) {
        when (r) {
            is NavRequest.Thread -> nav.openChat(r.threadId)
            is NavRequest.Compose -> {
                pendingShare = r.share.takeUnless { it.isEmpty }
                val id = runCatching { withContext(Dispatchers.IO) { c.messaging.threadIdFor(r.recipients) } }.getOrNull()
                // Not connected to the phone yet: let the user pick (and retry) from the new-chat screen.
                if (id != null) nav.openChat(id) else nav.navigate(Routes.NEW) { launchSingleTop = true }
            }
            is NavRequest.PickRecipient -> {
                pendingShare = r.share.takeUnless { it.isEmpty }
                nav.navigate(Routes.NEW) { launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(Unit) {
        initial?.let { handle(it) }
        requests.collect { handle(it) }
    }

    NavHost(nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(viewModel { SettingsViewModel(c) }, onPair = { nav.navigate(Routes.PAIR) }) {
                nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(Routes.HOME) {
            HomeScreen(
                vm = viewModel { HomeViewModel(c) },
                onOpen = { nav.navigate(Routes.chat(it)) },
                onNew = { nav.navigate(Routes.NEW) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onUsage = { nav.navigate(Routes.USAGE) },
                onPair = { nav.navigate(Routes.PAIR) },
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
            val share = remember { pendingShare.also { pendingShare = null } }
            val vm = viewModel { ChatViewModel(c, threadId, share?.text, share?.attachments.orEmpty()) }
            ChatScreen(vm, onBack = { if (!nav.popBackStack()) onExit() }, onPair = { nav.navigate(Routes.PAIR) })
        }
        composable(Routes.NEW) {
            // Backing out abandons the share, so it can't leak into the next chat opened.
            NewChatScreen(viewModel { NewChatViewModel(c) }, pendingShare, onBack = { pendingShare = null; if (!nav.popBackStack()) onExit() }) { id ->
                nav.navigate(Routes.chat(id)) { popUpTo(Routes.NEW) { inclusive = true } }
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = viewModel { SettingsViewModel(c) },
                onBack = { nav.popBackStack() },
                onModels = { nav.navigate(Routes.models(it)) },
                onUsage = { nav.navigate(Routes.USAGE) },
                onPair = { nav.navigate(Routes.PAIR) },
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

private fun NavHostController.openChat(threadId: Long) {
    navigate(Routes.chat(threadId)) {
        launchSingleTop = true
        // Replace whatever was open above the conversation list.
        graph.findNode(Routes.HOME)?.let { popUpTo(Routes.HOME) }
    }
}
