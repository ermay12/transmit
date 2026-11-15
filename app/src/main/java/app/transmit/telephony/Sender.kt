package app.transmit.telephony

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.content.FileProvider
import app.transmit.telephony.pdu.MmsPart
import app.transmit.telephony.pdu.MmsValues
import app.transmit.telephony.pdu.PduComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Sends SMS (single recipient, text only) and MMS (groups, media) and records them in the store. */
class Sender(
    private val context: Context,
    private val repo: MessagingRepository,
    private val subs: Subscriptions,
    private val mmsStore: MmsStore,
    private val encoder: MediaEncoder,
    private val deliveryReports: suspend () -> Boolean,
) {
    data class Result(val threadId: Long, val key: String)

    private val resolver get() = context.contentResolver

    suspend fun send(
        recipients: List<String>,
        text: String,
        attachments: List<OutgoingAttachment> = emptyList(),
        subId: Int = subs.defaultSmsSubId(),
    ): Result = withContext(Dispatchers.IO) {
        require(recipients.isNotEmpty()) { "No recipients" }
        require(text.isNotBlank() || attachments.isNotEmpty()) { "Nothing to send" }
        val threadId = repo.threadIdFor(recipients)
        val result = if (recipients.size == 1 && attachments.isEmpty()) {
            sendSms(threadId, recipients.single(), text.trim(), subId)
        } else {
            sendMms(threadId, recipients, text.trim(), attachments, subId)
        }
        repo.invalidate(threadId)
        result
    }

    /** Re-sends a failed message in place (SMS) or as a fresh copy (MMS). */
    suspend fun retry(message: Message): Result? = withContext(Dispatchers.IO) {
        when (message.kind) {
            MessageKind.SMS -> {
                val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, message.id)
                val values = ContentValues().apply {
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.ERROR_CODE, 0)
                    put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
                }
                resolver.update(uri, values, null, null)
                dispatchSms(uri, message.recipients.firstOrNull() ?: return@withContext null, message.body, message.subId)
                Result(message.threadId, message.key)
            }
            MessageKind.MMS -> {
                val attachments = message.attachments.map { OutgoingAttachment(it.uri, it.mimeType, it.name) }
                val recipients = message.recipients.ifEmpty { repo.recipientsForThread(message.threadId) }
                val result = sendMms(message.threadId, recipients, message.body, attachments, message.subId)
                repo.deleteMessage(message)
                result
            }
        }
    }

    private suspend fun sendSms(threadId: Long, address: String, text: String, subId: Int): Result {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, text)
            put(Telephony.Sms.DATE, now)
            put(Telephony.Sms.DATE_SENT, now)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.THREAD_ID, threadId)
            put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_NONE)
        }
        val uri = resolver.insert(Telephony.Sms.CONTENT_URI, values) ?: error("Could not store the message")
        dispatchSms(uri, address, text, subId)
        return Result(threadId, Message.smsKey(ContentUris.parseId(uri)))
    }

    private suspend fun dispatchSms(uri: Uri, address: String, text: String, subId: Int) {
        val sms = subs.smsManager(subId)
        val wantDelivery = deliveryReports()
        try {
            val parts = sms.divideMessage(text)
            val sent = ArrayList<PendingIntent>()
            val delivered = if (wantDelivery) ArrayList<PendingIntent>() else null
            parts.indices.forEach { i ->
                sent += statusIntent(SmsStatusReceiver.ACTION_SENT, uri, i, parts.size)
                delivered?.add(statusIntent(SmsStatusReceiver.ACTION_DELIVERED, uri, i, parts.size))
            }
            sms.sendMultipartTextMessage(address, null, parts, sent, delivered)
        } catch (e: Exception) {
            Log.e(TAG, "SMS dispatch failed", e)
            val values = ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }
            resolver.update(uri, values, null, null)
        }
    }

    private fun statusIntent(action: String, uri: Uri, part: Int, count: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(uri.buildUpon().fragment(part.toString()).build())
            .putExtra(SmsStatusReceiver.EXTRA_PART, part)
            .putExtra(SmsStatusReceiver.EXTRA_COUNT, count)
        return PendingIntent.getBroadcast(
            context, requestCodes.incrementAndGet(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun sendMms(threadId: Long, recipients: List<String>, text: String, attachments: List<OutgoingAttachment>, subId: Int): Result {
        val limit = subs.mmsMaxBytes(subId)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val perItem = if (attachments.isEmpty()) 0 else ((limit - 6_000 - textBytes.size) / attachments.size).coerceAtLeast(40_000)
        val parts = mutableListOf<MmsPart>()
        attachments.forEachIndexed { i, a ->
            val e = encoder.encode(a, perItem, i)
            parts += MmsPart(e.mimeType, e.bytes, name = e.name, contentId = "<${e.name}>", contentLocation = e.name)
        }
        if (text.isNotBlank()) {
            parts += MmsPart("text/plain", textBytes, name = "text_0.txt", contentId = "<text_0>", contentLocation = "text_0.txt", charset = MmsValues.CHARSET_UTF8)
        }
        parts.add(0, smil(parts))

        val transactionId = "T" + java.lang.Long.toHexString(System.currentTimeMillis()) + Random.nextInt(0x1000, 0xFFFF).toString(16)
        val now = System.currentTimeMillis() / 1000
        val pdu = PduComposer.sendRequest(transactionId, recipients, parts, now)
        val uri = mmsStore.insertOutgoing(threadId, recipients, parts, transactionId, subId, now, pdu.size.toLong())
        dispatchMms(uri, pdu, subId)
        return Result(threadId, Message.mmsKey(ContentUris.parseId(uri)))
    }

    private fun dispatchMms(uri: Uri, pdu: ByteArray, subId: Int) {
        try {
            val file = File(context.cacheDir, "mms/send-${ContentUris.parseId(uri)}-${System.nanoTime()}.pdu")
            file.parentFile?.mkdirs()
            file.writeBytes(pdu)
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            grantToMmsService(context, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val intent = Intent(context, MmsStatusReceiver::class.java)
                .setAction(MmsStatusReceiver.ACTION_SENT)
                .setData(uri)
                .putExtra(MmsStatusReceiver.EXTRA_FILE, file.absolutePath)
            val pi = PendingIntent.getBroadcast(context, requestCodes.incrementAndGet(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            subs.smsManager(subId).sendMultimediaMessage(context, contentUri, null, null, pi)
        } catch (e: Exception) {
            Log.e(TAG, "MMS dispatch failed", e)
            mmsStore.setBox(uri, Telephony.Mms.MESSAGE_BOX_FAILED)
        }
    }

    private fun smil(parts: List<MmsPart>): MmsPart {
        val body = StringBuilder()
        parts.forEach { p ->
            val (tag, region) = when {
                p.contentType.startsWith("image/") -> "img" to "Image"
                p.contentType.startsWith("video/") -> "video" to "Image"
                p.contentType.startsWith("audio/") -> "audio" to "Image"
                p.isText -> "text" to "Text"
                else -> "ref" to "Image"
            }
            body.append("<par dur=\"5000ms\"><$tag src=\"${p.contentLocation}\" region=\"$region\"/></par>")
        }
        val smil = "<smil><head><layout><root-layout/>" +
            "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>" +
            "<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>" +
            "</layout></head><body>$body</body></smil>"
        return MmsPart("application/smil", smil.toByteArray(), name = "smil.xml", contentId = "<smil>", contentLocation = "smil.xml", charset = MmsValues.CHARSET_UTF8)
    }

    companion object {
        private const val TAG = "Sender"
        private val requestCodes = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())

        /** The platform MMS service runs in one of these packages depending on the OEM. */
        fun grantToMmsService(context: Context, uri: Uri, flags: Int) {
            listOf("com.android.phone", "com.android.mms.service").forEach { pkg ->
                runCatching { context.grantUriPermission(pkg, uri, flags) }
            }
        }

        fun nextRequestCode() = requestCodes.incrementAndGet()
    }
}
