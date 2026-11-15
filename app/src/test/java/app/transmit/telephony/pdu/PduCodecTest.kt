package app.transmit.telephony.pdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PduCodecTest {

    @Test
    fun sendRequestRoundTrips() {
        val smil = MmsPart("application/smil", "<smil/>".toByteArray(), name = "smil.xml", contentId = "<smil>", contentLocation = "smil.xml", charset = 106)
        val image = MmsPart("image/jpeg", ByteArray(5000) { (it % 251).toByte() }, name = "image0.jpg", contentId = "<image0.jpg>", contentLocation = "image0.jpg")
        val text = MmsPart("text/plain", "¿Qué tal? 😀".toByteArray(), name = "text_0.txt", contentId = "<text_0>", contentLocation = "text_0.txt", charset = 106)
        val bytes = PduComposer.sendRequest("T123abc", listOf("+1 (555) 123-4567", "5559876543"), listOf(smil, image, text), 1_700_000_000L, subject = "Hola ñ")

        val pdu = PduParser.parse(bytes)
        assertEquals(MmsMessageType.SEND_REQ, pdu.messageType)
        assertEquals("T123abc", pdu.transactionId)
        assertEquals(MmsValues.VERSION_1_2, pdu.version)
        assertEquals(1_700_000_000L, pdu.date)
        assertEquals(listOf("+15551234567", "5559876543"), pdu.to)
        assertEquals("Hola ñ", pdu.subject)
        assertNull(pdu.from) // insert-address-token
        assertEquals(CT_MULTIPART_RELATED, pdu.contentType?.mediaType)
        assertEquals("<smil>", pdu.contentType?.params?.get("start"))
        assertEquals("application/smil", pdu.contentType?.params?.get("type"))
        assertEquals(7 * 24 * 3600L, pdu.expiry)
        assertEquals(true, pdu.expiryRelative)

        assertEquals(3, pdu.parts.size)
        val (p0, p1, p2) = pdu.parts
        assertEquals("application/smil", p0.contentType)
        assertEquals("<smil>", p0.contentId)
        assertEquals("image/jpeg", p1.contentType)
        assertEquals("image0.jpg", p1.name)
        assertEquals("image0.jpg", p1.contentLocation)
        assertArrayEquals(image.data, p1.data)
        assertEquals("text/plain", p2.contentType)
        assertEquals(106, p2.charset)
        assertEquals("¿Qué tal? 😀", p2.text())
    }

    @Test
    fun parsesNotificationInd() {
        // M-Notification.ind as a carrier would push it (hand-assembled).
        val w = PduWriter()
        w.header(MmsHeaders.MESSAGE_TYPE); w.byte(MmsMessageType.NOTIFICATION_IND)
        w.header(MmsHeaders.TRANSACTION_ID); w.text("tx-42")
        w.header(MmsHeaders.MMS_VERSION); w.byte(0x92)
        w.header(MmsHeaders.FROM)
        val from = PduWriter().apply { byte(0x80); text("+15550001111/TYPE=PLMN") }.bytes()
        w.valueLength(from.size); w.raw(from)
        w.header(MmsHeaders.MESSAGE_CLASS); w.byte(0x80)
        w.header(MmsHeaders.MESSAGE_SIZE); w.longInteger(123456)
        w.header(MmsHeaders.EXPIRY)
        val exp = PduWriter().apply { byte(0x81); longInteger(172800) }.bytes()
        w.valueLength(exp.size); w.raw(exp)
        // An unknown carrier header (X-Mms-Content-Class) must be skipped.
        w.byte(0x80 or 0x3A); w.byte(0x80)
        w.header(MmsHeaders.CONTENT_LOCATION); w.text("http://mmsc.example.com/abc?id=1")

        val pdu = PduParser.parse(w.bytes())
        assertEquals(MmsMessageType.NOTIFICATION_IND, pdu.messageType)
        assertEquals("tx-42", pdu.transactionId)
        assertEquals("+15550001111", pdu.from)
        assertEquals(123456L, pdu.messageSize)
        assertEquals(172800L, pdu.expiry)
        assertEquals("http://mmsc.example.com/abc?id=1", pdu.contentLocation)
        assertEquals("personal", pdu.messageClass)
    }

    @Test
    fun parsesRetrieveConfWithWellKnownTypesAndEncodedStrings() {
        val w = PduWriter()
        w.header(MmsHeaders.MESSAGE_TYPE); w.byte(MmsMessageType.RETRIEVE_CONF)
        w.header(MmsHeaders.MMS_VERSION); w.byte(0x92)
        w.header(MmsHeaders.MESSAGE_ID); w.text("msg-1")
        w.header(MmsHeaders.DATE); w.longInteger(1_700_000_123)
        w.header(MmsHeaders.FROM)
        val from = PduWriter().apply { byte(0x80); text("+15550001111/TYPE=PLMN") }.bytes()
        w.valueLength(from.size); w.raw(from)
        w.header(MmsHeaders.TO); w.text("+15552223333/TYPE=PLMN")
        w.header(MmsHeaders.TO); w.text("+15554445555/TYPE=PLMN")
        w.header(MmsHeaders.SUBJECT); w.encodedString("日本語")
        w.header(MmsHeaders.CONTENT_TYPE)
        val ct = PduWriter().apply { byte(0xB3); byte(0x89); text("application/smil") }.bytes()
        w.valueLength(ct.size); w.raw(ct)
        w.uintvar(2)
        // Part 1: well-known text/plain (0x83) with charset utf-8, quoted Content-ID.
        val h1 = PduWriter().apply {
            val c = PduWriter().apply { byte(0x83); byte(0x81); byte(0xEA) }.bytes()
            valueLength(c.size); raw(c)
            byte(0xC0); quotedString("<t1>")
            byte(0x8E); text("t1.txt")
        }.bytes()
        val d1 = "こんにちは".toByteArray()
        w.uintvar(h1.size.toLong()); w.uintvar(d1.size.toLong()); w.raw(h1); w.raw(d1)
        // Part 2: constrained well-known image/png (0xA0) with no params.
        val h2 = PduWriter().apply { byte(0xA0); byte(0x8E); text("pic.png") }.bytes()
        val d2 = byteArrayOf(1, 2, 3, 4)
        w.uintvar(h2.size.toLong()); w.uintvar(d2.size.toLong()); w.raw(h2); w.raw(d2)

        val pdu = PduParser.parse(w.bytes())
        assertEquals(MmsMessageType.RETRIEVE_CONF, pdu.messageType)
        assertEquals("msg-1", pdu.messageId)
        assertEquals(listOf("+15552223333", "+15554445555"), pdu.to)
        assertEquals("日本語", pdu.subject)
        assertEquals(2, pdu.parts.size)
        assertEquals("text/plain", pdu.parts[0].contentType)
        assertEquals("<t1>", pdu.parts[0].contentId)
        assertEquals("こんにちは", pdu.parts[0].text())
        assertEquals("image/png", pdu.parts[1].contentType)
        assertEquals("pic.png", pdu.parts[1].contentLocation)
        assertArrayEquals(d2, pdu.parts[1].data)
    }

    @Test
    fun notifyResponseIsWellFormed() {
        val pdu = PduParser.parse(PduComposer.notifyResponse("tx-9"))
        assertEquals(MmsMessageType.NOTIFYRESP_IND, pdu.messageType)
        assertEquals("tx-9", pdu.transactionId)
        assertEquals(MmsValues.STATUS_RETRIEVED, pdu.status)
    }

    @Test
    fun uintvarEncoding() {
        val w = PduWriter()
        w.uintvar(0); w.uintvar(127); w.uintvar(128); w.uintvar(16384)
        assertArrayEquals(
            byteArrayOf(0x00, 0x7F, 0x81.toByte(), 0x00, 0x81.toByte(), 0x80.toByte(), 0x00),
            w.bytes(),
        )
    }
}
