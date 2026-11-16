package app.transmit

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import app.transmit.ai.AiService
import app.transmit.ai.BudgetManager
import app.transmit.ai.ChatPolicy
import app.transmit.ai.TranslationManager
import app.transmit.ai.Tutor
import app.transmit.data.ContactsRepository
import app.transmit.data.SecretStore
import app.transmit.data.SettingsRepository
import app.transmit.data.db.AppDatabase
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

/** Manual dependency graph; one instance per process. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val contacts = ContactsRepository(context)
    val db = AppDatabase.build(context)
    val dao = db.dao()
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)

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

    val ai = AiService(settings, secrets)
    val policy = ChatPolicy(dao, messaging, contacts)
    val budget = BudgetManager(dao, policy)
    val tutor = Tutor(ai, settings, budget, dao)
    val translations: TranslationManager = TranslationManager(dao, tutor, policy, messaging, settings, contacts)
    val notifier: Notifier = Notifier(context, messaging, contacts, dao, settings, policy, translations)
    val smart = SmartLinks()
}

class TransmitApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        BridgeService.start(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
}

val Context.app: TransmitApp get() = applicationContext as TransmitApp
