package app.transmit.messaging

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.InvalidationTracker
import app.transmit.data.db.AppDatabase
import app.transmit.data.db.GmConversationEntity
import app.transmit.data.db.GmMessageEntity
import app.transmit.gm.BridgeState
import app.transmit.gm.GmBridge
import app.transmit.gm.GmMapping
import app.transmit.notify.ConversationShortcuts
import app.transmit.gm.proto.MediaContent
import app.transmit.gm.proto.ReactionData
import app.transmit.gm.proto.ConversationStatus
import app.transmit.gm.proto.ConversationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import app.transmit.gm.proto.Conversation as GmConversation
import app.transmit.gm.proto.Message as GmMessage

/**
 * Conversations and messages from Google Messages on the phone, read from Transmit's local cache.
 * Reads never wait on the phone: they return what's cached and ask the bridge to fetch more.
 */
class MessagingRepository(private val context: Context, private val db: AppDatabase, private val bridge: GmBridge) : Inbox {
    private val gmDao = db.gmDao()

    /** True once paired; until then there is nothing to read. */
    override fun canRead(): Boolean = bridge.isPaired || bridge.state.value is BridgeState.Connected

    /** Emits whenever the cached conversations or messages change. */
    override val changes: Flow<Unit> = callbackFlow {
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
    override suspend fun conversations(limit: Int): List<Conversation> = withContext(Dispatchers.IO) {
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
        val snippetStatus: DeliveryStatus?
        val hasMedia: Boolean
        if (latestMsg != null) {
            val media = GmMapping.mediaParts(latestMsg).map { it.mimeType }
            snippet = snippetText(GmMapping.bodyOf(latestMsg), media, GmMapping.isPendingDownload(latestMsg))
            snippetOutgoing = GmMapping.isOutgoing(latestMsg)
            snippetStatus = GmMapping.deliveryStatus(latestMsg).takeIf { snippetOutgoing }
            hasMedia = media.isNotEmpty()
        } else {
            // The latest message isn't cached yet: the conversation's summary of it has its status.
            val summary = conv.latestMessage
            snippet = summary.displayContent
            snippetOutgoing = summary.fromMe == 1L
            snippetStatus = summary.latestMessageStatus.takeIf { snippetOutgoing && it.statusValue in 1..99 }
                ?.let { GmMapping.outgoingStatus(it.status) }
            hasMedia = false
        }
        val recipients = GmMapping.recipients(conv)
        return Conversation(
            threadId = row.threadId,
            recipients = recipients,
            snippet = snippet,
            snippetKey = latestMsg?.let { Message.key(it.messageID) } ?: conv.latestMessageID.takeIf { it.isNotEmpty() }?.let(Message::key),
            snippetOutgoing = snippetOutgoing,
            snippetStatus = snippetStatus,
            date = maxOf(row.lastMessageAt, latest?.timestamp ?: 0),
            unread = row.unread,
            messageCount = 0,
            hasAttachment = hasMedia,
            groupName = conv.name.takeIf { recipients.size > 1 && it.isNotBlank() },
            rcs = conv.type == ConversationType.RCS,
            archivedOnPhone = conv.status == ConversationStatus.ARCHIVED || conv.status == ConversationStatus.KEEP_ARCHIVED,
            groupPhoto = groupPhoto(conv, recipients),
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

    private fun groupPhoto(conv: GmConversation, recipients: List<String>): Uri? =
        conv.groupAvatarURL.takeIf { recipients.size > 1 && it.startsWith("https://") }?.let(Uri::parse)

    override suspend fun groupPhotoForThread(threadId: Long): Uri? = withContext(Dispatchers.IO) {
        bridge.conversation(threadId)?.let { groupPhoto(it, GmMapping.recipients(it)) }
    }

    override suspend fun recipientsForThread(threadId: Long): List<String> = withContext(Dispatchers.IO) {
        bridge.conversation(threadId)?.let(GmMapping::recipients).orEmpty()
    }

    /** The conversation with exactly [recipients], created on the phone if needed. Needs a connection. */
    suspend fun threadIdFor(recipients: Collection<String>): Long = bridge.threadFor(recipients.toList())

    // ---- messages ------------------------------------------------------------------------------

    /**
     * The newest [limit] cached messages (before [beforeMs]), newest first. Opening a conversation
     * fetches its latest messages; asking for more than are cached fetches older ones.
     */
    override suspend fun messages(threadId: Long, limit: Int, beforeMs: Long?): List<Message> = withContext(Dispatchers.IO) {
        bridge.refreshThread(threadId)
        val rows = gmDao.messages(threadId, limit, beforeMs ?: Long.MAX_VALUE)
        if (rows.size < limit && beforeMs == null) bridge.requestOlder(threadId)
        val conv = bridge.conversation(threadId)
        rows.mapNotNull { toMessage(it, conv) }
    }

    override fun requestOlder(threadId: Long): Boolean = bridge.requestOlder(threadId)

    override suspend fun message(key: String): Message? = withContext(Dispatchers.IO) {
        if (!key.startsWith("g")) return@withContext null
        val row = gmDao.message(key.drop(1)) ?: return@withContext null
        toMessage(row, bridge.conversation(row.threadId))
    }

    /**
     * The message that tapback text [r], received or sent at [reactionDate] in [threadId], refers to:
     * the newest earlier message it quotes. Null when that isn't among the last [REACTION_WINDOW].
     */
    override suspend fun reactionTarget(threadId: Long, reactionDate: Long, r: Reactions.Parsed): Message? = withContext(Dispatchers.IO) {
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
    override suspend fun markThreadRead(threadId: Long): Unit = withContext(Dispatchers.IO) {
        bridge.markRead(threadId)
    }

    override suspend fun deleteMessage(m: Message): Unit = withContext(Dispatchers.IO) {
        bridge.deleteMessage(m.messageId)
    }

    override suspend fun deleteThread(threadId: Long): Unit = withContext(Dispatchers.IO) {
        bridge.deleteConversation(threadId)
        ConversationShortcuts.remove(context, threadId)
    }

    // ---- mapping -------------------------------------------------------------------------------

    private fun toMessage(row: GmMessageEntity, conv: GmConversation?): Message? {
        val m = runCatching { GmMessage.parseFrom(row.proto) }.getOrNull() ?: return null
        val recipients = conv?.let(GmMapping::recipients).orEmpty()
        val outgoing = row.outgoing
        val sender = if (outgoing) null else {
            GmMapping.participant(conv, m.participantID)?.let(GmMapping::address)
                ?: m.senderParticipant.takeIf { m.hasSenderParticipant() }?.let(GmMapping::address)
                ?: recipients.singleOrNull()
                ?: m.participantID
        }
        val reactions = m.reactionsList.mapNotNull { entry ->
            GmMapping.reaction(entry, conv)?.also { r -> if (r.photomoji != null) notePhotomoji(r.emoji, entry.data) }
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
            replyTo = GmMapping.replyTo(m)?.let(Message::key),
            replyable = GmMapping.canReplyTo(m),
        )
    }

    /** Names the file version too, so a thumbnail replaced by the full file isn't served from image caches. */
    private fun mediaUri(messageId: String, index: Int, media: MediaContent): Uri =
        Uri.Builder().scheme("content").authority("${context.packageName}.media").appendPath(messageId).appendPath(index.toString())
            .appendQueryParameter("v", media.mediaID.ifEmpty { media.thumbnailMediaID }.hashCode().toUInt().toString(36))
            .build()

    /**
     * Logs where a photomoji's picture is (scheme and host only) the first time it's seen. The protocol's photomoji
     * fields aren't documented, and the Play build's logcat is the one way to see what a phone sends.
     */
    private fun notePhotomoji(key: String, data: ReactionData) {
        if (loggedPhotomoji.add(key)) Log.i(TAG, "Photomoji ${GmMapping.photomojiSource(data)}")
    }

    private companion object {
        const val TAG = "Messaging"
        const val REACTION_WINDOW = 60
        val loggedPhotomoji: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
