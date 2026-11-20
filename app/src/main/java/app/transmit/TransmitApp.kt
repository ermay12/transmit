package app.transmit

import android.app.Application
import android.content.Context
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
        onIncoming = { threadId, key ->
            notifier.showThread(threadId, alert = true)
            IncomingWorker.enqueue(context, threadId, key)
        },
        onReadElsewhere = { threadId -> notifier.cancel(threadId) },
        isOnScreen = { threadId -> notifier.activeThreadId == threadId },
        onLoggedOut = { reason -> notifier.notifyLoggedOut(reason) },
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
    val ai = AiService(settings, secrets)
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

    /**
     * At launch and whenever the app comes back: a trial or subscription that ended meanwhile (OpenRouter refuses a
     * key the Worker turned off; a trial past its end resolves as ended), and purchases still to be delivered. The
     * Worker itself is asked daily and near the limit, not on every return.
     */
    fun checkAccess() {
        scope.launch {
            access.reconcile()
            purchases.sync()
        }
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
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.checkAccess()
        })
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
