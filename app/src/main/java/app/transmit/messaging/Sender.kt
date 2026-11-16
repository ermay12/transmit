package app.transmit.messaging

import android.content.Context
import android.provider.OpenableColumns
import android.util.Log
import app.transmit.data.db.GmDao
import app.transmit.data.db.TransmitDao
import app.transmit.gm.GmBridge
import app.transmit.gm.GmException
import app.transmit.gm.GmMapping
import app.transmit.gm.proto.MediaContent
import app.transmit.gm.proto.MessageContent
import app.transmit.gm.proto.MessageInfo
import app.transmit.gm.proto.MessagePayload
import app.transmit.gm.proto.MessageStatus
import app.transmit.gm.proto.MessageStatusType
import app.transmit.gm.proto.ReactionData
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
) {
    data class Result(val threadId: Long, val key: String)

    private data class Outgoing(val threadId: Long, val text: String, val attachment: OutgoingAttachment?)

    /** What each unconfirmed placeholder holds, so a failed one can be sent again. */
    private val outgoing = ConcurrentHashMap<String, Outgoing>()

    suspend fun send(recipients: List<String>, text: String, attachments: List<OutgoingAttachment> = emptyList()): Result =
        sendToThread(bridge.threadFor(recipients), text, attachments)

    /**
     * Queues [text] and [attachments] for the conversation and returns the key of the message with
     * the text. Each attachment goes as its own message (what RCS allows); the text rides with the last.
     * [prepare] runs with that key before the message appears, e.g. to store its translation so it
     * never shows untranslated.
     */
    suspend fun sendToThread(
        threadId: Long,
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        prepare: suspend (key: String) -> Unit = {},
    ): Result =
        withContext(Dispatchers.IO) {
            bridge.requireClient()
            for (a in attachments) {
                val size = sizeOf(a)
                if (size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(a.name ?: "Attachment", MAX_ATTACHMENT_BYTES)
            }
            val parts = if (attachments.isEmpty()) listOf(Outgoing(threadId, text, null))
            else attachments.mapIndexed { i, a -> Outgoing(threadId, if (i == attachments.lastIndex) text else "", a) }
            var key = ""
            parts.forEachIndexed { i, p -> key = queue(p, if (i == parts.lastIndex) prepare else null) }
            Result(threadId, key)
        }

    private suspend fun queue(p: Outgoing, prepare: (suspend (key: String) -> Unit)? = null): String {
        val conv = bridge.conversation(p.threadId) ?: throw GmException("That conversation isn't on your phone anymore")
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
            .setTimestamp(System.currentTimeMillis() * 1000)
            .setMessageStatus(MessageStatus.newBuilder().setStatus(MessageStatusType.OUTGOING_SENDING))
            .addAllMessageInfo(infos)
            .build()
        gmDao.upsertMessages(listOf(GmMapping.messageEntity(placeholder, read = true)))
        outgoing[placeholderId] = p
        scope.launch { deliver(placeholder, p) }
        return Message.key(placeholderId)
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
            delay(CONFIRM_TIMEOUT_MS)
            if (gmDao.message(placeholder.messageID) != null) bridge.refreshThread(p.threadId, force = true)
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
    suspend fun retry(message: Message): Result? = withContext(Dispatchers.IO) {
        val id = message.messageId
        val pending = outgoing.remove(id)
        // The new copy keeps the translation shown for the failed one.
        val keepTranslation: suspend (String) -> Unit = { key -> dao.copyTranslations(message.key, key) }
        if (pending != null) {
            gmDao.deleteMessage(id)
            return@withContext Result(message.threadId, queue(pending, keepTranslation))
        }
        // Failed on the phone: send its text again and drop the failed copy.
        if (message.body.isBlank()) return@withContext null
        val result = sendToThread(message.threadId, message.body, prepare = keepTranslation)
        runCatching { bridge.deleteMessage(id) }
        result
    }

    /** Adds, switches or removes this user's reaction, as Google Messages does natively. */
    suspend fun react(message: Message, emoji: String) = withContext(Dispatchers.IO) {
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

    private companion object {
        const val TAG = "Sender"
        const val MAX_ATTACHMENT_BYTES = 100_000_000
        val RETRY_DELAYS_MS = listOf(3_000L, 8_000L, 20_000L)
        const val CONFIRM_TIMEOUT_MS = 45_000L
    }
}
