package app.transmit.demo

import android.net.Uri
import app.transmit.gm.BridgeState
import app.transmit.gm.Link
import app.transmit.messaging.Attachment
import app.transmit.messaging.Conversation
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Inbox
import app.transmit.messaging.Message
import app.transmit.messaging.MessageKind
import app.transmit.messaging.Reaction
import app.transmit.messaging.Outbox
import app.transmit.messaging.OutgoingAttachment
import app.transmit.messaging.Reactions
import app.transmit.messaging.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The demo's conversations, in memory only. Sending adds the message at once, and a little later the other person
 * answers from [DemoChats.Chat.replies]; [onReply] then translates the answer, as the notification worker does for a
 * real one. Nothing is sent anywhere.
 */
internal class DemoInbox(private val scope: CoroutineScope, now: Long = System.currentTimeMillis()) : Inbox, Outbox, Link {
    private class Chat(val spec: DemoChats.Chat, val messages: MutableList<Message>, var unread: Boolean, var replied: Int = 0)

    private val lock = Any()
    private val ids = AtomicLong()
    private val chats: MutableMap<Long, Chat> = LinkedHashMap()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A sample answer arrived in a chat. */
    var onReply: suspend (threadId: Long, key: String) -> Unit = { _, _ -> }

    init {
        for (spec in DemoChats.chats) {
            val messages = spec.lines.map { line ->
                message(spec, nextId(), line.from, line.text, now - line.minutesAgo * 60_000L, read = !spec.unread || line.from == null)
                    .copy(reactions = reactions(line.reactions))
            }
            chats[spec.threadId] = Chat(spec, messages.toMutableList(), spec.unread)
        }
    }

    /** The user's own sample messages, with the Spanish each was written in. */
    fun written(): List<Triple<Long, String, String>> = synchronized(lock) {
        chats.values.flatMap { c ->
            c.spec.lines.zip(c.messages).mapNotNull { (line, m) -> line.wrote?.let { Triple(c.spec.threadId, m.key, it) } }
        }
    }

    val threadIds: List<Long> get() = synchronized(lock) { chats.keys.toList() }

    // ---- Link: always connected -----------------------------------------------------------------

    override val state: StateFlow<BridgeState> = MutableStateFlow(BridgeState.Connected())
    override val synced: StateFlow<Boolean> = MutableStateFlow(true)
    override fun useHere() = Unit

    // ---- Inbox ----------------------------------------------------------------------------------

    override fun canRead() = true

    override val changes: Flow<Unit> = _changes

    override suspend fun conversations(limit: Int): List<Conversation> = synchronized(lock) {
        chats.values.mapNotNull { c ->
            val latest = c.messages.maxByOrNull { it.date } ?: return@mapNotNull null
            Conversation(
                threadId = c.spec.threadId,
                recipients = c.spec.people,
                snippet = latest.body.ifBlank { if (latest.attachments.isNotEmpty()) "📷" else "" },
                snippetKey = latest.key,
                snippetOutgoing = latest.outgoing,
                snippetStatus = latest.status.takeIf { latest.outgoing },
                date = latest.date,
                unread = c.unread,
                messageCount = c.messages.size,
                hasAttachment = latest.attachments.isNotEmpty(),
                groupName = c.spec.groupName,
                rcs = true,
            )
        }.sortedByDescending { it.date }.take(limit)
    }

    override suspend fun groupPhotoForThread(threadId: Long): Uri? = null

    override suspend fun recipientsForThread(threadId: Long): List<String> = synchronized(lock) { chats[threadId]?.spec?.people.orEmpty() }

    override suspend fun messages(threadId: Long, limit: Int, beforeMs: Long?): List<Message> = synchronized(lock) {
        chats[threadId]?.messages.orEmpty().filter { it.date < (beforeMs ?: Long.MAX_VALUE) }.sortedByDescending { it.date }.take(limit)
    }

    override suspend fun message(key: String): Message? = synchronized(lock) {
        chats.values.firstNotNullOfOrNull { c -> c.messages.firstOrNull { it.key == key } }
    }

    /** The samples are all there is. */
    override fun requestOlder(threadId: Long) = false

    override suspend fun reactionTarget(threadId: Long, reactionDate: Long, r: Reactions.Parsed): Message? =
        Reactions.target(r, messages(threadId, 60, reactionDate))

    override suspend fun markThreadRead(threadId: Long) {
        // Only a real change is announced: chats reload on every change, and mark read as they do.
        val changed = synchronized(lock) {
            val c = chats[threadId] ?: return
            val wasUnread = c.unread || c.messages.any { !it.read }
            c.unread = false
            c.messages.replaceAll { if (it.read) it else it.copy(read = true) }
            wasUnread
        }
        if (changed) changed()
    }

    override suspend fun deleteMessage(m: Message) {
        synchronized(lock) { chats[m.threadId]?.messages?.removeAll { it.key == m.key } }
        changed()
    }

    override suspend fun deleteThread(threadId: Long) {
        synchronized(lock) { chats.remove(threadId) }
        changed()
    }

    // ---- Outbox ---------------------------------------------------------------------------------

    override suspend fun sendToThread(
        threadId: Long,
        text: String,
        attachments: List<OutgoingAttachment>,
        replyTo: String?,
        prepare: suspend (key: String) -> Unit,
    ): Sender.Result {
        val spec = synchronized(lock) { chats[threadId]?.spec } ?: throw IllegalStateException("That sample chat is gone")
        val id = nextId()
        prepare(Message.key(id))
        val sent = message(
            spec, id, null, text, System.currentTimeMillis(), read = true, status = DeliveryStatus.DELIVERED,
            attachments = attachments.mapIndexed { i, a -> Attachment(i.toLong(), a.uri, a.mimeType, a.name) },
            replyTo = replyTo?.let(Message::key),
        )
        synchronized(lock) { chats[threadId]?.messages?.add(sent) }
        changed()
        answer(threadId, sent.key)
        return Sender.Result(threadId, sent.key)
    }

    /** Nothing fails in the demo. */
    override suspend fun retry(message: Message): Sender.Result? = null

    override suspend fun react(message: Message, emoji: String) {
        synchronized(lock) {
            val list = chats[message.threadId]?.messages ?: return
            val i = list.indexOfFirst { it.key == message.key }.takeIf { it >= 0 } ?: return
            val m = list[i]
            // As in Google Messages: the user's reaction is replaced, or taken back when it's the same emoji.
            val removing = m.reactions.any { it.mine && it.emoji == emoji }
            val without = m.reactions.mapNotNull { r -> if (!r.mine) r else r.copy(count = r.count - 1, mine = false).takeIf { it.count > 0 } }
            list[i] = m.copy(
                reactions = when {
                    removing -> without
                    without.any { it.emoji == emoji } -> without.map { if (it.emoji == emoji) it.copy(count = it.count + 1, mine = true) else it }
                    else -> without + Reaction(emoji, 1, mine = true)
                },
            )
        }
        changed()
    }

    /** The other person reads [sentKey], then answers with the chat's next sample reply. */
    private fun answer(threadId: Long, sentKey: String) {
        scope.launch {
            delay(READ_MS)
            update(threadId, sentKey) { it.copy(status = DeliveryStatus.READ) }
            delay(REPLY_MS)
            val reply = synchronized(lock) {
                val c = chats[threadId] ?: return@launch
                val (from, text) = c.spec.replies.getOrNull(c.replied % c.spec.replies.size.coerceAtLeast(1)) ?: return@launch
                c.replied++
                c.unread = true
                message(c.spec, nextId(), from, text, System.currentTimeMillis(), read = false).also { c.messages.add(it) }
            }
            changed()
            onReply(threadId, reply.key)
        }
    }

    /** Sample reactions, from who (null for the user) to emoji, as Google Messages reports them: one per emoji. */
    private fun reactions(who: List<Pair<String?, String>>): List<Reaction> =
        who.groupBy({ it.second }, { it.first }).map { (emoji, people) ->
            Reaction(emoji, people.size, mine = null in people, others = people.filterNotNull())
        }

    private fun update(threadId: Long, key: String, block: (Message) -> Message) {
        synchronized(lock) {
            val list = chats[threadId]?.messages ?: return
            val i = list.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return
            list[i] = block(list[i])
        }
        changed()
    }

    private fun changed() {
        _changes.tryEmit(Unit)
    }

    private fun nextId() = "demo-${ids.incrementAndGet()}"

    private fun message(
        spec: DemoChats.Chat,
        id: String,
        from: String?,
        body: String,
        date: Long,
        read: Boolean,
        status: DeliveryStatus = if (from == null) DeliveryStatus.READ else DeliveryStatus.RECEIVED,
        attachments: List<Attachment> = emptyList(),
        replyTo: String? = null,
    ) = Message(
        key = Message.key(id), id = id.hashCode().toLong(), kind = MessageKind.RCS, threadId = spec.threadId, sender = from,
        recipients = spec.people, body = body, date = date, dateSent = date, outgoing = from == null, status = status,
        read = read, subId = 0, attachments = attachments, replyTo = replyTo, replyable = true,
    )

    private companion object {
        const val READ_MS = 1_200L
        const val REPLY_MS = 2_000L
    }
}
