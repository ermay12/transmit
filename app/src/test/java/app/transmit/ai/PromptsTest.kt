package app.transmit.ai

import app.transmit.data.Languages
import app.transmit.data.Level
import app.transmit.data.Romanization
import app.transmit.data.ScriptDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    @Test
    fun parsesTaggedBatch() {
        val out = "<m id=\"1\">Hola</m>\n<m id=\"2\">¿Qué tal?\nBien</m>"
        assertEquals(mapOf("1" to "Hola", "2" to "¿Qué tal?\nBien"), Prompts.parseTranslations(out, listOf("1", "2")))
    }

    @Test
    fun singleUntaggedReplyIsAccepted() {
        assertEquals(mapOf("1" to "Bonjour"), Prompts.parseTranslations("Bonjour", listOf("1")))
        assertEquals(emptyMap<String, String>(), Prompts.parseTranslations("Bonjour", listOf("1", "2")))
    }

    @Test
    fun ignoresUnknownIds() {
        assertEquals(mapOf("1" to "a"), Prompts.parseTranslations("<m id=\"1\">a</m><m id=\"9\">b</m>", listOf("1")))
    }

    @Test
    fun parsesFeedbackJson() {
        val raw = """```json
            {"verdict":"fix","corrected":"Estoy cansado","natural":"Estoy cansado","translation":"I'm tired","notes":"- Use **estar** for states"}
            ```"""
        val f = Prompts.parseFeedback(raw)
        assertEquals(Feedback.Verdict.FIX, f.verdict)
        assertEquals("Estoy cansado", f.corrected)
        assertEquals("", f.natural) // identical to corrected
        assertEquals("I'm tired", f.translation)
        assertTrue(f.notes.contains("estar"))
    }

    @Test
    fun feedbackFallsBackToRawText() {
        val f = Prompts.parseFeedback("Looks great!")
        assertEquals(Feedback.Verdict.GOOD, f.verdict)
        assertEquals("Looks great!", f.notes)
    }

    @Test
    fun promptsMentionLanguagesAndReadingAids() {
        val ja = Languages.get("ja")
        val en = Languages.get("en")
        val sys = Prompts.tutorSystem(en, ja, Level.BEGINNER)
        assertTrue(sys.contains("Japanese"))
        assertTrue(sys.contains("furigana"))
        val t = Prompts.translateUser(listOf("1" to "hi"), listOf(ContextLine(true, null, "yo"), ContextLine(false, "Ana", "hey")))
        assertTrue(t.contains("Me: yo"))
        assertTrue(t.contains("Ana: hey"))
        assertTrue(t.contains("<m id=\"1\">hi</m>"))
    }

    @Test
    fun translationPromptAsksForTextingEquivalentsNotGlosses() {
        val fr = Prompts.translateSystem(Languages.get("fr"))
        assertTrue(fr.contains("into French"))
        assertTrue(fr.contains("mdr / ptdr (lol)"))
        assertTrue(fr.contains("no alternatives, slashes"))
        assertTrue(fr.contains("never make it more slangy"))
        // Unbenchmarked languages get the general rules without a hints line; Traditional Chinese
        // must not get the Simplified examples.
        assertTrue(!Prompts.translateSystem(Languages.get("de")).contains("texters write things like"))
        assertTrue(!Prompts.translateSystem(Languages.get("zh-Hant")).contains("哈哈哈"))
    }

    @Test
    fun feedbackTranslationIsASendableMessage() {
        val sys = Prompts.feedbackSystem(Languages.get("en"), Languages.get("fr"), Level.INTERMEDIATE)
        assertTrue(sys.contains("\"translation\": the corrected text as the English text message they would send a friend"))
        assertTrue(!sys.contains("meaning of the corrected text"))
        assertTrue(Prompts.feedbackUser(null, "Bonjour", Languages.get("en"), Languages.get("fr")).contains("the English message I'd send"))
    }

    @Test
    fun builtInPricesMatchSnapshots() {
        assertEquals(Price(4.0, 20.0), Pricing.builtIn(ProviderType.ANTHROPIC, "claude-opus-5-5"))
        assertEquals(Price(1.0, 5.0), Pricing.builtIn(ProviderType.ANTHROPIC, "claude-haiku-4-5-20251001"))
        assertEquals(0.25, Pricing.builtIn(ProviderType.OPENAI, "gpt-5-mini-2025-08-07")!!.inputPerM, 1e-9)
        assertEquals(null, Pricing.builtIn(ProviderType.OPENROUTER, "anything"))
        assertEquals(0.000024, Price(4.0, 20.0).cost(1, 1), 1e-12)
    }

    @Test
    fun openAiModelFilter() {
        assertTrue(OpenAiCompatClient.isOpenAiChatModel("gpt-5-mini"))
        assertTrue(OpenAiCompatClient.isOpenAiChatModel("o4-mini"))
        assertTrue(!OpenAiCompatClient.isOpenAiChatModel("gpt-4o-realtime-preview"))
        assertTrue(!OpenAiCompatClient.isOpenAiChatModel("text-embedding-3-small"))
        assertEquals(listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH), OpenAiCompatClient.openAiEfforts("o3"))
    }

    @Test
    fun romanizationPromptNamesTheSystem() {
        val zh = Prompts.romanizeSystem(Languages.get("zh-Hans"), Romanization.PINYIN)
        assertTrue(zh.contains("in Pinyin"))
        assertTrue(zh.contains("tone marks"))
        assertTrue(zh.contains("reading it has in this sentence"))
        val ja = Prompts.romanizeSystem(Languages.get("ja"), Romanization.ROMAJI)
        assertTrue(ja.contains("Hepburn"))
        assertTrue(ja.contains("no Japanese characters"))
    }

    @Test
    fun charactersPromptStaysWithTheSyllables() {
        val zh = Prompts.charactersSystem(Languages.get("zh-Hans"), Romanization.PINYIN)
        assertTrue(zh.contains("wrote text messages in Pinyin instead of Chinese (Simplified) script"))
        assertTrue(zh.contains("Stay true to the Pinyin"))
        assertTrue(zh.contains("don't add, drop or reorder words"))
        assertTrue(zh.contains("isn't Pinyin at all, return it unchanged"))
        val ja = Prompts.charactersSystem(Languages.get("ja"), Romanization.ROMAJI)
        assertTrue(ja.contains("in Japanese script"))
        // Each message is followed by the one that went out, on one line.
        assertEquals(
            "<m id=\"1\">ta1 lai2 le</m>\n<sent id=\"1\">She's here. See you</sent>\n<m id=\"2\">dui4</m>\n",
            Prompts.charactersUser(listOf("1" to "ta1 lai2 le", "2" to "dui4"), mapOf("1" to "She's here.\nSee you", "2" to " ")),
        )
    }

    @Test
    fun feedbackCorrectsIntoTheScriptTheLearnerReads() {
        val en = Languages.get("en")
        val zh = Languages.get("zh-Hans")
        // Pinyin only: corrections stay in pinyin, with the learner's tone notation.
        val roman = Prompts.feedbackSystem(en, zh, Level.BEGINNER, ScriptDisplay.ROMANIZED)
        assertTrue(roman.contains("Write \"corrected\" and \"natural\" in Pinyin too, never in Chinese (Simplified) script"))
        assertTrue(roman.contains("tone numbers (dui4)"))
        assertTrue(!roman.contains("corrected_romanized"))
        // Characters: typed pinyin is corrected into characters, which come with their pinyin.
        for (display in listOf(ScriptDisplay.BOTH, ScriptDisplay.CHARACTERS)) {
            val chars = Prompts.feedbackSystem(en, zh, Level.BEGINNER, display)
            assertTrue(chars.contains("always write \"corrected\" and \"natural\" in Chinese (Simplified) script"))
            assertTrue(chars.contains("\"corrected_romanized\""))
        }
        val ja = Prompts.feedbackSystem(en, Languages.get("ja"), Level.BEGINNER, ScriptDisplay.ROMANIZED)
        assertTrue(ja.contains("in Romaji too") && !ja.contains("tone numbers"))
        // Languages without a romanization are unchanged.
        assertEquals(Prompts.feedbackSystem(en, Languages.get("fr"), Level.BEGINNER), Prompts.feedbackSystem(en, Languages.get("fr"), Level.BEGINNER, null))
        assertTrue(!Prompts.feedbackSystem(en, Languages.get("fr"), Level.BEGINNER).contains("romaniz"))
    }

    @Test
    fun parsesRomanizedCorrections() {
        val f = Prompts.parseFeedback(
            """{"verdict":"good","corrected":"对不起","natural":"不好意思","corrected_romanized":"duìbuqǐ","natural_romanized":"bù hǎoyìsi","translation":"Sorry","notes":"- ok"}""",
        )
        assertEquals("对不起", f.corrected)
        assertEquals("duìbuqǐ", f.correctedRomanized)
        assertEquals("bù hǎoyìsi", f.naturalRomanized)
        // No natural version: no romanization for it either.
        val g = Prompts.parseFeedback("""{"verdict":"perfect","corrected":"对","natural":"对","corrected_romanized":"duì","natural_romanized":"duì"}""")
        assertEquals("", g.naturalRomanized)
        assertEquals("", Prompts.parseFeedback("""{"verdict":"perfect","corrected":"Hola"}""").correctedRomanized)
    }
}
