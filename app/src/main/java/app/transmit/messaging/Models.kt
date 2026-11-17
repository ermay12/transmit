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

/** A reaction Google Messages tracks on a message (RCS reactions, and SMS tapbacks it recognized). */
data class NativeReaction(val emoji: String, val count: Int, val mine: Boolean)

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
    val reactions: List<NativeReaction> = emptyList(),
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
