package app.transmit.messaging

import android.content.Context
import android.net.Uri
import androidx.room.InvalidationTracker
import app.transmit.data.db.AppDatabase
import app.transmit.data.db.GmConversationEntity
import app.transmit.data.db.GmMessageEntity
import app.transmit.gm.BridgeState
import app.transmit.gm.GmBridge
import app.transmit.gm.GmMapping
import app.transmit.gm.proto.MediaContent
import app.transmit.gm.proto.ConversationStatus
import app.transmit.gm.proto.ConversationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import app.transmit.gm.proto.Conversation as GmConversation
import app.transmit.gm.proto.Message as GmMessage

/**
 * Conversations and messages from Google Messages on the phone, read from Transmit's local cache.
 * Reads never wait on the phone: they return what's cached and ask the bridge to fetch more.
 */
class MessagingRepository(private val context: Context, private val db: AppDatabase, private val bridge: GmBridge) {
    private val gmDao = db.gmDao()

    /** True once paired; until then there is nothing to read. */
    fun canRead(): Boolean = bridge.isPaired || bridge.state.value is BridgeState.Connected

    /** Emits whenever the cached conversations or messages change. */
    val changes: Flow<Unit> = callbackFlow {
        val observer = object : InvalidationTracker.Observer(arrayOf("gm_conversations", "gm_messages")) {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        db.invalidationTracker.addObserver(observer)
        awaitClose { db.invalidationTracker.removeObserver(observer) }
    }.conflate()

    // ---- conversations -------------------------------------------------------------------------

    /** Conversations, newest first. */
    suspend fun conversations(limit: Int = Int.MAX_VALUE): List<Conversation> = withContext(Dispatchers.IO) {
        val rows = gmDao.conversations().take(limit)
        val latest = gmDao.latestMessages().associateBy { it.threadId }
        rows.mapNotNull { row ->
            val conv = runCatching { GmConversation.parseFrom(row.proto) }.getOrNull() ?: return@mapNotNull null
            toConversation(row, conv, latest[row.threadId])
        }
    }

    private fun toConversation(row: GmConversationEntity, conv: GmConversation, latest: GmMessageEntity?): Conversation {
        val latestMsg = latest?.takeIf { it.timestamp >= row.lastMessageAt }?.let { runCatching { GmMessage.parseFrom(it.proto) }.getOrNull() }
        val snippet: String
        val snippetOutgoing: Boolean
        val hasMedia: Boolean
        if (latestMsg != null) {
            val media = GmMapping.mediaParts(latestMsg).map { it.mimeType }
            snippet = snippetText(GmMapping.bodyOf(latestMsg), media, GmMapping.isPendingDownload(latestMsg))
            snippetOutgoing = GmMapping.isOutgoing(latestMsg)
            hasMedia = media.isNotEmpty()
        } else {
            snippet = conv.latestMessage.displayContent
            snippetOutgoing = conv.latestMessage.fromMe == 1L
            hasMedia = false
        }
        val recipients = GmMapping.recipients(conv)
        return Conversation(
            threadId = row.threadId,
            recipients = recipients,
            snippet = snippet,
            snippetKey = latestMsg?.let { Message.key(it.messageID) } ?: conv.latestMessageID.takeIf { it.isNotEmpty() }?.let(Message::key),
            snippetOutgoing = snippetOutgoing,
            date = maxOf(row.lastMessageAt, latest?.timestamp ?: 0),
            unread = row.unread,
            messageCount = 0,
            hasAttachment = hasMedia,
            groupName = conv.name.takeIf { recipients.size > 1 && it.isNotBlank() },
            rcs = conv.type == ConversationType.RCS,
            archivedOnPhone = conv.status == ConversationStatus.ARCHIVED || conv.status == ConversationStatus.KEEP_ARCHIVED,
        )
    }

    private fun snippetText(body: String, media: List<String>, pending: Boolean): String = when {
        body.isNotBlank() -> body
        media.any { it.startsWith("image/") } -> "📷"
        media.any { it.startsWith("video/") } -> "🎬"
        media.any { it.startsWith("audio/") } -> "🎵"
        media.isNotEmpty() -> "📎"
        pending -> "⬇"
        else -> ""
    }

    suspend fun recipientsForThread(threadId: Long): List<String> = withContext(Dispatchers.IO) {
        bridge.conversation(threadId)?.let(GmMapping::recipients).orEmpty()
    }

    /** The conversation with exactly [recipients], created on the phone if needed. Needs a connection. */
    suspend fun threadIdFor(recipients: Collection<String>): Long = bridge.threadFor(recipients.toList())

    // ---- messages ------------------------------------------------------------------------------

    /**
     * The newest [limit] cached messages (before [beforeMs]), newest first. Opening a conversation
     * fetches its latest messages; asking for more than are cached fetches older ones.
     */
    suspend fun messages(threadId: Long, limit: Int, beforeMs: Long? = null): List<Message> = withContext(Dispatchers.IO) {
        bridge.refreshThread(threadId)
        val rows = gmDao.messages(threadId, limit, beforeMs ?: Long.MAX_VALUE)
        if (rows.size < limit && beforeMs == null) bridge.requestOlder(threadId)
        val conv = bridge.conversation(threadId)
        rows.mapNotNull { toMessage(it, conv) }
    }

    suspend fun message(key: String): Message? = withContext(Dispatchers.IO) {
        if (!key.startsWith("g")) return@withContext null
        val row = gmDao.message(key.drop(1)) ?: return@withContext null
        toMessage(row, bridge.conversation(row.threadId))
    }

    /**
     * The message that tapback text [r], received or sent at [reactionDate] in [threadId], refers to:
     * the newest earlier message it quotes. Null when that isn't among the last [REACTION_WINDOW].
     */
    suspend fun reactionTarget(threadId: Long, reactionDate: Long, r: Reactions.Parsed): Message? = withContext(Dispatchers.IO) {
        val conv = bridge.conversation(threadId)
        Reactions.target(r, gmDao.messages(threadId, REACTION_WINDOW, reactionDate).mapNotNull { toMessage(it, conv) })
    }

    suspend fun unreadIncoming(threadId: Long, limit: Int = 8): List<Message> = withContext(Dispatchers.IO) {
        val conv = bridge.conversation(threadId)
        gmDao.unreadIncoming(threadId, limit).mapNotNull { toMessage(it, conv) }.sortedBy { it.date }
    }

    suspend fun unreadThreadIds(): Set<Long> = withContext(Dispatchers.IO) {
        gmDao.unreadThreadIds().toSet() + gmDao.conversations().filter { it.unread }.map { it.threadId }
    }

    /** Marks the conversation read here and on the phone. */
    suspend fun markThreadRead(threadId: Long) = withContext(Dispatchers.IO) {
        bridge.markRead(threadId)
    }

    suspend fun deleteMessage(m: Message) = withContext(Dispatchers.IO) {
        bridge.deleteMessage(m.messageId)
    }

    suspend fun deleteThread(threadId: Long) = withContext(Dispatchers.IO) {
        bridge.deleteConversation(threadId)
    }

    // ---- mapping -------------------------------------------------------------------------------

    private fun toMessage(row: GmMessageEntity, conv: GmConversation?): Message? {
        val m = runCatching { GmMessage.parseFrom(row.proto) }.getOrNull() ?: return null
        val selfIds = GmMapping.selfIds(conv)
        val recipients = conv?.let(GmMapping::recipients).orEmpty()
        val outgoing = row.outgoing
        val sender = if (outgoing) null else {
            GmMapping.participant(conv, m.participantID)?.let(GmMapping::address)
                ?: m.senderParticipant.takeIf { m.hasSenderParticipant() }?.let(GmMapping::address)
                ?: recipients.singleOrNull()
                ?: m.participantID
        }
        val reactions = m.reactionsList.mapNotNull { r ->
            val emoji = r.data.unicode.ifEmpty { GmMapping.emoji(r.data.type) }
            if (emoji.isEmpty() || r.participantIDsCount == 0) null
            else NativeReaction(emoji, r.participantIDsCount, r.participantIDsList.any { it in selfIds })
        }
        val status = GmMapping.deliveryStatus(m)
        return Message(
            key = Message.key(row.messageId),
            id = row.messageId.toLongOrNull() ?: row.messageId.hashCode().toLong(),
            kind = when {
                conv?.type == ConversationType.RCS || m.type == 4L -> MessageKind.RCS
                m.type == 2L || m.type == 3L -> MessageKind.MMS
                else -> MessageKind.SMS
            },
            threadId = row.threadId,
            sender = sender,
            recipients = recipients,
            body = GmMapping.bodyOf(m),
            date = row.timestamp,
            dateSent = row.timestamp,
            outgoing = outgoing,
            status = status,
            read = row.read,
            subId = 0,
            attachments = GmMapping.mediaParts(m).mapIndexed { i, media ->
                val local = media.mediaID.startsWith(GmMapping.LOCAL_MEDIA)
                val uri = if (local) Uri.parse(media.mediaID.removePrefix(GmMapping.LOCAL_MEDIA)) else mediaUri(row.messageId, i, media)
                Attachment(
                    i.toLong(), uri, media.mimeType.ifEmpty { "application/octet-stream" }, media.mediaName.ifEmpty { null },
                    pending = !local && !GmMapping.hasMedia(media),
                )
            },
            subject = m.subject.takeIf { m.hasSubject() && it.isNotBlank() },
            mmsPending = GmMapping.isPendingDownload(m),
            sizeBytes = GmMapping.mediaParts(m).sumOf { it.size },
            errorCode = if (status == DeliveryStatus.FAILED) m.messageStatus.subCode.toInt() else 0,
            reactions = reactions,
        )
    }

    /** Names the file version too, so a thumbnail replaced by the full file isn't served from image caches. */
    private fun mediaUri(messageId: String, index: Int, media: MediaContent): Uri =
        Uri.Builder().scheme("content").authority("${context.packageName}.media").appendPath(messageId).appendPath(index.toString())
            .appendQueryParameter("v", media.mediaID.ifEmpty { media.thumbnailMediaID }.hashCode().toUInt().toString(36))
            .build()

    private companion object {
        const val REACTION_WINDOW = 60
    }
}
