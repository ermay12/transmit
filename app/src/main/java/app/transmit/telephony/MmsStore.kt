package app.transmit.telephony

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import app.transmit.telephony.pdu.MmsAddrType
import app.transmit.telephony.pdu.MmsPart
import app.transmit.telephony.pdu.MmsPdu
import app.transmit.telephony.pdu.MmsValues

/** Writes MMS rows (pdu, parts, addr) into the platform MMS store, mirroring AOSP PduPersister. */
class MmsStore(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun insertOutgoing(
        threadId: Long,
        recipients: List<String>,
        parts: List<MmsPart>,
        transactionId: String,
        subId: Int,
        dateSeconds: Long,
        sizeBytes: Long,
    ): Uri {
        val values = baseValues(threadId, dateSeconds, subId).apply {
            put(Telephony.Mms.DATE_SENT, dateSeconds)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, 0x80)
            put(Telephony.Mms.TRANSACTION_ID, transactionId)
            put(Telephony.Mms.MESSAGE_SIZE, sizeBytes)
            put(Telephony.Mms.TEXT_ONLY, if (parts.all { it.isText || it.isSmil }) 1 else 0)
        }
        val uri = resolver.insert(Telephony.Mms.CONTENT_URI, values) ?: error("MMS insert failed")
        val id = ContentUris.parseId(uri)
        insertParts(id, parts)
        insertAddress(id, "insert-address-token", MmsAddrType.FROM)
        recipients.forEach { insertAddress(id, it, MmsAddrType.TO) }
        return uri
    }

    /** An undownloaded M-Notification.ind, shown as "downloading" until the content arrives. */
    fun insertNotification(pdu: MmsPdu, threadId: Long, subId: Int): Uri {
        val now = System.currentTimeMillis() / 1000
        val values = baseValues(threadId, now, subId).apply {
            put(Telephony.Mms.DATE_SENT, pdu.date ?: now)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, 0x82)
            put(Telephony.Mms.TRANSACTION_ID, pdu.transactionId)
            put(Telephony.Mms.CONTENT_LOCATION, pdu.contentLocation)
            put(Telephony.Mms.MESSAGE_SIZE, pdu.messageSize ?: 0)
            val exp = pdu.expiry ?: 0
            put(Telephony.Mms.EXPIRY, if (pdu.expiryRelative) now + exp else exp)
            pdu.subject?.let { putSubject(this, it) }
        }
        val uri = resolver.insert(Telephony.Mms.CONTENT_URI, values) ?: error("MMS insert failed")
        pdu.from?.let { insertAddress(ContentUris.parseId(uri), it, MmsAddrType.FROM) }
        return uri
    }

    fun insertRetrieved(pdu: MmsPdu, threadId: Long, subId: Int, receivedSeconds: Long): Uri {
        val values = baseValues(threadId, receivedSeconds, subId).apply {
            put(Telephony.Mms.DATE_SENT, pdu.date ?: receivedSeconds)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, 0x84)
            put(Telephony.Mms.TRANSACTION_ID, pdu.transactionId)
            put(Telephony.Mms.MESSAGE_ID, pdu.messageId)
            put(Telephony.Mms.MESSAGE_SIZE, pdu.parts.sumOf { it.data.size }.toLong())
            put(Telephony.Mms.TEXT_ONLY, if (pdu.parts.all { it.isText || it.isSmil }) 1 else 0)
            pdu.subject?.let { putSubject(this, it) }
        }
        val uri = resolver.insert(Telephony.Mms.CONTENT_URI, values) ?: error("MMS insert failed")
        val id = ContentUris.parseId(uri)
        insertParts(id, pdu.parts)
        pdu.from?.let { insertAddress(id, it, MmsAddrType.FROM) }
        pdu.to.forEach { insertAddress(id, it, MmsAddrType.TO) }
        pdu.cc.forEach { insertAddress(id, it, MmsAddrType.CC) }
        return uri
    }

    fun setBox(uri: Uri, box: Int, messageId: String? = null, responseStatus: Int? = null) {
        val values = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, box)
            messageId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
            responseStatus?.let { put(Telephony.Mms.RESPONSE_STATUS, it) }
        }
        runCatching { resolver.update(uri, values, null, null) }
    }

    fun setDownloadFailed(uri: Uri) {
        val values = ContentValues().apply { put(Telephony.Mms.STATUS, 0x87) } // X-Mms-Status: unrecognised/failed
        runCatching { resolver.update(uri, values, null, null) }
    }

    fun delete(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /** Finds an existing notification row for the same transaction, to drop duplicate WAP pushes. */
    fun findByLocation(contentLocation: String?): Uri? {
        if (contentLocation.isNullOrBlank()) return null
        resolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
            "${Telephony.Mms.CONTENT_LOCATION}=?", arrayOf(contentLocation), null,
        )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, c.getLong(0)) }
        return null
    }

    fun threadIdOf(uri: Uri): Long {
        resolver.query(uri, arrayOf(Telephony.Mms.THREAD_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 0
    }

    private fun baseValues(threadId: Long, dateSeconds: Long, subId: Int) = ContentValues().apply {
        put(Telephony.Mms.THREAD_ID, threadId)
        put(Telephony.Mms.DATE, dateSeconds)
        put(Telephony.Mms.MMS_VERSION, MmsValues.VERSION_1_2)
        put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
        put(Telephony.Mms.MESSAGE_CLASS, "personal")
        put(Telephony.Mms.PRIORITY, 129)
        put(Telephony.Mms.READ_REPORT, 129)
        put(Telephony.Mms.DELIVERY_REPORT, 129)
        put(Telephony.Mms.SUBSCRIPTION_ID, subId)
    }

    private fun putSubject(values: ContentValues, subject: String) {
        // PduPersister stores encoded strings as ISO-8859-1 views of their raw bytes.
        values.put(Telephony.Mms.SUBJECT, String(subject.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1))
        values.put(Telephony.Mms.SUBJECT_CHARSET, MmsValues.CHARSET_UTF8)
    }

    private fun insertParts(messageId: Long, parts: List<MmsPart>) {
        val partsUri = Uri.parse("content://mms/$messageId/part")
        parts.forEachIndexed { index, part ->
            val values = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, messageId)
                put(Telephony.Mms.Part.SEQ, if (part.isSmil) -1 else index)
                put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
                part.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, it) }
                part.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
                part.name?.let { put(Telephony.Mms.Part.NAME, it) }
                if (part.isText || part.isSmil) {
                    put(Telephony.Mms.Part.CHARSET, MmsValues.CHARSET_UTF8)
                    put(Telephony.Mms.Part.TEXT, part.text())
                }
            }
            val partUri = resolver.insert(partsUri, values) ?: return@forEachIndexed
            if (!part.isText && !part.isSmil) {
                resolver.openOutputStream(partUri)?.use { it.write(part.data) }
            }
        }
    }

    private fun insertAddress(messageId: Long, address: String, type: Int) {
        val values = ContentValues().apply {
            put(Telephony.Mms.Addr.ADDRESS, address)
            put(Telephony.Mms.Addr.TYPE, type)
            put(Telephony.Mms.Addr.CHARSET, MmsValues.CHARSET_UTF8)
        }
        resolver.insert(Uri.parse("content://mms/$messageId/addr"), values)
    }
}
