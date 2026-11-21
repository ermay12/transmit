package app.transmit.gm

import app.transmit.data.db.GmConversationEntity
import app.transmit.data.db.GmMessageEntity
import app.transmit.gm.proto.Conversation
import app.transmit.gm.proto.ConversationStatus
import app.transmit.gm.proto.EmojiType
import app.transmit.gm.proto.MediaContent
import app.transmit.gm.proto.MessageStatusType
import app.transmit.gm.proto.Participant
import app.transmit.gm.proto.ReactionData
import app.transmit.gm.proto.ReactionEntry
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.PhoneNumbers
import app.transmit.messaging.Photomoji
import app.transmit.messaging.Reaction
import app.transmit.gm.proto.Message as GmMessage

/** Conversions between the phone's protocol messages and Transmit's cache rows. */
object GmMapping {
    private const val THREAD_BASE = 10_000_000_000L

    /**
     * Transmit's numeric thread id for a conversation. Google Messages ids are small numbers; the
     * offset keeps them apart from the SMS-era thread ids that per-chat settings were saved under.
     */
    fun threadId(conversationId: String): Long =
        conversationId.toLongOrNull()?.let { THREAD_BASE + it }
            ?: (2 * THREAD_BASE + (conversationId.hashCode().toLong() and 0x7fffffffL))

    fun conversationEntity(conv: Conversation) = GmConversationEntity(
        threadId = threadId(conv.conversationID),
        conversationId = conv.conversationID,
        lastMessageAt = conv.lastMessageTimestamp / 1000,
        unread = conv.unread,
        status = conv.statusValue,
        proto = conv.toByteArray(),
    )

    /**
     * Whether the phone calls [conv] unread only because it hasn't caught up with the user reading
     * it in Transmit up to [readUpToMs]: nothing has arrived since.
     */
    fun isStaleUnread(conv: Conversation, readUpToMs: Long?) =
        conv.unread && readUpToMs != null && conv.lastMessageTimestamp / 1000 <= readUpToMs

    /**
     * The newest message time (ms) that [conv], when the phone calls it read, covers: its latest message's
     * ([latestMs], when that message is cached) or the conversation's own time. A conversation update can trail
     * the messages, so anything newer arrived since and is still unread.
     */
    fun readUpTo(conv: Conversation, latestMs: Long?): Long = maxOf(conv.lastMessageTimestamp / 1000, latestMs ?: Long.MIN_VALUE)

    /**
     * Whether [conv]'s read state covers a received message at [timestampMs] that is new to the cache: the
     * conversation is read, and the message is no newer than its latest one (at [latestMs], when known).
     */
    fun readByConversation(conv: Conversation, timestampMs: Long, latestMs: Long?) =
        !conv.unread && timestampMs <= readUpTo(conv, latestMs)

    fun messageEntity(msg: GmMessage, read: Boolean) = GmMessageEntity(
        messageId = msg.messageID,
        threadId = threadId(msg.conversationID),
        timestamp = msg.timestamp / 1000,
        outgoing = isOutgoing(msg),
        read = read,
        tmpId = msg.tmpID.takeIf { it.isNotEmpty() },
        proto = msg.toByteArray(),
    )

    /** Statuses 1–99 are outgoing, 100–199 incoming, 200+ are timeline notices ("group created"). */
    fun isOutgoing(msg: GmMessage): Boolean {
        val s = msg.messageStatus.statusValue
        return when {
            s in 1..99 -> true
            s in 100..199 -> false
            else -> msg.participantID == "1"
        }
    }

    fun isNotice(msg: GmMessage) = msg.messageStatus.statusValue >= 200

    /** The phone's id for the message [msg] replies to; null when it isn't a reply. */
    fun replyTo(msg: GmMessage): String? = msg.replyMessage.messageID.takeIf { msg.hasReplyMessage() && it.isNotEmpty() }

    /**
     * Whether a reply can point at [msg]. Only RCS messages can be replied to: SMS and MMS (types 1–3)
     * can't carry the link, so mautrix-gmessages sends no reply for them either. A message still
     * sending, or one that failed, isn't on the other person's phone to point at.
     */
    fun canReplyTo(msg: GmMessage): Boolean =
        msg.type !in 1L..3L && !msg.messageID.startsWith(GmBridge.PLACEHOLDER) &&
            deliveryStatus(msg).let { it != DeliveryStatus.FAILED && it != DeliveryStatus.SENDING }

    fun isDeleted(msg: GmMessage) = msg.messageStatus.status in setOf(
        MessageStatusType.MESSAGE_DELETED, MessageStatusType.INCOMING_DELETED, MessageStatusType.OUTGOING_DELETED,
    )

    fun deliveryStatus(msg: GmMessage): DeliveryStatus =
        if (isOutgoing(msg)) outgoingStatus(msg.messageStatus.status) else DeliveryStatus.RECEIVED

    /** The receipt for an outgoing message the phone gives [status]. */
    fun outgoingStatus(status: MessageStatusType): DeliveryStatus = when {
        status == MessageStatusType.OUTGOING_DISPLAYED -> DeliveryStatus.READ
        status == MessageStatusType.OUTGOING_DELIVERED -> DeliveryStatus.DELIVERED
        status == MessageStatusType.OUTGOING_COMPLETE || status == MessageStatusType.OUTGOING_NOT_DELIVERED_YET -> DeliveryStatus.SENT
        status.name.startsWith("OUTGOING_FAILED") || status == MessageStatusType.OUTGOING_CANCELED ||
            status == MessageStatusType.OUTGOING_RESTRICTED -> DeliveryStatus.FAILED
        else -> DeliveryStatus.SENDING
    }

    /** Participant ids that are this phone's own SIMs. */
    fun selfIds(conv: Conversation?): Set<String> = buildSet {
        add("1")
        conv?.defaultOutgoingID?.takeIf { it.isNotEmpty() }?.let(::add)
        conv?.participantsList?.filter { it.isMe }?.forEach { add(it.id.participantID) }
    }

    /** The other people in a conversation, as addresses (phone numbers, or ids for business chats). */
    fun recipients(conv: Conversation): List<String> {
        val others = conv.participantsList.filter { !it.isMe }
        val visible = others.filter { it.isVisible }.ifEmpty { others }
        return visible.map(::address).filter { it.isNotEmpty() }.distinct()
    }

    fun address(p: Participant): String =
        p.id.number.ifEmpty { p.formattedNumber }.ifEmpty { p.id.participantID }

    /**
     * Names Google Messages shows for the other people in [conv], by address, for addresses not in
     * the address book: each participant's own name, and for a one-to-one chat the conversation's
     * name. Business (RBM) chats such as "Ramp" arrive named only on the conversation.
     */
    fun names(conv: Conversation): List<Pair<String, String>> {
        val named = conv.participantsList.filter { !it.isMe }.mapNotNull { p ->
            val address = address(p)
            p.fullName.ifBlank { p.firstName }.takeIf { isName(it, address) }?.let { address to it.trim() }
        }
        val single = recipients(conv).singleOrNull()
        if (single == null || conv.isGroupChat || named.any { it.first == single } || !isName(conv.name, single)) return named
        return named + (single to conv.name.trim())
    }

    /** Whether [name] says more than [address] does: a stranger's chat is named after their number. */
    private fun isName(name: String, address: String) =
        name.isNotBlank() && !name.trim().equals(address, ignoreCase = true) && !PhoneNumbers.same(name, address)

    fun participant(conv: Conversation?, participantId: String): Participant? =
        conv?.participantsList?.firstOrNull { it.id.participantID == participantId }

    /**
     * A reaction the phone tracks on a message, with who reacted; null when it names no one or no emoji. A photomoji
     * comes with its id where the emoji goes, which is kept as the key a removal sends back.
     */
    fun reaction(entry: ReactionEntry, conv: Conversation?): Reaction? {
        val ids = entry.participantIDsList.distinct()
        if (ids.isEmpty()) return null
        val data = entry.data
        val photomoji = photomoji(data)
        val emoji = data.unicode.ifEmpty { if (photomoji != null) data.customEmoji.uuid.ifEmpty { PHOTOMOJI } else emoji(data.type) }
        if (emoji.isEmpty()) return null
        val self = selfIds(conv)
        return Reaction(
            emoji = emoji,
            count = ids.size,
            mine = ids.any { it in self },
            others = ids.filter { it !in self }.mapNotNull { id -> participant(conv, id)?.let(::address)?.takeIf { it.isNotEmpty() } },
            photomoji = photomoji,
        )
    }

    /** The key of a photomoji the phone sent without an id. */
    private const val PHOTOMOJI = "photomoji"

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * The picture of a photomoji, when [data] is one: it carries custom-emoji data, or (as seen on Android 17, 2026-11)
     * its id where the emoji goes. Only a web address is kept, the sized copy first; the phone's own files are out of reach.
     */
    fun photomoji(data: ReactionData): Photomoji? {
        if (!data.hasCustomEmoji() && data.type != EmojiType.EMOTIFY && !uuid.matches(data.unicode)) return null
        val inner = data.customEmoji.innerData
        val image = listOf(inner.second.data.uri, inner.first.uri).firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
        return Photomoji(image)
    }

    /**
     * Where [data]'s photomoji pictures point, without the addresses themselves: the scheme and host of each, and the
     * MIME type. Logged once per photomoji, since which of these the phone sends isn't documented.
     */
    fun photomojiSource(data: ReactionData): String {
        fun where(uri: String) = if (uri.isEmpty()) "none" else uri.substringBefore("://", "?") + "://" + uri.substringAfter("://", "").substringBefore('/')
        val inner = data.customEmoji.innerData
        return "type=${data.type} custom=${data.hasCustomEmoji()} sized=${where(inner.second.data.uri)} (${inner.second.data.mimeType}) " +
            "plain=${where(inner.first.uri)} (${inner.first.mimeType})"
    }

    fun emoji(type: EmojiType): String = when (type) {
        EmojiType.LIKE -> "👍"
        EmojiType.LOVE -> "😍"
        EmojiType.LAUGH -> "😂"
        EmojiType.SURPRISED -> "😮"
        EmojiType.SAD -> "😥"
        EmojiType.ANGRY -> "😠"
        EmojiType.DISLIKE -> "👎"
        EmojiType.QUESTIONING -> "🤔"
        EmojiType.CRYING_FACE -> "😢"
        EmojiType.POUTING_FACE -> "😡"
        EmojiType.RED_HEART -> "❤️"
        else -> ""
    }

    fun emojiType(emoji: String): EmojiType = when (emoji) {
        "👍" -> EmojiType.LIKE
        "😍" -> EmojiType.LOVE
        "😂" -> EmojiType.LAUGH
        "😮" -> EmojiType.SURPRISED
        "😥" -> EmojiType.SAD
        "😠" -> EmojiType.ANGRY
        "👎" -> EmojiType.DISLIKE
        "🤔" -> EmojiType.QUESTIONING
        "😢" -> EmojiType.CRYING_FACE
        "😡" -> EmojiType.POUTING_FACE
        "❤", "❤️" -> EmojiType.RED_HEART
        else -> EmojiType.CUSTOM
    }

    /** Media still on this device, shown while a sent message uploads: the id holds its content URI. */
    const val LOCAL_MEDIA = "local:"

    fun bodyOf(m: GmMessage): String = m.messageInfoList.mapNotNull { info ->
        when {
            info.hasMessageContent() -> info.messageContent.content
            info.hasLocationContent() && info.locationContent.hasMessageContent() -> info.locationContent.messageContent.content
            else -> null
        }
    }.filter { it.isNotEmpty() }.joinToString("\n")

    /**
     * Every attachment of [m], including ones the phone hasn't made available yet (see [hasMedia]);
     * attachment indexes everywhere refer to this list.
     */
    fun mediaParts(m: GmMessage): List<MediaContent> = m.messageInfoList.mapNotNull { info ->
        when {
            info.hasMediaContent() -> info.mediaContent
            info.hasLocationContent() && info.locationContent.hasMediaContent() -> info.locationContent.mediaContent
            else -> null
        }
    }

    /** Whether there's anything to download yet: the full file, a thumbnail, or the bytes inline. */
    fun hasMedia(m: MediaContent) = m.mediaID.isNotEmpty() || m.thumbnailMediaID.isNotEmpty() || !m.mediaData.isEmpty

    private fun isUploaded(m: MediaContent) = m.mediaID.isNotEmpty() && !m.mediaID.startsWith(LOCAL_MEDIA)

    /**
     * [new] with the attachments of [old] (an earlier copy of the same message) wherever [new] has no
     * full-size file, or wherever [preferOld] says the old one is better. The phone's copy of a photo
     * Transmit sent can arrive without its media, and so can later status updates of any message.
     */
    fun keepMedia(new: GmMessage, old: GmMessage, preferOld: (MediaContent) -> Boolean = { false }): GmMessage {
        val oldMedia = old.messageInfoList.filter { it.hasMediaContent() }.map { it.mediaContent }
        if (oldMedia.none(::isUploaded)) return new
        val b = new.toBuilder()
        var i = 0
        var changed = false
        for (k in 0 until b.messageInfoCount) {
            val info = b.getMessageInfo(k)
            if (!info.hasMediaContent()) continue
            val prev = oldMedia.getOrNull(i++)?.takeIf(::isUploaded) ?: continue
            val cur = info.mediaContent
            if (cur.mediaID == prev.mediaID || (cur.mediaID.isNotEmpty() && !preferOld(prev))) continue
            val merged = cur.toBuilder()
                .setMediaID(prev.mediaID)
                .setDecryptionKey(prev.decryptionKey)
                .apply {
                    if (mimeType.isEmpty()) mimeType = prev.mimeType
                    if (mediaName.isEmpty()) mediaName = prev.mediaName
                    if (size == 0L) size = prev.size
                    if (!hasDimensions() && prev.hasDimensions()) dimensions = prev.dimensions
                }
            b.setMessageInfo(k, info.toBuilder().setMediaContent(merged))
            changed = true
        }
        return if (changed) b.build() else new
    }

    /** An MMS the phone hasn't (successfully) downloaded. */
    fun isPendingDownload(m: GmMessage) = m.messageStatus.status in setOf(
        MessageStatusType.INCOMING_YET_TO_MANUAL_DOWNLOAD, MessageStatusType.INCOMING_MANUAL_DOWNLOADING,
        MessageStatusType.INCOMING_RETRYING_MANUAL_DOWNLOAD, MessageStatusType.INCOMING_AUTO_DOWNLOADING,
        MessageStatusType.INCOMING_RETRYING_AUTO_DOWNLOAD, MessageStatusType.INCOMING_DOWNLOAD_FAILED,
        MessageStatusType.INCOMING_EXPIRED_OR_NOT_AVAILABLE, MessageStatusType.INCOMING_AWAITING_AUTO_DOWNLOAD,
    )

    fun isListed(status: Int) = status in setOf(
        ConversationStatus.UNKNOWN_CONVERSATION_STATUS_VALUE, ConversationStatus.ACTIVE_VALUE,
        ConversationStatus.ARCHIVED_VALUE, ConversationStatus.KEEP_ARCHIVED_VALUE,
    )
}
