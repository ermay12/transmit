package app.transmit.telephony.pdu

/**
 * Minimal model of the MMS PDUs a default SMS app needs to exchange with the platform
 * MmsService (OMA-MMS-ENC 1.2 / WAP-230 WSP encoding).
 */
object MmsHeaders {
    const val BCC = 0x01
    const val CC = 0x02
    const val CONTENT_LOCATION = 0x03
    const val CONTENT_TYPE = 0x04
    const val DATE = 0x05
    const val DELIVERY_REPORT = 0x06
    const val DELIVERY_TIME = 0x07
    const val EXPIRY = 0x08
    const val FROM = 0x09
    const val MESSAGE_CLASS = 0x0A
    const val MESSAGE_ID = 0x0B
    const val MESSAGE_TYPE = 0x0C
    const val MMS_VERSION = 0x0D
    const val MESSAGE_SIZE = 0x0E
    const val PRIORITY = 0x0F
    const val READ_REPORT = 0x10
    const val REPORT_ALLOWED = 0x11
    const val RESPONSE_STATUS = 0x12
    const val RESPONSE_TEXT = 0x13
    const val SENDER_VISIBILITY = 0x14
    const val STATUS = 0x15
    const val SUBJECT = 0x16
    const val TO = 0x17
    const val TRANSACTION_ID = 0x18
    const val RETRIEVE_STATUS = 0x19
    const val RETRIEVE_TEXT = 0x1A
    const val READ_STATUS = 0x1B
}

object MmsMessageType {
    const val SEND_REQ = 0x80
    const val SEND_CONF = 0x81
    const val NOTIFICATION_IND = 0x82
    const val NOTIFYRESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84
    const val ACKNOWLEDGE_IND = 0x85
    const val DELIVERY_IND = 0x86
    const val READ_REC_IND = 0x87
    const val READ_ORIG_IND = 0x88
}

object MmsValues {
    const val VERSION_1_2 = 0x12
    const val YES = 0x80
    const val NO = 0x81
    const val PRIORITY_NORMAL = 0x81
    const val CLASS_PERSONAL = 0x80
    const val STATUS_RETRIEVED = 0x81
    const val RESPONSE_STATUS_OK = 0x80
    const val RETRIEVE_STATUS_OK = 0x80
    const val CHARSET_UTF8 = 106
    const val CHARSET_US_ASCII = 3
    const val CHARSET_ISO_8859_1 = 4
    const val CHARSET_UCS2 = 1000
    const val CHARSET_UTF16 = 1015
}

/** Address header values as they appear in the MMS provider (`addr` table types). */
object MmsAddrType {
    const val BCC = 129
    const val CC = 130
    const val FROM = 137
    const val TO = 151
}

data class MmsPart(
    val contentType: String,
    val data: ByteArray,
    val name: String? = null,
    val contentId: String? = null,
    val contentLocation: String? = null,
    val charset: Int? = null,
) {
    val isText get() = contentType.equals("text/plain", ignoreCase = true)
    val isSmil get() = contentType.equals("application/smil", ignoreCase = true)

    fun text(): String = PduText.decode(data, charset ?: MmsValues.CHARSET_UTF8)

    override fun equals(other: Any?): Boolean =
        other is MmsPart && contentType == other.contentType && data.contentEquals(other.data) &&
            name == other.name && contentId == other.contentId && contentLocation == other.contentLocation &&
            charset == other.charset

    override fun hashCode(): Int = 31 * contentType.hashCode() + data.contentHashCode()
}

data class ContentType(
    val mediaType: String,
    val params: Map<String, String> = emptyMap(),
)

/** Every header a parsed PDU carried, decoded into the shapes the app consumes. */
data class MmsPdu(
    val messageType: Int,
    val transactionId: String? = null,
    val messageId: String? = null,
    val version: Int? = null,
    val date: Long? = null, // seconds
    val from: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String? = null,
    val contentLocation: String? = null,
    val messageSize: Long? = null,
    val expiry: Long? = null, // seconds; absolute when [expiryRelative] is false
    val expiryRelative: Boolean = false,
    val messageClass: String? = null,
    val priority: Int? = null,
    val responseStatus: Int? = null,
    val responseText: String? = null,
    val retrieveStatus: Int? = null,
    val retrieveText: String? = null,
    val status: Int? = null,
    val deliveryReport: Int? = null,
    val readReport: Int? = null,
    val contentType: ContentType? = null,
    val parts: List<MmsPart> = emptyList(),
)

internal object PduText {
    fun decode(bytes: ByteArray, charset: Int): String {
        val cs = when (charset) {
            MmsValues.CHARSET_UTF8, 0 -> Charsets.UTF_8
            MmsValues.CHARSET_US_ASCII -> Charsets.US_ASCII
            MmsValues.CHARSET_ISO_8859_1 -> Charsets.ISO_8859_1
            MmsValues.CHARSET_UCS2 -> Charsets.UTF_16BE
            MmsValues.CHARSET_UTF16 -> Charsets.UTF_16
            else -> Charsets.UTF_8
        }
        return String(bytes, cs)
    }

    /** "+15551234567/TYPE=PLMN" -> "+15551234567". */
    fun stripAddressType(address: String): String {
        val idx = address.indexOf("/TYPE=", ignoreCase = true)
        return (if (idx >= 0) address.substring(0, idx) else address).trim()
    }

    fun formatAddress(address: String): String {
        if (address.contains('@')) return address
        val dialable = address.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        return "$dialable/TYPE=PLMN"
    }
}

/** WSP well-known content types (WAP-230 Table 40), index == assigned number. */
internal val WELL_KNOWN_CONTENT_TYPES = arrayOf(
    "*/*", "text/*", "text/html", "text/plain", "text/x-hdml", "text/x-ttml", "text/x-vCalendar",
    "text/x-vCard", "text/vnd.wap.wml", "text/vnd.wap.wmlscript", "text/vnd.wap.wta-event",
    "multipart/*", "multipart/mixed", "multipart/form-data", "multipart/byterantes",
    "multipart/alternative", "application/*", "application/java-vm",
    "application/x-www-form-urlencoded", "application/x-hdmlc", "application/vnd.wap.wmlc",
    "application/vnd.wap.wmlscriptc", "application/vnd.wap.wta-eventc", "application/vnd.wap.uaprof",
    "application/vnd.wap.wtls-ca-certificate", "application/vnd.wap.wtls-user-certificate",
    "application/x-x509-ca-cert", "application/x-x509-user-cert", "image/*", "image/gif",
    "image/jpeg", "image/tiff", "image/png", "image/vnd.wap.wbmp",
    "application/vnd.wap.multipart.*", "application/vnd.wap.multipart.mixed",
    "application/vnd.wap.multipart.form-data", "application/vnd.wap.multipart.byteranges",
    "application/vnd.wap.multipart.alternative", "application/xml", "text/xml",
    "application/vnd.wap.wbxml", "application/x-x968-cross-cert", "application/x-x968-ca-cert",
    "application/x-x968-user-cert", "text/vnd.wap.si", "application/vnd.wap.sic", "text/vnd.wap.sl",
    "application/vnd.wap.slc", "text/vnd.wap.co", "application/vnd.wap.coc",
    "application/vnd.wap.multipart.related", "application/vnd.wap.sia", "text/vnd.wap.connectivity-xml",
    "application/vnd.wap.connectivity-wbxml", "application/pkcs7-mime",
    "application/vnd.wap.hashed-certificate", "application/vnd.wap.signed-certificate",
    "application/vnd.wap.cert-response", "application/xhtml+xml", "application/wml+xml", "text/css",
    "application/vnd.wap.mms-message", "application/vnd.wap.rollover-certificate",
    "application/vnd.wap.locc+wbxml", "application/vnd.wap.loc+xml", "application/vnd.syncml.dm+wbxml",
    "application/vnd.syncml.dm+xml", "application/vnd.syncml.notification",
    "application/vnd.uplanet.bearer-choice", "application/vnd.wap.xhtml+xml",
    "application/vnd.wv.csp.cir", "application/vnd.oma.dd+xml", "application/vnd.oma.drm.message",
    "application/vnd.oma.drm.content", "application/vnd.oma.drm.rights+xml",
    "application/vnd.oma.drm.rights+wbxml", "application/vnd.wv.csp+xml",
    "application/vnd.wv.csp+wbxml", "application/vnd.syncml.ds.notification", "audio/*", "video/*",
    "application/vnd.oma.dd2+xml", "application/mikey",
)

internal const val CT_MULTIPART_RELATED = "application/vnd.wap.multipart.related"
internal const val CT_MULTIPART_MIXED = "application/vnd.wap.multipart.mixed"
