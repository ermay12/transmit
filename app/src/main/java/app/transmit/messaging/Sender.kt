package app.transmit.messaging

import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import app.transmit.data.db.GmDao
import app.transmit.data.db.TransmitDao
import app.transmit.gm.GmBridge
import app.transmit.notify.ConversationShortcuts
import app.transmit.gm.GmException
import app.transmit.gm.GmMapping
import app.transmit.gm.proto.Conversation
import app.transmit.gm.proto.ConversationType
import app.transmit.gm.proto.MediaContent
import app.transmit.gm.proto.MessageContent
import app.transmit.gm.proto.MessageInfo
import app.transmit.gm.proto.MessagePayload
import app.transmit.gm.proto.MessageStatus
import app.transmit.gm.proto.MessageStatusType
import app.transmit.gm.proto.ReactionData
import app.transmit.gm.proto.ReplyMessage
import app.transmit.gm.proto.ReplyPayload
import app.transmit.gm.proto.SendMessageRequest
import app.transmit.gm.proto.SendMessageResponse
import app.transmit.gm.proto.SendReactionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import app.transmit.gm.proto.Message as GmMessage

/**
 * Sends through Google Messages on the phone. A sent message shows at once as a placeholder; the
 * phone's copy replaces it when it arrives (matched by tmpID).
 */
class Sender(
    private val context: Context,
    private val gmDao: GmDao,
    private val dao: TransmitDao,
    private val bridge: GmBridge,
    private val scope: CoroutineScope,
    private val onFailed: suspend (threadId: Long) -> Unit,
) : Outbox {
    data class Result(val threadId: Long, val key: String)

    /** [replyTo] is the phone's id for the message this one replies to. */
    private data class Outgoing(val threadId: Long, val text: String, val attachment: OutgoingAttachment?, val replyTo: String? = null)

    /** What each unconfirmed placeholder holds, so a failed one can be sent again. */
    private val outgoing = ConcurrentHashMap<String, Outgoing>()

    /** Placeholder times in microseconds, each later than the last, so parts queued together keep their order. */
    private val placeholderClock = AtomicLong()

    suspend fun send(recipients: List<String>, text: String, attachments: List<OutgoingAttachment> = emptyList()): Result =
        sendToThread(bridge.threadFor(recipients), text, attachments)

    /**
     * Queues [text] and [attachments] for the conversation and returns the key of the message with
     * the text. Each attachment goes as its own message. In an SMS chat the text rides with the last
     * one as an MMS caption; RCS has no captions (the phone rejects a photo with text), so there the
     * text follows as a message of its own. [prepare] runs with that key before the message appears,
     * e.g. to store its translation so it never shows untranslated. With [replyTo] (the phone's id for
     * a message), the first message sent is a reply to it.
     */
    override suspend fun sendToThread(
        threadId: Long,
        text: String,
        attachments: List<OutgoingAttachment>,
        replyTo: String?,
        prepare: suspend (key: String) -> Unit,
    ): Result =
        withContext(Dispatchers.IO) {
            bridge.requireClient()
            for (a in attachments) {
                val size = sizeOf(a)
                if (size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(a.name ?: "Attachment", MAX_ATTACHMENT_BYTES)
            }
            val conv = conversation(threadId)
            val parts = split(text, attachments, captions = conv.type == ConversationType.SMS)
                .mapIndexed { i, (t, a) -> Outgoing(threadId, t, a, replyTo.takeIf { i == 0 }) }
            val queued = parts.mapIndexed { i, p -> queue(conv, p, if (i == parts.lastIndex) prepare else null) }
            // One at a time, so they reach the phone, and so the other person, in order.
            scope.launch { for ((placeholder, p) in queued) deliver(placeholder, p) }
            ConversationShortcuts.reportUsed(context, threadId)
            Result(threadId, Message.key(queued.last().first.messageID))
        }

    private suspend fun conversation(threadId: Long) =
        bridge.conversation(threadId) ?: throw GmException("That conversation isn't on your phone anymore")

    /** Shows [p] as a sending placeholder; [deliver] then sends it. */
    private suspend fun queue(conv: Conversation, p: Outgoing, prepare: (suspend (key: String) -> Unit)? = null): Pair<GmMessage, Outgoing> {
        val tmpId = UUID.randomUUID().toString()
        val placeholderId = GmBridge.PLACEHOLDER + tmpId
        prepare?.invoke(Message.key(placeholderId))
        val infos = buildList {
            p.attachment?.let { a ->
                add(
                    MessageInfo.newBuilder().setMediaContent(
                        MediaContent.newBuilder().setMediaID(GmMapping.LOCAL_MEDIA + a.uri).setMimeType(a.mimeType).setMediaName(a.name ?: ""),
                    ).build(),
                )
            }
            if (p.text.isNotEmpty()) add(MessageInfo.newBuilder().setMessageContent(MessageContent.newBuilder().setContent(p.text)).build())
        }
        val placeholder = GmMessage.newBuilder()
            .setMessageID(placeholderId)
            .setConversationID(conv.conversationID)
            .setTmpID(tmpId)
            .setParticipantID(conv.defaultOutgoingID)
            .setTimestamp(placeholderClock.updateAndGet { maxOf(System.currentTimeMillis() * 1000, it + 1) })
            .setMessageStatus(MessageStatus.newBuilder().setStatus(MessageStatusType.OUTGOING_SENDING))
            .addAllMessageInfo(infos)
            // So the quote shows while it sends.
            .apply { p.replyTo?.let { setReplyMessage(ReplyMessage.newBuilder().setMessageID(it).setConversationID(conv.conversationID)) } }
            .build()
        gmDao.upsertMessages(listOf(GmMapping.messageEntity(placeholder, read = true)))
        outgoing[placeholderId] = p
        return placeholder to p
    }

    private suspend fun deliver(placeholder: GmMessage, p: Outgoing) {
        val tmpId = placeholder.tmpID
        try {
            val conv = bridge.conversation(p.threadId) ?: throw GmException("Conversation not found")
            val infos = buildList {
                p.attachment?.let { add(MessageInfo.newBuilder().setMediaContent(upload(it, placeholder.messageID)).build()) }
                if (p.text.isNotEmpty()) add(MessageInfo.newBuilder().setMessageContent(MessageContent.newBuilder().setContent(p.text)).build())
            }
            val req = SendMessageRequest.newBuilder()
                .setConversationID(conv.conversationID)
                .setMessagePayload(
                    MessagePayload.newBuilder()
                        .setTmpID(tmpId)
                        .setConversationID(conv.conversationID)
                        .setParticipantID(conv.defaultOutgoingID)
                        .addAllMessageInfo(infos)
                        .setTmpID2(tmpId),
                )
                .apply { bridge.simPayload(conv)?.let { setSIMPayload(it) } }
                .apply { p.replyTo?.let { setReply(ReplyPayload.newBuilder().setMessageID(it)) } }
                .setTmpID(tmpId)
                .build()
            var resp = bridge.requireClient().sendMessage(req)
            for (wait in RETRY_DELAYS_MS) {
                if (resp.status != SendMessageResponse.Status.FAILURE_2 && resp.status != SendMessageResponse.Status.FAILURE_3) break
                delay(wait)
                resp = bridge.requireClient().sendMessage(req)
            }
            if (resp.status != SendMessageResponse.Status.SUCCESS) throw GmException(describe(resp.status))
            // The phone's copy normally arrives within seconds; if it doesn't, go and get it.
            scope.launch {
                delay(CONFIRM_TIMEOUT_MS)
                if (gmDao.message(placeholder.messageID) != null) bridge.refreshThread(p.threadId, force = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Send failed", e)
            markFailed(placeholder)
            onFailed(p.threadId)
        }
    }

    private suspend fun markFailed(placeholder: GmMessage) {
        val current = gmDao.message(placeholder.messageID) ?: return
        val failed = GmMessage.parseFrom(current.proto).toBuilder()
            .setMessageStatus(MessageStatus.newBuilder().setStatus(MessageStatusType.OUTGOING_FAILED_GENERIC))
            .build()
        gmDao.upsertMessages(listOf(GmMapping.messageEntity(failed, read = true)))
    }

    /** Sends a failed message again, as a new message. */
    override suspend fun retry(message: Message): Result? = withContext(Dispatchers.IO) {
        val id = message.messageId
        val pending = outgoing.remove(id)
        // The new copy keeps the translation shown for the failed one.
        val keepTranslation: suspend (String) -> Unit = { key -> dao.copyTranslations(message.key, key) }
        if (pending != null) {
            gmDao.deleteMessage(id)
            val (placeholder, p) = queue(conversation(pending.threadId), pending, keepTranslation)
            scope.launch { deliver(placeholder, p) }
            return@withContext Result(message.threadId, Message.key(placeholder.messageID))
        }
        // Failed on the phone: send its text again and drop the failed copy.
        if (message.body.isBlank()) return@withContext null
        val result = sendToThread(message.threadId, message.body, replyTo = message.replyTo?.removePrefix("g"), prepare = keepTranslation)
        runCatching { bridge.deleteMessage(id) }
        result
    }

    /** Adds, switches or removes this user's reaction, as Google Messages does natively. */
    override suspend fun react(message: Message, emoji: String): Unit = withContext(Dispatchers.IO) {
        val mine = message.reactions.firstOrNull { it.mine }
        val action = when {
            mine == null -> SendReactionRequest.Action.ADD
            mine.emoji == emoji -> SendReactionRequest.Action.REMOVE
            else -> SendReactionRequest.Action.SWITCH
        }
        val conv = bridge.conversation(message.threadId)
        val req = SendReactionRequest.newBuilder()
            .setMessageID(message.messageId)
            .setReactionData(ReactionData.newBuilder().setUnicode(emoji).setType(GmMapping.emojiType(emoji)))
            .setAction(action)
            .apply { conv?.let(bridge::simPayload)?.let { setSIMPayload(it) } }
            .build()
        val resp = bridge.requireClient().sendReaction(req)
        if (!resp.success) throw GmException("Google Messages didn't accept the reaction")
    }

    /**
     * Uploads [a] for the phone to send. The phone's copy of the message usually comes back without
     * the file, so the placeholder keeps this upload (and a local copy) for the copy to take over.
     */
    private suspend fun upload(a: OutgoingAttachment, placeholderId: String): MediaContent {
        val bytes = context.contentResolver.openInputStream(a.uri)?.use { it.readBytes() }
            ?: throw GmException("Couldn't read ${a.name ?: "the attachment"}")
        val name = a.name ?: displayName(a) ?: "attachment"
        val media = bridge.requireClient().uploadMedia(bytes, name, a.mimeType)
        // Before sending, so it's in place however fast the phone's copy arrives.
        bridge.attachUpload(placeholderId, media, bytes)
        return media
    }

    private fun sizeOf(a: OutgoingAttachment): Long = runCatching {
        context.contentResolver.query(a.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull() ?: 0L

    private fun displayName(a: OutgoingAttachment): String? = runCatching {
        context.contentResolver.query(a.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun describe(status: SendMessageResponse.Status) = when (status) {
        SendMessageResponse.Status.FAILURE_4 -> "Google Messages isn't the default SMS app on your phone"
        SendMessageResponse.Status.FAILURE_2 -> "Google Messages couldn't send it. Check that your phone has signal."
        else -> "Google Messages on your phone rejected the message ($status)"
    }

    internal companion object {
        private const val TAG = "Sender"
        private const val MAX_ATTACHMENT_BYTES = 100_000_000
        private val RETRY_DELAYS_MS = listOf(3_000L, 8_000L, 20_000L)
        private const val CONFIRM_TIMEOUT_MS = 45_000L

        /**
         * The text and attachment of each message [text] and [attachments] go as, in order. With
         * [captions] (SMS chats) the text rides with the last attachment; otherwise it comes last on its own.
         */
        internal fun <A : Any> split(text: String, attachments: List<A>, captions: Boolean): List<Pair<String, A?>> = when {
            attachments.isEmpty() -> listOf(text to null)
            captions -> attachments.mapIndexed { i, a -> (if (i == attachments.lastIndex) text else "") to a }
            else -> attachments.map { "" to it } + listOfNotNull((text to null).takeIf { text.isNotBlank() })
        }
    }
}
