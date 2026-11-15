package app.transmit

import android.app.Application
import android.app.role.RoleManager
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
import app.transmit.telephony.IncomingHandler
import app.transmit.telephony.MediaEncoder
import app.transmit.telephony.MessagingRepository
import app.transmit.telephony.MmsDownloader
import app.transmit.telephony.MmsStore
import app.transmit.telephony.Sender
import app.transmit.telephony.Subscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency graph; one instance per process. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val subscriptions = Subscriptions(context)
    val messaging = MessagingRepository(context, subscriptions)
    val contacts = ContactsRepository(context)
    val db = AppDatabase.build(context)
    val dao = db.dao()
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)
    val mmsStore = MmsStore(context)
    val sender = Sender(context, messaging, subscriptions, mmsStore, MediaEncoder(context)) { settings.current().deliveryReports }
    val downloader = MmsDownloader(context, subscriptions)

    val ai = AiService(settings, secrets)
    val policy = ChatPolicy(dao, messaging, contacts)
    val budget = BudgetManager(dao, policy)
    val tutor = Tutor(ai, settings, budget, dao)
    val translations = TranslationManager(dao, tutor, policy, messaging, settings, contacts)
    val notifier = Notifier(context, messaging, contacts, dao, settings, policy, translations)
    val smart = SmartLinks()

    val incoming = IncomingHandler(context, messaging, mmsStore, downloader, subscriptions) { threadId, key ->
        notifier.showThread(threadId, alert = true)
        IncomingWorker.enqueue(context, threadId, key)
    }

    fun isDefaultSmsApp(): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
}

class TransmitApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
}

val Context.app: TransmitApp get() = applicationContext as TransmitApp
