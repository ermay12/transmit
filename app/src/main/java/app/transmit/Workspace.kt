package app.transmit

import app.transmit.ai.BudgetManager
import app.transmit.ai.ChatPolicy
import app.transmit.ai.TranslationManager
import app.transmit.ai.Tutor
import app.transmit.data.ContactsRepository
import app.transmit.data.db.TransmitDao
import app.transmit.gm.Link
import app.transmit.messaging.Inbox
import app.transmit.messaging.Outbox

/**
 * What the inbox and chats work on: the user's own messages ([AppContainer.live]), or the reviewer demo's sample chats
 * ([AppContainer.startDemo]). Each has its own conversations, database, translations and usage ledger, so nothing
 * done in the demo reaches the user's own. The AI access, settings and purchases are the app's, shared by both.
 */
class Workspace(
    private val app: AppContainer,
    /** The reviewer demo: sample chats held in memory. */
    val demo: Boolean,
    val dao: TransmitDao,
    val messaging: Inbox,
    val sender: Outbox,
    val bridge: Link,
    val contacts: ContactsRepository,
    val policy: ChatPolicy,
    val budget: BudgetManager,
    val tutor: Tutor,
    val translations: TranslationManager,
) {
    val context get() = app.context
    val scope get() = app.scope
    val settings get() = app.settings
    val access get() = app.access
    val purchases get() = app.purchases
    val notifier get() = app.notifier
    val smart get() = app.smart
}
