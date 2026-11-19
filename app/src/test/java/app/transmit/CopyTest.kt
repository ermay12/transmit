package app.transmit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product decisions about wording, checked across every string resource. */
class CopyTest {
    @Test
    fun theTrialIsAlwaysTheFreeWeek() {
        val trial = TestStrings.all.filterValues { it.contains("trial", ignoreCase = true) }
        assertEquals(emptyMap<String, String>(), trial)
        assertEquals("Your free week has ended", TestStrings.all["ended_free_week"])
    }

    /** Moving down from Plus comes with backend work. Until then the app says nothing about it, either way. */
    @Test
    fun nothingSaysWhetherPlusCanMoveDown() {
        val mentions = TestStrings.all.filterValues { v -> listOf("downgrad", "switch plans", "change plans", "move down").any { v.contains(it, ignoreCase = true) } }
        assertEquals(emptyMap<String, String>(), mentions)
    }

    @Test
    fun theStringsAreRead() {
        assertTrue(TestStrings.all.size > 100)
    }
}
