package app.transmit.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionsTest {
    @Test
    fun parsesIMessageTapbacks() {
        assertEquals(Reactions.Parsed("❤️", "See you soon", false), Reactions.parse("Loved “See you soon”"))
        assertEquals(Reactions.Parsed("😂", "lol ok", false), Reactions.parse("Laughed at \"lol ok\""))
        assertEquals(Reactions.Parsed("👍", null, false), Reactions.parse("Liked an image"))
        assertEquals(Reactions.Parsed("‼️", "Wait", false), Reactions.parse("Emphasized “Wait”"))
    }

    @Test
    fun parsesEmojiAndRemovalForms() {
        assertEquals(Reactions.Parsed("🔥", "Nice", false), Reactions.parse("Reacted 🔥 to “Nice”"))
        assertEquals(Reactions.Parsed("❤️", "Hi", true), Reactions.parse("Removed a heart from “Hi”"))
        assertEquals(Reactions.Parsed("😮", "Hi", true), Reactions.parse("Removed 😮 from “Hi”"))
        assertEquals(Reactions.Parsed("👍", "Ok", false), Reactions.parse("‎👍 to “Ok”"))
    }

    @Test
    fun ignoresOrdinaryMessages() {
        assertNull(Reactions.parse("Loved the movie, thanks!"))
        assertNull(Reactions.parse("I went to “the place”"))
        assertNull(Reactions.parse("ok"))
        assertNull(Reactions.parse("Reacted badly to “news”"))
    }

    @Test
    fun formatRoundTrips() {
        for (emoji in Reactions.QUICK + listOf("🔥", "😮")) {
            val text = Reactions.format(emoji, "Dinner at 8?", targetIsMedia = false)
            assertEquals(Reactions.Parsed(emoji, "Dinner at 8?", false), Reactions.parse(text))
            val removal = Reactions.format(emoji, "Dinner at 8?", targetIsMedia = false, remove = true)
            assertEquals(Reactions.Parsed(emoji, "Dinner at 8?", true), Reactions.parse(removal))
        }
        assertEquals("Loved “Hi”", Reactions.format("❤️", "Hi", false))
        assertEquals("Reacted 🔥 to an image", Reactions.format("🔥", "", true))
    }

    @Test
    fun matchesTruncatedQuotes() {
        val long = "This is a fairly long message that someone will react to on their phone"
        assertTrue(Reactions.matches("This is a fairly long message…", long))
        assertTrue(Reactions.matches("It’s fine", "It's fine"))
        assertFalse(Reactions.matches("Hi…", "Hello there"))
    }

    @Test
    fun targetIsNewestQuotedMessage() {
        val long = "This is a fairly long message that someone will react to on their phone"
        val newestFirst = listOf(text("s4", "Ok"), text("s3", "See you soon"), text("m2", long), text("s1", "See you soon"))
        assertEquals("s3", Reactions.target(Reactions.parse("Loved “See you soon”")!!, newestFirst)?.key)
        assertEquals("m2", Reactions.target(Reactions.parse("Liked “This is a fairly long message…”")!!, newestFirst)?.key)
        assertNull(Reactions.target(Reactions.parse("Loved “Something else”")!!, newestFirst))
        assertNull(Reactions.target(Reactions.parse("Liked an image")!!, newestFirst))
    }

    private fun text(key: String, body: String) = Message(
        key = key, id = key.drop(1).toLong(), kind = if (key[0] == 'm') MessageKind.MMS else MessageKind.SMS, threadId = 1,
        sender = null, recipients = emptyList(), body = body, date = 0, dateSent = 0, outgoing = true,
        status = DeliveryStatus.SENT, read = true, subId = 0,
    )
}
