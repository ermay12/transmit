package app.transmit.ui.chat

import app.transmit.data.ScriptDisplay
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.data.db.shown
import app.transmit.data.db.shownWithReading
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Message
import app.transmit.messaging.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUiTest {
    private val message = Message(
        key = "g1", id = 1, kind = MessageKind.RCS, threadId = 1, sender = "+15551234567", recipients = emptyList(),
        body = "Are you coming tonight?", date = 0, dateSent = 0, outgoing = false, status = DeliveryStatus.RECEIVED, read = true, subId = 0,
    )

    private fun translation(romanized: String?) = TranslationEntity(
        "g1", "zh-Hans", 1, 0, "你今晚来吗？", TranslationOrigin.AI, null, 0.0, 0, 0, 0, romanized,
    )

    private fun item(script: ScriptDisplay?, romanized: String? = "Nǐ jīnwǎn lái ma?", showRoman: Boolean = false, showOriginal: Boolean = false, failed: Boolean = false) =
        MessageUi(
            message = message, reactions = emptyList(), translation = translation(romanized), showOriginal = showOriginal,
            firstInRun = true, lastInRun = true, newDay = false, translatable = true, awaiting = false,
            script = script, showRoman = showRoman, romanFailed = failed,
        )

    @Test
    fun bothShowsTheRomanizationUnderTheCharacters() {
        val i = item(ScriptDisplay.BOTH)
        assertEquals("你今晚来吗？", i.displayText)
        assertEquals("Nǐ jīnwǎn lái ma?", i.subtext)
        assertFalse(i.romanizesOnSight)
        // On its way: a placeholder line, and a request when the bubble is drawn.
        val pending = item(ScriptDisplay.BOTH, romanized = null)
        assertNull(pending.subtext)
        assertTrue(pending.subtextPending)
        assertTrue(pending.romanizesOnSight)
        // The original has no romanization line.
        assertEquals("Are you coming tonight?", item(ScriptDisplay.BOTH, showOriginal = true).displayText)
        assertNull(item(ScriptDisplay.BOTH, showOriginal = true).subtext)
    }

    @Test
    fun romanizedShowsOnlyTheRomanization() {
        val i = item(ScriptDisplay.ROMANIZED)
        assertEquals("Nǐ jīnwǎn lái ma?", i.displayText)
        assertNull(i.subtext)
        assertTrue(item(ScriptDisplay.ROMANIZED, romanized = null).romanizesOnSight)
        // When romanizing failed, the characters show rather than nothing.
        val failed = item(ScriptDisplay.ROMANIZED, romanized = null, failed = true)
        assertEquals("你今晚来吗？", failed.displayText)
        assertFalse(failed.romanizesOnSight)
    }

    @Test
    fun charactersShowTheRomanizationOnlyWhenTapped() {
        val i = item(ScriptDisplay.CHARACTERS)
        assertEquals("你今晚来吗？", i.displayText)
        assertNull(i.subtext)
        assertEquals("Nǐ jīnwǎn lái ma?", item(ScriptDisplay.CHARACTERS, showRoman = true).subtext)
        // A tap asks for a missing one; it isn't fetched just for being on screen.
        val missing = item(ScriptDisplay.CHARACTERS, romanized = null)
        assertFalse(missing.romanMissing)
        assertFalse(missing.romanizesOnSight)
        val tapped = item(ScriptDisplay.CHARACTERS, romanized = null, showRoman = true)
        assertTrue(tapped.subtextPending)
        assertFalse(tapped.romanizesOnSight)
    }

    @Test
    fun languagesWithoutARomanizationAreUnchanged() {
        val i = item(null)
        assertEquals("你今晚来吗？", i.displayText)
        assertNull(i.subtext)
        assertFalse(i.romanMissing)
    }

    @Test
    fun oneLineTextFollowsTheDisplay() {
        val t = translation("Nǐ jīnwǎn lái ma?")
        assertEquals("Nǐ jīnwǎn lái ma?", t.shown(ScriptDisplay.ROMANIZED))
        assertEquals("你今晚来吗？", t.shown(ScriptDisplay.BOTH))
        assertEquals("你今晚来吗？\nNǐ jīnwǎn lái ma?", t.shownWithReading(ScriptDisplay.BOTH))
        assertEquals("你今晚来吗？", t.shownWithReading(ScriptDisplay.CHARACTERS))
        assertEquals("你今晚来吗？", translation(null).shown(ScriptDisplay.ROMANIZED))
    }

    @Test
    fun composeReadingDimsWhileTheNewOneIsOnItsWay() {
        val c = ComposeUi(target = "我想去", reading = "我想去" to "Wǒ xiǎng qù")
        assertEquals("Wǒ xiǎng qù", c.targetReading)
        assertFalse(c.readingStale)
        val typing = c.copy(target = "我想去北京", readingBusy = true)
        assertEquals("Wǒ xiǎng qù", typing.targetReading)
        assertTrue(typing.readingStale)
        // Nothing to add when the box already holds the romanization.
        assertNull(ComposeUi(target = "dui", reading = "dui" to "dui").targetReading)
        assertNull(c.copy(target = "").targetReading)
    }
}
