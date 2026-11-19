package app.transmit.messaging

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/**
 * The conversations the inbox and chats show: the user's own from Google Messages ([MessagingRepository]), or the
 * reviewer demo's sample chats, which live only in memory.
 */
interface Inbox {
    /** True once there's anything to read (for Google Messages, once paired). */
    fun canRead(): Boolean

    /** Emits whenever conversations or messages change. */
    val changes: Flow<Unit>

    /** Conversations, newest first. */
    suspend fun conversations(limit: Int = Int.MAX_VALUE): List<Conversation>

    suspend fun groupPhotoForThread(threadId: Long): Uri?

    suspend fun recipientsForThread(threadId: Long): List<String>

    /** The newest [limit] messages (before [beforeMs]), newest first. */
    suspend fun messages(threadId: Long, limit: Int, beforeMs: Long? = null): List<Message>

    suspend fun message(key: String): Message?

    /** The message that tapback text [r], sent at [reactionDate] in [threadId], refers to. */
    suspend fun reactionTarget(threadId: Long, reactionDate: Long, r: Reactions.Parsed): Message?

    suspend fun markThreadRead(threadId: Long)

    suspend fun deleteMessage(m: Message)

    suspend fun deleteThread(threadId: Long)
}

/** Sending in a conversation of an [Inbox]. */
interface Outbox {
    /**
     * Queues [text] and [attachments] for [threadId] and returns the key of the message with the text. [prepare] runs
     * with that key before the message appears. [replyTo] is the id of the message this one replies to.
     */
    suspend fun sendToThread(
        threadId: Long,
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        replyTo: String? = null,
        prepare: suspend (key: String) -> Unit = {},
    ): Sender.Result

    /** Sends a failed message again. */
    suspend fun retry(message: Message): Sender.Result?

    /** Adds, switches or removes this user's reaction. */
    suspend fun react(message: Message, emoji: String)
}
