package app.transmit.ai

import app.transmit.access.AiAccess
import app.transmit.access.ManagedAi
import app.transmit.access.forAccess
import app.transmit.data.AppSettings
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Message
import app.transmit.messaging.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What switching a chat's auto-translate on sends to the AI. It's the same selection opening the chat makes
 * ([TranslationManager.autoTranslate]): the newest "Translate on open" messages, sent ones by "Translate my sent
 * messages", less those translated or on their way, and nothing without AI.
 */
class AutoTranslateSelectionTest {
    private fun message(n: Int, outgoing: Boolean = false, body: String = "See you at the café, message ${'a' + n}") = Message(
        key = "g$n", id = n.toLong(), kind = MessageKind.RCS, threadId = 1, sender = if (outgoing) null else "+15555550142",
        recipients = emptyList(), body = body, date = n * 60_000L, dateSent = n * 60_000L, outgoing = outgoing,
        status = DeliveryStatus.RECEIVED, read = true, subId = 0,
    )

    /** Twelve messages, oldest first, as a chat holds them; every third one is the user's. */
    private val chat = (1..12).map { message(it, outgoing = it % 3 == 0) }

    /** What switching on asks the AI for, with [translated] keys already done and [inFlight] ones on their way. */
    private fun requested(
        s: AppSettings,
        messages: List<Message> = chat,
        ai: Boolean = true,
        translated: Set<String> = emptySet(),
        inFlight: MutableSet<String> = mutableSetOf(),
    ): List<String> = TranslationManager.toRequest(TranslationManager.newestForAuto(messages, s), "es", ai, translated, inFlight).map { it.key }

    @Test
    fun theNewestTranslateOnOpenCount() {
        assertEquals(listOf("g12", "g11", "g10", "g9"), requested(AppSettings(backfillCount = 4)))
        assertEquals(listOf("g12", "g11", "g10", "g9", "g8", "g7", "g6", "g5"), requested(AppSettings(backfillCount = 8)))
        // "Translate on open" at 0: switching on translates nothing already there, only what arrives.
        assertEquals(emptyList<String>(), requested(AppSettings(backfillCount = 0)))
    }

    @Test
    fun managedAiUsesTheFixedCountWhateverTheUserSet() {
        val own = AppSettings(backfillCount = 20)
        val managed = listOf(AiAccess.Trial(endsAt = Long.MAX_VALUE), AiAccess.Subscribed(app.transmit.access.Tier.STANDARD, renewsAt = null))
        for (a in managed) assertEquals(ManagedAi.TRANSLATE_ON_OPEN, requested(own.forAccess(a)).size)
        assertEquals(12, requested(own.forAccess(AiAccess.Byok)).size)
    }

    @Test
    fun sentMessagesOnlyWithTranslateMySentMessages() {
        assertEquals(listOf("g12", "g11", "g10", "g9"), requested(AppSettings(backfillCount = 4, translateOutgoing = true)))
        // Without it, the count is of received messages: the user's own don't take a place.
        assertEquals(listOf("g11", "g10", "g8", "g7"), requested(AppSettings(backfillCount = 4, translateOutgoing = false)))
    }

    @Test
    fun verificationCodesAndTextWithoutWordsAreLeftOut() {
        val messages = chat + message(13, body = "Your verification code is 482913") + message(14, body = "👍") + message(15, body = "https://example.com/a")
        assertEquals(listOf("g12", "g11"), requested(AppSettings(backfillCount = 2), messages))
    }

    @Test
    fun alreadyTranslatedMessagesAreSkippedAndStillCount() {
        // The newest four are looked at; the two already translated aren't asked for again, and nothing older is.
        assertEquals(listOf("g12", "g10"), requested(AppSettings(backfillCount = 4), translated = setOf("g11", "g9")))
        assertEquals(emptyList<String>(), requested(AppSettings(backfillCount = 4), translated = setOf("g12", "g11", "g10", "g9")))
    }

    @Test
    fun messagesOnTheirWayAreNotAskedForTwice() {
        val inFlight = mutableSetOf("g12|es")
        // Opening the chat already asked for g12; switching on picks up the rest and marks them on their way.
        assertEquals(listOf("g11", "g10", "g9"), requested(AppSettings(backfillCount = 4), inFlight = inFlight))
        assertTrue(inFlight.containsAll(listOf("g12|es", "g11|es", "g10|es", "g9|es")))
        // A second switch-on while those are out asks for nothing.
        assertEquals(emptyList<String>(), requested(AppSettings(backfillCount = 4), inFlight = inFlight))
        // Another language is a different translation.
        assertEquals(listOf("g12"), TranslationManager.toRequest(listOf(message(12)), "fr", true, emptySet(), inFlight).map { it.key })
    }

    @Test
    fun nothingIsAskedForWithoutAi() {
        val inFlight = mutableSetOf<String>()
        assertEquals(emptyList<String>(), requested(AppSettings(backfillCount = 4), ai = false, inFlight = inFlight))
        // Nothing is marked on its way either, so turning AI on later translates them.
        assertTrue(inFlight.isEmpty())
        assertEquals(listOf("g12", "g11", "g10", "g9"), requested(AppSettings(backfillCount = 4), inFlight = inFlight))
    }
}
