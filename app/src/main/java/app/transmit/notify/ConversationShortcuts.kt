package app.transmit.notify

import android.content.Context
import android.content.pm.ShortcutInfo
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import app.transmit.MainActivity
import app.transmit.data.ContactsRepository
import app.transmit.data.chatTitle
import app.transmit.data.db.TransmitDao
import app.transmit.messaging.MessagingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Conversations as shortcuts. The most recent ones are kept published as sharing shortcuts, so they
 * show in the system share sheet (Direct Share) and the launcher's long-press menu. Conversation
 * notifications use the same shortcuts. Calls block; make them off the main thread.
 */
class ConversationShortcuts(
    private val context: Context,
    private val repo: MessagingRepository,
    private val contacts: ContactsRepository,
    private val dao: TransmitDao,
) {
    /** What was published last, so an unchanged list doesn't call the system again. */
    private var published: List<String>? = null
    private val lock = Mutex()

    /** Keeps the recent conversations published as messages, chat names and archiving change. [scope] runs on IO. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            merge(repo.changes, dao.allChatSettings().map { }, contacts.version.map { })
                .debounce(PUBLISH_DELAY_MS)
                .collect { runCatching { publishRecent() }.onFailure { Log.w(TAG, "Couldn't publish share targets", it) } }
        }
    }

    private suspend fun publishRecent() = lock.withLock {
        if (!repo.canRead()) {
            if (published != emptyList<String>()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                published = emptyList()
            }
            return@withLock
        }
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, MAX_TARGETS)
        val chats = dao.allChatSettings().first().associateBy { it.threadId }
        // Read a few extra in case some of the newest are archived.
        val recent = repo.conversations(limit = max * 3)
            .filter { it.recipients.isNotEmpty() && chats[it.threadId]?.archived(it.date) != true }
            .take(max)
        contacts.prefetch(recent.flatMap { it.recipients })
        val targets = recent.map { conv ->
            val people = conv.recipients.map(contacts::lookup)
            Target(conv.threadId, chatTitle(people, chats[conv.threadId]?.name), people.singleOrNull()?.address, conv.recipients.first())
        }
        val signature = targets.map { t -> "${t.threadId}|${t.title}|${t.address?.let { contacts.lookup(it).photoUri }}" }
        if (signature == published) return@withLock
        val shortcuts = targets.mapIndexed { rank, t -> build(t.threadId, t.title, t.address?.let(::person), t.seed, rank) }
        // Refused while rate-limited (in the background without the connection service); the next change retries.
        if (ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)) published = signature
    }

    private data class Target(val threadId: Long, val title: String, val address: String?, val seed: String)

    /**
     * Publishes or refreshes [threadId]'s shortcut, for a notification about it, and returns its id.
     * [person] is null for a group, whose icon is drawn from [seed].
     */
    fun push(threadId: Long, title: String, person: Person?, seed: String): String {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, build(threadId, title, person, seed)) }
        return id(threadId)
    }

    /** [address] as a notification or shortcut person, with their photo or a drawn avatar. */
    fun person(address: String): Person {
        val info = contacts.lookup(address)
        val bitmap = info.photoUri?.let { uri ->
            runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        return Person.Builder()
            .setName(info.displayName)
            .setKey(address)
            .setIcon(Avatars.icon(info.displayName, address, bitmap))
            .apply { info.lookupUri?.let { setUri(it.toString()) } }
            .build()
    }

    private fun build(threadId: Long, title: String, person: Person?, seed: String, rank: Int = 0): ShortcutInfoCompat {
        val id = id(threadId)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(title.ifBlank { "Conversation" })
            .setLongLived(true)
            .setLocusId(LocusIdCompat(id))
            .setIntent(MainActivity.threadIntent(context, threadId))
            .setCategories(CATEGORIES)
            .setRank(rank)
            .apply { person?.let(::setPerson) }
            .setIcon(person?.icon ?: Avatars.groupIcon(context, seed))
            .build()
    }

    companion object {
        private const val TAG = "Shortcuts"
        /** Matches the share-target category in res/xml/shortcuts.xml. */
        private const val SHARE_TARGET = "app.transmit.category.SHARE_TARGET"
        private val CATEGORIES = setOf(SHARE_TARGET, ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION)
        /** Share sheets and launchers show only a few per app. */
        private const val MAX_TARGETS = 8
        private const val PUBLISH_DELAY_MS = 1500L
        private const val PREFIX = "thread_"

        fun id(threadId: Long) = "$PREFIX$threadId"

        fun threadIdOf(shortcutId: String?): Long? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()

        /** The user sent something to [threadId]: share sheets rank it higher. */
        fun reportUsed(context: Context, threadId: Long) {
            runCatching { ShortcutManagerCompat.reportShortcutUsed(context, id(threadId)) }
        }

        /** Drops a deleted conversation's shortcut, including the copy the system keeps for its notifications. */
        fun remove(context: Context, threadId: Long) {
            runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(id(threadId))) }
        }
    }
}
