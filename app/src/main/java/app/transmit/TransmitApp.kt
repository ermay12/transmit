package app.transmit

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.transmit.access.AccessBuild
import app.transmit.access.AccessListener
import app.transmit.access.AccessManager
import app.transmit.access.AccessStore
import app.transmit.access.AccessWork
import app.transmit.access.EndReason
import app.transmit.access.PlanRefresher
import app.transmit.access.PurchaseQueue
import app.transmit.access.Purchases
import app.transmit.access.RefreshTrigger
import app.transmit.ai.AiService
import app.transmit.ui.pairing.GoogleSignInCookies
import app.transmit.ai.ProviderType
import app.transmit.ai.BudgetManager
import app.transmit.ai.ChatPolicy
import app.transmit.ai.TranslationManager
import app.transmit.ai.Tutor
import app.transmit.data.ContactsRepository
import app.transmit.demo.DemoSession
import app.transmit.data.SecretStore
import app.transmit.data.SettingsRepository
import app.transmit.data.db.AppDatabase
import app.transmit.notify.ConversationShortcuts
import app.transmit.notify.IncomingWorker
import app.transmit.notify.Notifier
import app.transmit.smart.SmartLinks
import app.transmit.gm.BridgeService
import app.transmit.gm.GmBridge
import app.transmit.messaging.MessagingRepository
import app.transmit.messaging.Sender
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.accessDataStore: DataStore<Preferences> by preferencesDataStore(name = "access")

/** Manual dependency graph; one instance per process. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val contacts = ContactsRepository(context)
    val db = AppDatabase.build(context)
    val dao = db.dao()
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)
    /** Kept warm so language pickers open already sorted. */
    val recentLanguages: StateFlow<List<String>> = settings.recentLanguages.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The connection to Google Messages on the phone, which does the actual messaging. */
    val bridge: GmBridge = GmBridge(
        secrets, db.gmDao(), dao, contacts, File(context.cacheDir, "gm-media"), scope,
        onIncoming = { threadId, keys ->
            notifier.showThread(threadId, alert = true)
            keys.forEach { IncomingWorker.enqueue(context, threadId, it) }
        },
        onReadElsewhere = { threadId -> notifier.readElsewhere(threadId) },
        isOnScreen = { threadId -> notifier.activeThreadId == threadId },
        onLoggedOut = { reason -> notifier.notifyLoggedOut(reason) },
        clearWebSignIn = {
            // Only where the in-app sign-in was ever opened (QR pairing never loads a WebView); CookieManager wants the
            // main thread, and throws while the WebView package is updating.
            if (File(context.dataDir, "app_webview").exists()) {
                Handler(Looper.getMainLooper()).post { runCatching { GoogleSignInCookies.clear() } }
            }
        },
    )
    val messaging: MessagingRepository = MessagingRepository(context, db, bridge)
    val sender: Sender = Sender(context, db.gmDao(), dao, bridge, scope) { threadId -> notifier.notifySendFailed(threadId) }

    private val accessServices = AccessBuild.services(context, secrets, scope)
    /** Where AI comes from: trial, subscription, the user's own key, or none. */
    val access = AccessManager(
        store = AccessStore(context.accessDataStore) { ProviderType.entries.any { secrets.has(it.secretName) } },
        vault = secrets,
        backend = accessServices.backend,
        allowanceSource = accessServices.allowance,
        managedSpend = { since -> dao.managedSpentSinceFlow(since) },
        scope = scope,
        isForeground = { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
        autoStart = false,
    )
    /** Play's purchase sheet, and getting every purchase's key from the Worker. */
    val purchases = Purchases(
        accessServices.store, access, PurchaseQueue(secrets), scope,
        // A purchase the Worker answered for brings the plan with it; otherwise the plan may have changed unseen.
        onFlowReturned = { sent -> if (sent) planRefresh.upToDate() else planRefresh.request(RefreshTrigger.PURCHASE_FLOW) },
    ) { AccessWork.scheduleDelivery(context) }
    /**
     * Keeps the plan current while the app stays open: the Plans and Settings screens ask when they show, and a
     * purchase flow that sent nothing to the Worker asks when it returns.
     */
    val planRefresh: PlanRefresher = PlanRefresher.forPlan(scope, purchases, access)
    /** Play Integrity for the trial; warmed when a screen that can start it opens. */
    val trialCheck = accessServices.attestation
    val ai = AiService(settings, secrets, accessServices.managedAi)
    val policy = ChatPolicy(dao, messaging, contacts)
    val budget = BudgetManager(dao, policy)
    val tutor = Tutor(ai, settings, budget, dao, access)
    val translations: TranslationManager = TranslationManager(dao, tutor, policy, messaging, settings, access)
    /** Recent conversations in the system share sheet and launcher, and behind notifications. */
    val shortcuts = ConversationShortcuts(context, messaging, contacts, dao)
    val notifier: Notifier = Notifier(context, messaging, contacts, dao, settings, policy, translations, shortcuts)
    val smart = SmartLinks()
    /** The user's own messages, as the inbox and chats work on them. */
    val live = Workspace(this, demo = false, dao, messaging, sender, bridge, contacts, policy, budget, tutor, translations)

    private val demoLock = Any()
    private var demo: DemoSession? = null

    /** The reviewer demo's sample chats: the open demo's, or a fresh one. */
    fun startDemo(): Workspace = synchronized(demoLock) { (demo ?: DemoSession(this).also { demo = it }).workspace }

    /** Leaves the demo that [workspace] belongs to, discarding everything in it. */
    fun endDemo(workspace: Workspace) = synchronized(demoLock) {
        if (demo?.workspace === workspace) {
            demo?.close()
            demo = null
        }
    }

    init {
        access.listener = object : AccessListener {
            override fun onEndedInBackground(reason: EndReason) = notifier.notifyAccessEnded(reason, purchases.available)
            override fun onInUseElsewhereInBackground() = notifier.notifyInUseElsewhere()
            override fun onTrialGranted(endsAt: Long) = AccessWork.scheduleTrialReminder(context, endsAt)
            override fun onSubscriptionGranted() = AccessWork.cancelTrialReminder(context)
        }
        access.start()
    }

    @Volatile private var accessCheckedAt = 0L

    /**
     * Each time the app's screen comes back (MainActivity resumed), the same whether it's from the launcher, Recents,
     * a notification or another app: a trial or subscription that ended meanwhile (OpenRouter refuses a key the Worker
     * turned off; a trial past its end resolves as ended), OpenRouter's figure for the allowance, and purchases still
     * to be delivered. Not while Play's purchase sheet is open or its token is on its way: that answer is the plan. The
     * Worker itself is asked daily, near the limit and after the paid period's end ([AccessManager.watchPeriodEnd]).
     */
    fun checkAccess() {
        if (purchases.flowOpen) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            // One resume can come with another a moment later (a permission prompt closing, say).
            if (now - accessCheckedAt < ACCESS_CHECK_GAP_MS) return
            accessCheckedAt = now
        }
        scope.launch {
            access.reconcile()
            purchases.sync()
        }
    }

    private companion object {
        const val ACCESS_CHECK_GAP_MS = 2_000L
    }
}

class TransmitApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.shortcuts.start(container.scope)
        BridgeService.start(this)
        AccessWork.scheduleDailyRefresh(this)
        // Coming back is checked in MainActivity.onResume (container.checkAccess); while a screen of the app shows,
        // the end of the paid period is watched for, so a lapse or a renewal shows without leaving the app.
        val process = ProcessLifecycleOwner.get()
        process.lifecycleScope.launch {
            process.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.access.watchPeriodEnd(busy = { container.purchases.flowOpen })
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components {
            add(VideoFrameDecoder.Factory())
            // Group pictures load from the web; registered here rather than trusting service discovery after R8.
            add(OkHttpNetworkFetcherFactory())
        }.build()
}

val Context.app: TransmitApp get() = applicationContext as TransmitApp
