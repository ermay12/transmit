package app.transmit.messaging

import android.net.Uri

enum class MessageKind { SMS, MMS, RCS }

enum class DeliveryStatus { RECEIVED, SENDING, SENT, DELIVERED, READ, FAILED }

data class Attachment(
    val partId: Long,
    val uri: Uri,
    val mimeType: String,
    val name: String?,
    /** Google Messages hasn't made the file available yet. */
    val pending: Boolean = false,
) {
    val isImage get() = mimeType.startsWith("image/")
    val isVideo get() = mimeType.startsWith("video/")
    val isAudio get() = mimeType.startsWith("audio/")
    val isContactCard get() = mimeType.contains("vcard", ignoreCase = true)
}

/**
 * A reaction on a message: one emoji (or photomoji) and who reacted with it. Google Messages tracks RCS reactions and
 * the SMS tapbacks it recognized; the chat adds tapback texts it didn't.
 */
data class Reaction(
    /** The emoji, or a photomoji's id. */
    val emoji: String,
    /** How many people reacted with it, the user included. */
    val count: Int,
    /** The user reacted with it. */
    val mine: Boolean,
    /**
     * The other people who reacted with it, by address. Fewer than [count] says (less the user) when the phone names
     * someone it doesn't list in the chat, such as a person who left the group.
     */
    val others: List<String> = emptyList(),
    /** Set when this is a photomoji, a picture Google Messages lets people react with, rather than an emoji. */
    val photomoji: Photomoji? = null,
)

/** A photomoji's picture: [image] is its web address, or null when the phone sent none that Transmit can fetch. */
data class Photomoji(val image: String?)

/** A message in a Google Messages conversation. [key] is stable: "g" + the phone's message id. */
data class Message(
    val key: String,
    val id: Long,
    val kind: MessageKind,
    val threadId: Long,
    /** Sender for incoming messages; null for outgoing. */
    val sender: String?,
    /** The other people in the conversation, for outgoing messages. */
    val recipients: List<String>,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val outgoing: Boolean,
    val status: DeliveryStatus,
    val read: Boolean,
    val subId: Int,
    val attachments: List<Attachment> = emptyList(),
    val subject: String? = null,
    /** An MMS the phone hasn't downloaded yet. */
    val mmsPending: Boolean = false,
    val sizeBytes: Long = 0,
    val errorCode: Int = 0,
    val reactions: List<Reaction> = emptyList(),
    /** The [key] of the message this one replies to; null when it isn't a reply. */
    val replyTo: String? = null,
    /** A reply can point at this message (RCS messages that reached the phone). */
    val replyable: Boolean = false,
) {
    val hasText get() = body.isNotBlank()
    /** The phone's id for this message. */
    val messageId get() = key.removePrefix("g")

    companion object {
        fun key(messageId: String) = "g$messageId"
    }
}

data class Conversation(
    val threadId: Long,
    val recipients: List<String>,
    val snippet: String,
    val snippetKey: String?,
    val snippetOutgoing: Boolean,
    /** The receipt of the latest message when it is outgoing; null when unknown. */
    val snippetStatus: DeliveryStatus? = null,
    val date: Long,
    val unread: Boolean,
    val messageCount: Int,
    val hasAttachment: Boolean,
    /** The group's name in Google Messages; null for one-to-one chats. */
    val groupName: String? = null,
    val rcs: Boolean = false,
    /** Archived in Google Messages on the phone. */
    val archivedOnPhone: Boolean = false,
    /** The group's picture in Google Messages (RCS groups); null for one-to-one chats. */
    val groupPhoto: Uri? = null,
) {
    val isGroup get() = recipients.size > 1
}

/** Media chosen in the compose bar, before it is uploaded. */
data class OutgoingAttachment(
    val uri: Uri,
    val mimeType: String,
    val name: String? = null,
)

class AttachmentTooLargeException(val name: String, val limitBytes: Int) :
    Exception("$name is too large to send (limit ${limitBytes / 1_000_000} MB)")
