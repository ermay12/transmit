package app.transmit.telephony.pdu

import java.io.ByteArrayOutputStream

class PduParseException(message: String) : Exception(message)

/**
 * Decodes M-Notification.ind, M-Retrieve.conf, M-Send.conf and friends. Unknown header fields are
 * skipped with the generic WSP value rules so carrier-specific extensions do not break parsing.
 */
class PduParser(private val data: ByteArray) {
    private var pos = 0

    fun parse(): MmsPdu {
        var pdu = MmsPdu(messageType = -1)
        val to = mutableListOf<String>()
        val cc = mutableListOf<String>()
        val bcc = mutableListOf<String>()

        while (pos < data.size) {
            val b = peek()
            if (b < 0x80) {
                // Application header: Token-text Application-specific-value.
                readText()
                readText()
                continue
            }
            pos++
            when (b and 0x7F) {
                MmsHeaders.MESSAGE_TYPE -> pdu = pdu.copy(messageType = readByte())
                MmsHeaders.TRANSACTION_ID -> pdu = pdu.copy(transactionId = readText())
                MmsHeaders.MESSAGE_ID -> pdu = pdu.copy(messageId = readText())
                MmsHeaders.MMS_VERSION -> pdu = pdu.copy(version = readByte() and 0x7F)
                MmsHeaders.DATE -> pdu = pdu.copy(date = readLongInteger())
                MmsHeaders.MESSAGE_SIZE -> pdu = pdu.copy(messageSize = readLongInteger())
                MmsHeaders.FROM -> pdu = pdu.copy(from = readFrom())
                MmsHeaders.TO -> to += PduText.stripAddressType(readEncodedString())
                MmsHeaders.CC -> cc += PduText.stripAddressType(readEncodedString())
                MmsHeaders.BCC -> bcc += PduText.stripAddressType(readEncodedString())
                MmsHeaders.SUBJECT -> pdu = pdu.copy(subject = readEncodedString())
                MmsHeaders.RESPONSE_TEXT -> pdu = pdu.copy(responseText = readEncodedString())
                MmsHeaders.RETRIEVE_TEXT -> pdu = pdu.copy(retrieveText = readEncodedString())
                MmsHeaders.CONTENT_LOCATION -> pdu = pdu.copy(contentLocation = readText())
                MmsHeaders.EXPIRY, MmsHeaders.DELIVERY_TIME -> {
                    val (value, relative) = readExpiry()
                    if ((b and 0x7F) == MmsHeaders.EXPIRY) pdu = pdu.copy(expiry = value, expiryRelative = relative)
                }
                MmsHeaders.MESSAGE_CLASS -> pdu = pdu.copy(messageClass = readMessageClass())
                MmsHeaders.PRIORITY -> pdu = pdu.copy(priority = readByte())
                MmsHeaders.RESPONSE_STATUS -> pdu = pdu.copy(responseStatus = readByte())
                MmsHeaders.RETRIEVE_STATUS -> pdu = pdu.copy(retrieveStatus = readByte())
                MmsHeaders.STATUS -> pdu = pdu.copy(status = readByte())
                MmsHeaders.DELIVERY_REPORT -> pdu = pdu.copy(deliveryReport = readByte())
                MmsHeaders.READ_REPORT -> pdu = pdu.copy(readReport = readByte())
                MmsHeaders.CONTENT_TYPE -> {
                    val ct = readContentType()
                    pdu = pdu.copy(contentType = ct)
                    // Content-Type is always the last header; the body follows.
                    val parts = if (ct.mediaType.startsWith("application/vnd.wap.multipart", true) ||
                        ct.mediaType.startsWith("multipart/", true)
                    ) {
                        readMultipart()
                    } else {
                        listOf(MmsPart(ct.mediaType, data.copyOfRange(pos, data.size), charset = ct.params["charset"]?.toIntOrNull()))
                    }
                    pos = data.size
                    pdu = pdu.copy(parts = parts)
                }
                else -> skipValue()
            }
        }
        if (pdu.messageType == -1) throw PduParseException("Missing X-Mms-Message-Type")
        return pdu.copy(to = to, cc = cc, bcc = bcc)
    }

    // ---- primitive readers -------------------------------------------------------------------

    private fun peek(): Int {
        if (pos >= data.size) throw PduParseException("Unexpected end of PDU at $pos")
        return data[pos].toInt() and 0xFF
    }

    private fun readByte(): Int {
        val b = peek()
        pos++
        return b
    }

    private fun readUintvar(): Long {
        var result = 0L
        var count = 0
        while (true) {
            val b = readByte()
            result = (result shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) break
            if (++count > 5) throw PduParseException("uintvar too long")
        }
        return result
    }

    /** Value-length: Short-length (0..30) or 31 followed by a uintvar. */
    private fun readValueLength(): Int {
        val b = readByte()
        return when {
            b < 31 -> b
            b == 31 -> readUintvar().toInt()
            else -> throw PduParseException("Bad value-length $b")
        }
    }

    private fun readLongInteger(): Long {
        val len = readByte()
        if (len > 8) throw PduParseException("Long-integer too long: $len")
        var v = 0L
        repeat(len) { v = (v shl 8) or readByte().toLong() }
        return v
    }

    /** Integer-value: Short-integer or Long-integer. */
    private fun readIntegerValue(): Long {
        val b = peek()
        return if (b >= 0x80) (readByte() and 0x7F).toLong() else readLongInteger()
    }

    private fun readTextBytes(): ByteArray {
        if (peek() == 0x7F || peek() == 0x22) pos++ // Quote / quoted-string marker
        val out = ByteArrayOutputStream()
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            if (b == 0) break
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun readText(): String = String(readTextBytes(), Charsets.UTF_8)

    private fun readEncodedString(): String {
        val b = peek()
        if (b > 31) return readText()
        val len = readValueLength()
        if (len == 0) return ""
        val end = pos + len
        val charset = readIntegerValue().toInt()
        val bytes = readTextBytes()
        pos = end
        return PduText.decode(bytes, charset)
    }

    private fun readFrom(): String? {
        val len = readValueLength()
        val end = pos + len
        val token = readByte()
        val address = if (token == 0x80) PduText.stripAddressType(readEncodedString()) else null
        pos = end
        return address
    }

    private fun readExpiry(): Pair<Long, Boolean> {
        val len = readValueLength()
        val end = pos + len
        val token = readByte()
        val value = readLongInteger()
        pos = end
        return value to (token == 0x81)
    }

    private fun readMessageClass(): String {
        val b = peek()
        if (b >= 0x80) {
            pos++
            return when (b) {
                0x80 -> "personal"
                0x81 -> "advertisement"
                0x82 -> "informational"
                0x83 -> "auto"
                else -> "personal"
            }
        }
        return readText()
    }

    /** Skips one header value using the generic WSP encoding rules. */
    private fun skipValue() {
        val b = peek()
        when {
            b < 31 -> pos += 1 + b
            b == 31 -> { pos++; val len = readUintvar(); pos += len.toInt() }
            b < 128 -> readTextBytes()
            else -> pos++
        }
    }

    // ---- content type & multipart ------------------------------------------------------------

    private fun readMediaType(end: Int): String {
        val b = peek()
        return when {
            b >= 0x80 -> wellKnown(readByte() and 0x7F)
            b < 31 -> wellKnown(readLongInteger().toInt())
            else -> readText()
        }.also { if (pos > end) throw PduParseException("Media type overflow") }
    }

    private fun wellKnown(index: Int): String =
        WELL_KNOWN_CONTENT_TYPES.getOrNull(index) ?: "application/octet-stream"

    internal fun readContentType(): ContentType {
        val b = peek()
        if (b >= 0x80) return ContentType(wellKnown(readByte() and 0x7F))
        if (b > 31) return ContentType(readText())
        val len = readValueLength()
        val end = pos + len
        val media = readMediaType(end)
        val params = mutableMapOf<String, String>()
        while (pos < end) {
            val p = peek()
            if (p < 0x80) {
                // Untyped parameter: Token-text Untyped-value.
                val name = readText().lowercase()
                val v = peek()
                params[name] = if (v >= 0x80 || v < 31) readIntegerValue().toString() else readText()
                continue
            }
            pos++
            when (p) {
                0x81 -> { // Charset
                    params["charset"] = if (peek() == 0x80) { pos++; "0" } else readIntegerValue().toString()
                }
                0x83, 0x89 -> { // Type / multipart-related type
                    val v = peek()
                    params["type"] = if (v >= 0x80) wellKnown(readByte() and 0x7F) else readText()
                }
                0x85, 0x97 -> params["name"] = readTextValue()
                0x86, 0x98 -> params["filename"] = readTextValue()
                0x8A, 0x99 -> params["start"] = readTextValue()
                0x8B, 0x9A -> params["start-info"] = readTextValue()
                else -> skipValue()
            }
        }
        pos = end
        return ContentType(media, params)
    }

    private fun readTextValue(): String {
        if (peek() == 0) { pos++; return "" }
        return readText()
    }

    private fun readMultipart(): List<MmsPart> {
        val count = readUintvar().toInt()
        val parts = ArrayList<MmsPart>(count)
        repeat(count) {
            val headersLen = readUintvar().toInt()
            val dataLen = readUintvar().toInt()
            val headersStart = pos
            val headersEnd = headersStart + headersLen
            var ct = ContentType("application/octet-stream")
            var contentId: String? = null
            var contentLocation: String? = null
            var dispositionName: String? = null
            try {
                ct = readContentType()
                while (pos < headersEnd) {
                    val h = peek()
                    if (h < 0x80) {
                        readText(); readText()
                        continue
                    }
                    pos++
                    when (h) {
                        0x8E -> contentLocation = readText()
                        0xC0 -> contentId = readText()
                        0xAE, 0xC5 -> dispositionName = readDisposition()
                        else -> skipValue()
                    }
                }
            } catch (e: PduParseException) {
                // Malformed part headers: keep the data, fall back to defaults.
            }
            pos = headersEnd
            if (pos + dataLen > data.size) throw PduParseException("Part data overflow")
            val bytes = data.copyOfRange(pos, pos + dataLen)
            pos += dataLen
            parts += MmsPart(
                contentType = ct.mediaType,
                data = bytes,
                name = ct.params["name"] ?: ct.params["filename"] ?: dispositionName,
                contentId = contentId,
                contentLocation = contentLocation,
                charset = ct.params["charset"]?.toIntOrNull(),
            )
        }
        return parts
    }

    private fun readDisposition(): String? {
        val len = readValueLength()
        val end = pos + len
        var filename: String? = null
        if (pos < end) {
            val v = peek()
            if (v >= 0x80) pos++ else readText()
            while (pos < end) {
                val p = peek()
                if (p == 0x86 || p == 0x98 || p == 0x85 || p == 0x97) {
                    pos++
                    filename = readTextValue()
                } else if (p >= 0x80) {
                    pos++
                    skipValue()
                } else {
                    readText(); readText()
                }
            }
        }
        pos = end
        return filename
    }

    companion object {
        fun parse(bytes: ByteArray): MmsPdu = PduParser(bytes).parse()
    }
}
