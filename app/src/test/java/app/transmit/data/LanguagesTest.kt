package app.transmit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagesTest {
    @Test
    fun recentLanguagesComeFirstMostRecentFirst() {
        val (top, rest) = Languages.sections(listOf("ja", "fr", "es"))
        assertEquals(listOf("ja", "fr", "es"), top.map { it.code })
        assertEquals(Languages.all.size - 3, rest.size)
        assertEquals(Languages.all.map { it.code }.filter { it !in setOf("ja", "fr", "es") }, rest.map { it.code })
    }

    @Test
    fun excludedUnknownAndDuplicateCodesAreSkipped() {
        val (top, rest) = Languages.sections(listOf("en", "xx", "fr", "fr", "de"), exclude = "en")
        assertEquals(listOf("fr", "de"), top.map { it.code })
        assertEquals(Languages.all.size - 3, rest.size)
        assertEquals(false, rest.any { it.code == "en" })
    }

    @Test
    fun onlyAFewRecentAreShown() {
        val (top, _) = Languages.sections(Languages.all.map { it.code })
        assertEquals(Languages.RECENT_SHOWN, top.size)
    }

    @Test
    fun usingALanguageMovesItToTheFront() {
        assertEquals(listOf("fr", "es", "en"), Languages.used(listOf("es", "en"), "fr"))
        assertEquals(listOf("en", "fr", "es"), Languages.used(listOf("fr", "es", "en"), "en"))
        assertEquals(8, Languages.used(Languages.all.map { it.code }, "tl").size)
    }
}
