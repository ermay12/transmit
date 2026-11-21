package app.transmit.ui.chat

import app.transmit.data.ScriptDisplay
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Message
import app.transmit.messaging.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The side of a bubble that shows with the translate switch on and off (docs/product/chat-extras.md, section 3). The
 * user speaks English and practises Spanish.
 */
class BubbleSideTest {
    private fun message(key: String, body: String, outgoing: Boolean = false) = Message(
        key = key, id = 1, kind = MessageKind.RCS, threadId = 1, sender = if (outgoing) null else "+15551234567", recipients = emptyList(),
        body = body, date = 0, dateSent = 0, outgoing = outgoing, status = DeliveryStatus.RECEIVED, read = true, subId = 0,
    )

    private fun spanish(key: String, text: String, origin: String = TranslationOrigin.AI, romanized: String? = null) =
        TranslationEntity(key, "es", 1, 0, text, origin, null, 0.0, 0, 0, 0, romanized)

    /** [m]'s bubble as the chat shows it with the switch [on] and these [flips]. */
    private fun bubble(
        m: Message,
        t: TranslationEntity?,
        on: Boolean,
        flips: Flips = Flips(on),
        waiting: Boolean = false,
        script: ScriptDisplay? = null,
    ): MessageUi {
        val side = bubbleSide(on, flips, m.key, t, waiting)
        return MessageUi(
            message = m, reactions = emptyList(), translation = t, showOriginal = side.showOriginal,
            firstInRun = true, lastInRun = true, newDay = false, translatable = true, awaiting = side.awaiting,
            script = script, showRoman = m.key in flips.under(on).roman, nativeSide = side.nativeSide,
        )
    }

    @Test
    fun anIncomingMessageInTheNativeLanguageShowsItsOriginalWhenOff() {
        val m = message("g1", "Want to meet there at 7?")
        val t = spanish("g1", "¿Quieres quedar allí a las 7?")
        assertEquals("¿Quieres quedar allí a las 7?", bubble(m, t, on = true).displayText)
        val off = bubble(m, t, on = false)
        assertEquals("Want to meet there at 7?", off.displayText)
        assertFalse(off.showingTranslation)
        // The translation is kept to the side: the switch crossfades between them.
        assertTrue(off.nativeSide)
        assertFalse(bubble(m, t, on = true).nativeSide)
    }

    @Test
    fun anIncomingMessageInThePracticeLanguageShowsItsOriginalWhenOff() {
        // There's no cached English translation to show (translations are only into the practice language), so the
        // original shows, with its cached practice-language translation (the same Spanish) or without one.
        val m = message("g1", "¿Vienes a cenar?")
        assertEquals("¿Vienes a cenar?", bubble(m, spanish("g1", "¿Vienes a cenar?"), on = false).displayText)
        val bare = bubble(m, null, on = false)
        assertEquals("¿Vienes a cenar?", bare.displayText)
        assertFalse(bare.awaiting)
        assertFalse(bare.nativeSide)
    }

    @Test
    fun sentMessagesShowWhatFriendsGotWhenOff() {
        // Written in the Spanish box and sent in English: the English went out, the Spanish is the translation.
        val sent = message("g2", "Yes! What do you have in mind?", outgoing = true)
        val wrote = spanish("g2", "¡Sí! ¿Qué tienes en mente?", origin = TranslationOrigin.SELF)
        assertEquals("¡Sí! ¿Qué tienes en mente?", bubble(sent, wrote, on = true).displayText)
        assertEquals("Yes! What do you have in mind?", bubble(sent, wrote, on = false).displayText)
        // Sent from the phone and translated by "Translate my sent messages": the same.
        val fromPhone = message("g3", "On my way", outgoing = true)
        val ai = spanish("g3", "Voy para allá")
        assertEquals("Voy para allá", bubble(fromPhone, ai, on = true).displayText)
        assertEquals("On my way", bubble(fromPhone, ai, on = false).displayText)
    }

    @Test
    fun aTapShowsTheOtherSideUntilTheSwitchMoves() {
        val m = message("g1", "See you at dinner")
        val t = spanish("g1", "Nos vemos en la cena")
        // On, tapped to the original.
        val tappedOn = Flips(on = true).toggle("g1")
        assertEquals("See you at dinner", bubble(m, t, on = true, flips = tappedOn).displayText)
        // Switched off, then on again: the tap is gone, and the bubble is on the chat's side each time.
        assertEquals("See you at dinner", bubble(m, t, on = false, flips = tappedOn).displayText)
        assertTrue(tappedOn.under(false).original.isEmpty())
        // Off, tapped to the translation: one message's translation, as before.
        val tappedOff = Flips(on = false).toggle("g1")
        assertEquals("Nos vemos en la cena", bubble(m, t, on = false, flips = tappedOff).displayText)
        assertFalse(bubble(m, t, on = false, flips = tappedOff).showOriginal)
        // Switched on: the tap is gone, so the translation shows as the chat's side, not the original.
        assertEquals("Nos vemos en la cena", bubble(m, t, on = true, flips = tappedOff).displayText)
        // And off again: still gone, once the switch's moves have reset the taps in turn.
        assertEquals("See you at dinner", bubble(m, t, on = false, flips = tappedOff.under(true)).displayText)
        // The romanization tapped under the characters goes too.
        assertTrue(Flips(on = true, roman = setOf("g1")).under(false).roman.isEmpty())
        // Showing a side is relative to the chat's: the original with the switch on is a flip, off it isn't.
        assertEquals(setOf("g1"), Flips(on = true).show("g1", toOriginal = true).original)
        assertTrue(Flips(on = false).show("g1", toOriginal = true).original.isEmpty())
        assertEquals(setOf("g1"), Flips(on = false).show("g1", toOriginal = false).original)
    }

    @Test
    fun offWithNoCachedTranslationsShowsEveryOriginalAndWaitsForNothing() {
        val m = message("g1", "Did you finish the book?")
        // A translation started before the switch went off isn't waited for: the original shows, not the placeholder.
        val inFlight = bubble(m, null, on = false, waiting = true)
        assertFalse(inFlight.awaiting)
        assertEquals("Did you finish the book?", inFlight.displayText)
        // Nothing to switch to, so the switch leaves the bubble as it was.
        assertEquals(bubble(m, null, on = true), bubble(m, null, on = false))
        // On, the same message waits for its translation.
        assertTrue(bubble(m, null, on = true, waiting = true).awaiting)
    }

    @Test
    fun offTheTranslateActionStillShowsOneMessagesTranslation() {
        val m = message("g1", "Did you finish the book?")
        val asked = Flips(on = false).show("g1", toOriginal = false)
        // Its placeholder while the translation is on its way, then the translation.
        val waiting = bubble(m, null, on = false, flips = asked, waiting = true)
        assertTrue(waiting.awaiting)
        assertFalse(waiting.showOriginal)
        assertEquals("¿Terminaste el libro?", bubble(m, spanish("g1", "¿Terminaste el libro?"), on = false, flips = asked).displayText)
        // Other bubbles stay in English.
        assertEquals("Hi", bubble(message("g2", "Hi"), spanish("g2", "Hola"), on = false, flips = asked).displayText)
    }

    @Test
    fun offTheReadingIsNeitherShownNorFetched() {
        val m = message("g1", "Are you coming tonight?")
        val zh = TranslationEntity("g1", "zh-Hans", 1, 0, "你今晚来吗？", TranslationOrigin.AI, null, 0.0, 0, 0, 0, null)
        // On, the missing pinyin is fetched as the bubble comes into view, and the reader of pinyin alone waits for it.
        assertTrue(bubble(m, zh, on = true, script = ScriptDisplay.BOTH).romanizesOnSight)
        assertTrue(bubble(m, zh, on = true, waiting = true, script = ScriptDisplay.ROMANIZED).awaiting)
        // Off, the translation is behind the English: no pinyin line, no request, no wait.
        val off = bubble(m, zh, on = false, script = ScriptDisplay.BOTH)
        assertEquals("Are you coming tonight?", off.displayText)
        assertFalse(off.romanizesOnSight)
        assertFalse(off.subtextPending)
        assertFalse(bubble(m, zh, on = false, waiting = true, script = ScriptDisplay.ROMANIZED).awaiting)
        // Tapped to the translation, the reading shows as it would with the switch on.
        val tapped = bubble(m, zh.copy(romanized = "Nǐ jīnwǎn lái ma?"), on = false, flips = Flips(on = false).toggle("g1"), script = ScriptDisplay.BOTH)
        assertEquals("Nǐ jīnwǎn lái ma?", tapped.subtext)
    }

    @Test
    fun searchStillShowsAMatchOnTheHiddenSide() {
        val m = message("g1", "See you at dinner")
        val off = bubble(m, spanish("g1", "Nos vemos en la cena"), on = false)
        val shown = off.revealing(SearchHit("g1", 0, inOriginal = false, inTranslation = true))
        assertEquals("Nos vemos en la cena", shown.displayText)
        assertEquals(off, off.revealing(null))
    }
}
