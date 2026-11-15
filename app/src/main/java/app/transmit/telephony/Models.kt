package app.transmit.telephony

import android.net.Uri

enum class MessageKind { SMS, MMS }

enum class DeliveryStatus { RECEIVED, SENDING, SENT, DELIVERED, FAILED }

data class Attachment(
    val partId: Long,
    val uri: Uri,
    val mimeType: String,
    val name: String?,
) {
    val isImage get() = mimeType.startsWith("image/")
    val isVideo get() = mimeType.startsWith("video/")
    val isAudio get() = mimeType.startsWith("audio/")
    val isContactCard get() = mimeType.contains("vcard", ignoreCase = true)
}

/** A message from the system SMS/MMS store. [key] is stable: "s<id>" for SMS, "m<id>" for MMS. */
data class Message(
    val key: String,
    val id: Long,
    val kind: MessageKind,
    val threadId: Long,
    /** Sender for incoming messages; null for outgoing. */
    val sender: String?,
    /** Recipients for outgoing messages (SMS: one; MMS: all To/Cc). */
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
    /** MMS notification that has not been downloaded yet. */
    val mmsPending: Boolean = false,
    val mmsContentLocation: String? = null,
    val mmsExpiry: Long = 0,
    val sizeBytes: Long = 0,
    val errorCode: Int = 0,
) {
    val hasText get() = body.isNotBlank()

    companion object {
        fun smsKey(id: Long) = "s$id"
        fun mmsKey(id: Long) = "m$id"
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
) {
    val isGroup get() = recipients.size > 1
}

/** Media chosen in the compose bar, before it is encoded into an MMS part. */
data class OutgoingAttachment(
    val uri: Uri,
    val mimeType: String,
    val name: String? = null,
)
