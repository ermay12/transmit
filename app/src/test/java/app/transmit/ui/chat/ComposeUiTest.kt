package app.transmit.ui.chat

import app.transmit.ai.Feedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composer with translation off: one plain box, sent as typed. */
class ComposeUiTest {
    private val feedback = Feedback(Feedback.Verdict.GOOD, "Hola", "Hola", "Hi", "")

    @Test
    fun aDraftOnlyInThePracticeBoxMovesIntoThePlainBox() {
        val draft = ComposeUi(target = "¿Vienes esta noche?", targetAi = true, stale = true, feedback = feedback, readyToSend = true)
        val plain = draft.plain()
        assertEquals("¿Vienes esta noche?", plain.native)
        assertEquals("", plain.target)
        assertFalse(plain.nativeAi)
        assertFalse(plain.targetAi)
        assertFalse(plain.stale)
        assertNull(plain.feedback)
        assertFalse(plain.readyToSend)
        assertTrue(plain.canSend)
    }

    @Test
    fun withBothBoxesFilledThePlainBoxKeepsWhatFriendsGet() {
        val draft = ComposeUi(native = "Are you coming tonight?", target = "¿Vienes esta noche?", nativeAi = true, feedback = feedback)
        val plain = draft.plain()
        assertEquals("Are you coming tonight?", plain.native)
        // The practice text waits for translation to come back.
        assertEquals("¿Vienes esta noche?", plain.target)
        assertNull(plain.feedback)
    }

    @Test
    fun anEmptyOrNativeOnlyDraftIsUnchanged() {
        assertEquals(ComposeUi(), ComposeUi().plain())
        val native = ComposeUi(native = "On my way")
        assertEquals(native, native.plain())
    }

    @Test
    fun withTranslationOffNothingWaitsForTheAiBeforeSending() {
        val practiceOnly = ComposeUi(target = "¿Vienes?")
        assertTrue(practiceOnly.needsNativeFirst(translationOff = false))
        assertFalse(practiceOnly.needsNativeFirst(translationOff = true))
        assertFalse(ComposeUi(native = "Coming?", target = "¿Vienes?").needsNativeFirst(translationOff = false))
        assertFalse(ComposeUi(native = "Coming?").needsNativeFirst(translationOff = false))
    }
}
