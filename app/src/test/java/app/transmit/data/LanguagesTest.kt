package app.transmit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun keyboardsAreHintedByRegionForChinese() {
        assertEquals("zh-CN", Languages.get("zh-Hans").keyboardLocale)
        assertEquals("zh-TW", Languages.get("zh-Hant").keyboardLocale)
        assertEquals("ja", Languages.get("ja").keyboardLocale)
    }

    @Test
    fun chineseAndJapaneseHaveRomanizations() {
        assertEquals(Romanization.PINYIN, Languages.get("zh-Hans").romanization)
        assertEquals(Romanization.PINYIN, Languages.get("zh-Hant").romanization)
        assertEquals(Romanization.ROMAJI, Languages.get("ja").romanization)
        assertEquals(null, Languages.get("ko").romanization)
        assertEquals(null, Languages.get("es").romanization)
        assertEquals("汉字", Languages.get("zh-Hans").scriptName)
        assertEquals("漢字", Languages.get("zh-Hant").scriptName)
        assertTrue(Languages.get("vi").latinScript)
        assertTrue(!Languages.get("ru").latinScript)
    }

    @Test
    fun scriptDisplayIsPerRomanizationAndDefaultsToBoth() {
        val s = AppSettings(scriptDisplays = mapOf(Romanization.ROMAJI to ScriptDisplay.ROMANIZED))
        assertEquals(ScriptDisplay.BOTH, s.scriptDisplay(Languages.get("zh-Hans")))
        assertEquals(ScriptDisplay.ROMANIZED, s.scriptDisplay(Languages.get("ja")))
        assertEquals(null, s.scriptDisplay(Languages.get("fr")))
        assertTrue(s.romanizesAutomatically(Languages.get("ja")))
        assertTrue(!s.romanizesAutomatically(Languages.get("fr")))
        assertTrue(!AppSettings(scriptDisplays = mapOf(Romanization.PINYIN to ScriptDisplay.CHARACTERS)).romanizesAutomatically(Languages.get("zh-Hant")))
    }
}
