package app.transmit.messaging

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
    fun emojiOnlyMeansNoLettersInAnyScript() {
        assertTrue(Reactions.isEmoji("😂"))
        assertTrue(Reactions.isEmoji("❤️🔥"))
        assertTrue(Reactions.isEmoji("👍🏽"))
        assertTrue(Reactions.isEmoji("ℹ️"))
        assertFalse(Reactions.isEmoji("ok 👍"))
        assertFalse(Reactions.isEmoji("住所送るね📍"))
        assertFalse(Reactions.isEmoji("好的👌"))
        assertFalse(Reactions.isEmoji("좋아요 😊"))
        assertFalse(Reactions.isEmoji("ｏｋ👍"))
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

    /** In an SMS group, tapback texts Google Messages didn't recognize still say who reacted. */
    @Test
    fun aTallyOfTapbacksKnowsWhoReacted() {
        val dinner = text("s1", "Dinner at 8?")
        fun from(sender: String?, body: String) = text("s9", body).copy(outgoing = sender == null, sender = sender)
        val tally = Reactions.Tally()
        fun add(sender: String?, body: String) = tally.add(Reactions.parse(body)!!, from = from(sender, body), to = dinner)
        add("+15551110000", "Loved “Dinner at 8?”")
        add("5552220000", "Loved “Dinner at 8?”")
        add(null, "Liked “Dinner at 8?”")
        // A second tapback from the same person (written another way) replaces their first.
        add("+15552220000", "Laughed at “Dinner at 8?”")
        assertEquals(
            listOf(
                Reaction("❤️", 1, mine = false, others = listOf("+15551110000")),
                Reaction("👍", 1, mine = true),
                Reaction("😂", 1, mine = false, others = listOf("+15552220000")),
            ),
            tally.on(dinner),
        )
        add(null, "Removed a like from “Dinner at 8?”")
        assertEquals(listOf("❤️", "😂"), tally.on(dinner).map { it.emoji })
        // Google Messages' own reactions come first, and win over a tapback of the same emoji.
        val tracked = dinner.copy(reactions = listOf(Reaction("❤️", 2, mine = true, others = listOf("+15551110000"))))
        assertEquals(listOf(tracked.reactions.single(), Reaction("😂", 1, mine = false, others = listOf("+15552220000"))), tally.on(tracked))
        assertEquals(emptyList<Reaction>(), Reactions.Tally().on(text("s2", "Hi")))
    }

    private fun text(key: String, body: String) = Message(
        key = key, id = key.drop(1).toLong(), kind = if (key[0] == 'm') MessageKind.MMS else MessageKind.SMS, threadId = 1,
        sender = null, recipients = emptyList(), body = body, date = 0, dateSent = 0, outgoing = true,
        status = DeliveryStatus.SENT, read = true, subId = 0,
    )
}
