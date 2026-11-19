package app.transmit.demo

import androidx.lifecycle.ViewModel
import androidx.room.Room
import app.transmit.AppContainer
import app.transmit.Workspace
import app.transmit.ai.BudgetManager
import app.transmit.ai.ChatPolicy
import app.transmit.ai.TranslationManager
import app.transmit.ai.Tutor
import app.transmit.data.ContactsRepository
import app.transmit.data.Languages
import app.transmit.data.db.AppDatabase
import app.transmit.data.db.ChatSettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The reviewer demo: sample chats so Play reviewers (and anyone curious) can try translation and the tutor without
 * pairing. Its conversations, translations, explanations, drafts, chat settings and usage ledger are all in memory,
 * in a database of its own, so leaving the demo leaves nothing behind in the user's inbox or database.
 *
 * AI goes through the app's real access: with none set up, the demo offers the free week or the user's own key, as
 * any chat does.
 */
class DemoSession(private val app: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = Room.inMemoryDatabaseBuilder(app.context, AppDatabase::class.java).build()
    private val inbox = DemoInbox(scope)

    val workspace: Workspace

    init {
        val dao = db.dao()
        val contacts = ContactsRepository(app.context, fixedNames = DemoChats.names)
        val policy = ChatPolicy(dao, inbox, contacts)
        val budget = BudgetManager(dao, policy)
        val tutor = Tutor(app.ai, app.settings, budget, dao, app.access)
        val translations = TranslationManager(dao, tutor, policy, inbox, app.settings, app.access)
        inbox.onReply = { threadId, key -> translations.translateIncoming(threadId, key) }
        workspace = Workspace(app, demo = true, dao, inbox, inbox, inbox, contacts, policy, budget, tutor, translations)

        scope.launch {
            // The chats practice Spanish, unless that's the language the user speaks.
            val s = app.settings.current()
            val lang = if (s.nativeLang == SPANISH) s.target else Languages.get(SPANISH)
            for (chat in DemoChats.chats) {
                dao.upsertChatSettings(ChatSettingsEntity(chat.threadId, autoTranslate = true, targetLang = lang.code, name = chat.groupName))
            }
            for ((threadId, key, wrote) in inbox.written()) translations.storeSelf(key, threadId, lang, wrote)
            // Like real chats, whose new messages are translated as they arrive: the newest of each, once AI is on.
            app.access.access.map { it?.canUseAi == true }.distinctUntilChanged().filter { it }.collect {
                for (id in inbox.threadIds) translations.autoTranslate(id, inbox.messages(id, 50))
            }
        }
    }

    /** Stops the demo's work. Its database goes a minute later, when nothing can still be reading it. */
    fun close() {
        scope.cancel()
        app.scope.launch {
            delay(60_000)
            db.close()
        }
    }

    private companion object {
        const val SPANISH = "es"
    }
}

/** Keeps the demo open while its inbox is on the back stack, and ends it when the inbox is left. */
class DemoViewModel(private val app: AppContainer) : ViewModel() {
    val workspace: Workspace = app.startDemo()

    override fun onCleared() = app.endDemo(workspace)
}
