package app.transmit.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

class SenderSplitTest {
    @Test
    fun textAloneIsOneMessage() {
        assertEquals(listOf("hi" to null), Sender.split("hi", emptyList<String>(), captions = false))
        assertEquals(listOf("hi" to null), Sender.split("hi", emptyList<String>(), captions = true))
    }

    @Test
    fun rcsSendsTheTextAfterThePhotosOnItsOwn() {
        // The phone rejects an RCS message with both a photo and text, so neither would send.
        assertEquals(
            listOf("" to "photo1", "" to "photo2", "hi" to null),
            Sender.split("hi", listOf("photo1", "photo2"), captions = false),
        )
    }

    @Test
    fun rcsSendsNoEmptyTextMessage() {
        assertEquals(listOf("" to "photo"), Sender.split("", listOf("photo"), captions = false))
        assertEquals(listOf("" to "photo"), Sender.split("  ", listOf("photo"), captions = false))
    }

    @Test
    fun smsCaptionsTheLastPhoto() {
        assertEquals(
            listOf("" to "photo1", "hi" to "photo2"),
            Sender.split("hi", listOf("photo1", "photo2"), captions = true),
        )
    }
}
