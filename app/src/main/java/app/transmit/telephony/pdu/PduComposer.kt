package app.transmit.telephony.pdu

import java.io.ByteArrayOutputStream

/** Encodes the outgoing PDUs: M-Send.req (new messages) and M-NotifyResp.ind (download acks). */
object PduComposer {

    fun sendRequest(
        transactionId: String,
        recipients: List<String>,
        parts: List<MmsPart>,
        dateSeconds: Long,
        subject: String? = null,
        expirySeconds: Long = 7 * 24 * 3600L,
    ): ByteArray {
        require(recipients.isNotEmpty()) { "MMS needs at least one recipient" }
        val out = PduWriter()
        out.header(MmsHeaders.MESSAGE_TYPE); out.byte(MmsMessageType.SEND_REQ)
        out.header(MmsHeaders.TRANSACTION_ID); out.text(transactionId)
        out.header(MmsHeaders.MMS_VERSION); out.byte(0x80 or MmsValues.VERSION_1_2)
        out.header(MmsHeaders.DATE); out.longInteger(dateSeconds)
        // From: Insert-address-token, the MMSC fills in our number.
        out.header(MmsHeaders.FROM); out.byte(1); out.byte(0x81)
        recipients.forEach {
            out.header(MmsHeaders.TO)
            out.encodedString(PduText.formatAddress(it))
        }
        if (!subject.isNullOrBlank()) {
            out.header(MmsHeaders.SUBJECT); out.encodedString(subject)
        }
        out.header(MmsHeaders.MESSAGE_CLASS); out.byte(MmsValues.CLASS_PERSONAL)
        out.header(MmsHeaders.EXPIRY)
        PduWriter().apply { byte(0x81); longInteger(expirySeconds) }.bytes().let {
            out.valueLength(it.size); out.raw(it)
        }
        out.header(MmsHeaders.PRIORITY); out.byte(MmsValues.PRIORITY_NORMAL)
        out.header(MmsHeaders.DELIVERY_REPORT); out.byte(MmsValues.NO)
        out.header(MmsHeaders.READ_REPORT); out.byte(MmsValues.NO)

        // Content-Type: multipart/related; start=<smil>; type=application/smil
        val smil = parts.firstOrNull { it.isSmil }
        out.header(MmsHeaders.CONTENT_TYPE)
        val ct = PduWriter().apply {
            if (smil != null) {
                byte(0x80 or WELL_KNOWN_CONTENT_TYPES.indexOf(CT_MULTIPART_RELATED))
                byte(0x8A); text(smil.contentId ?: "<smil>")
                byte(0x89); text("application/smil")
            } else {
                byte(0x80 or WELL_KNOWN_CONTENT_TYPES.indexOf(CT_MULTIPART_MIXED))
            }
        }.bytes()
        out.valueLength(ct.size); out.raw(ct)

        // Body: SMIL first so receivers that require it as the start part find it.
        val ordered = listOfNotNull(smil) + parts.filter { it !== smil }
        out.uintvar(ordered.size.toLong())
        ordered.forEach { part -> writePart(out, part) }
        return out.bytes()
    }

    fun notifyResponse(transactionId: String, status: Int = MmsValues.STATUS_RETRIEVED): ByteArray {
        val out = PduWriter()
        out.header(MmsHeaders.MESSAGE_TYPE); out.byte(MmsMessageType.NOTIFYRESP_IND)
        out.header(MmsHeaders.TRANSACTION_ID); out.text(transactionId)
        out.header(MmsHeaders.MMS_VERSION); out.byte(0x80 or MmsValues.VERSION_1_2)
        out.header(MmsHeaders.STATUS); out.byte(status)
        out.header(MmsHeaders.REPORT_ALLOWED); out.byte(MmsValues.NO)
        return out.bytes()
    }

    private fun writePart(out: PduWriter, part: MmsPart) {
        val headers = PduWriter()
        val ct = PduWriter().apply {
            text(part.contentType)
            part.charset?.let { byte(0x81); integerValue(it.toLong()) }
            part.name?.let { byte(0x85); text(it) }
        }.bytes()
        headers.valueLength(ct.size); headers.raw(ct)
        part.contentId?.let { headers.byte(0xC0); headers.quotedString(it) }
        part.contentLocation?.let { headers.byte(0x8E); headers.text(it) }
        val h = headers.bytes()
        out.uintvar(h.size.toLong())
        out.uintvar(part.data.size.toLong())
        out.raw(h)
        out.raw(part.data)
    }
}

internal class PduWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()
    fun byte(b: Int) = out.write(b and 0xFF)
    fun raw(b: ByteArray) = out.write(b)
    fun header(field: Int) = byte(0x80 or field)

    fun text(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) >= 0x80) byte(0x7F)
        raw(bytes); byte(0)
    }

    fun quotedString(s: String) {
        byte(0x22); raw(s.toByteArray(Charsets.UTF_8)); byte(0)
    }

    fun encodedString(s: String) {
        if (s.all { it.code < 0x80 }) {
            text(s)
            return
        }
        val body = PduWriter().apply { byte(0x80 or MmsValues.CHARSET_UTF8); text(s) }.bytes()
        valueLength(body.size); raw(body)
    }

    fun uintvar(value: Long) {
        var v = value
        val stack = ArrayList<Int>()
        stack += (v and 0x7F).toInt()
        v = v shr 7
        while (v > 0) {
            stack += ((v and 0x7F) or 0x80).toInt()
            v = v shr 7
        }
        for (i in stack.indices.reversed()) byte(stack[i])
    }

    fun valueLength(len: Int) {
        if (len < 31) byte(len) else { byte(31); uintvar(len.toLong()) }
    }

    fun longInteger(value: Long) {
        var v = value
        val bytes = ArrayList<Int>()
        do {
            bytes += (v and 0xFF).toInt()
            v = v ushr 8
        } while (v != 0L)
        byte(bytes.size)
        for (i in bytes.indices.reversed()) byte(bytes[i])
    }

    fun integerValue(value: Long) {
        if (value in 0..127) byte(0x80 or value.toInt()) else longInteger(value)
    }
}
