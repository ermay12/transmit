package app.transmit.ai

import app.transmit.data.Languages
import app.transmit.data.Level
import app.transmit.data.ModelSlot
import app.transmit.data.Romanization
import app.transmit.data.ScriptDisplay
import app.transmit.data.StartingModels
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
        val t = Prompts.translateUser(listOf("1" to "hi"), listOf(ContextLine(true, "yo"), ContextLine(false, "hey"), ContextLine(false, "sup", person = 2)))
        assertTrue(t.contains("Me: yo"))
        assertTrue(t.contains("Them: hey"))
        assertTrue(t.contains("Person 2: sup"))
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
        // Newer models get their own price, not an older family's through the prefix match.
        assertEquals(Price(8.0, 40.0), Pricing.builtIn(ProviderType.ANTHROPIC, "claude-fable-5-2"))
        assertEquals(0.10, Pricing.builtIn(ProviderType.OPENAI, "gpt-6-luna")!!.inputPerM, 1e-9)
        assertEquals(0.75, Pricing.builtIn(ProviderType.OPENAI, "gpt-5.4-mini-2026-03-17")!!.inputPerM, 1e-9)
        assertEquals(0.25, Pricing.builtIn(ProviderType.GEMINI, "gemini-3.1-flash-lite")!!.inputPerM, 1e-9)
        assertEquals(0.30, Pricing.builtIn(ProviderType.GEMINI, "models/gemini-3.5-flash-lite")!!.inputPerM, 1e-9)
    }

    @Test
    fun everyStartingModelHasABuiltInPrice() {
        for (p in listOf(ProviderType.ANTHROPIC, ProviderType.OPENAI, ProviderType.GEMINI)) {
            for (slot in ModelSlot.entries) {
                StartingModels.candidates(p, slot).forEach { assertTrue("$p $it", Pricing.builtIn(p, it) != null) }
            }
        }
    }

    @Test
    fun startingModelFallsBackToOneTheKeyCanSee() {
        val gemini = setOf("gemini-3.5-flash-lite", "gemini-3.6-flash")
        assertEquals("gemini-3.5-flash-lite", StartingModels.pick(ProviderType.GEMINI, ModelSlot.TRANSLATE, gemini))
        assertEquals("gemini-3.6-flash", StartingModels.pick(ProviderType.GEMINI, ModelSlot.TUTOR, gemini))
        assertEquals(null, StartingModels.pick(ProviderType.OPENAI, ModelSlot.TRANSLATE, setOf("gpt-4o")))
        assertEquals("claude-haiku-5-5", StartingModels.candidates(ProviderType.ANTHROPIC, ModelSlot.TRANSLATE).first())
    }

    @Test
    fun openAiModelFilter() {
        assertTrue(OpenAiCompatClient.isOpenAiChatModel("gpt-5-mini"))
        assertTrue(OpenAiCompatClient.isOpenAiChatModel("o4-mini"))
        assertTrue(!OpenAiCompatClient.isOpenAiChatModel("gpt-4o-realtime-preview"))
        assertTrue(!OpenAiCompatClient.isOpenAiChatModel("text-embedding-3-small"))
        assertEquals(listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH), OpenAiCompatClient.openAiEfforts("o3"))
        // Responses-only and trusted-access models fail on chat completions.
        assertTrue(!OpenAiCompatClient.isOpenAiChatModel("gpt-5-pro"))
        assertTrue(!OpenAiCompatClient.isOpenAiChatModel("gpt-6.1-terra-pro"))
        assertTrue(!OpenAiCompatClient.isOpenAiChatModel("gpt-6-cyber"))
        assertTrue(OpenAiCompatClient.isOpenAiChatModel("gpt-6-luna"))
        val all = listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.XHIGH, Effort.MAX)
        assertEquals(listOf(Effort.NONE) + all, OpenAiCompatClient.openAiEfforts("gpt-6-luna"))
        assertEquals(all, OpenAiCompatClient.openAiEfforts("gpt-6.2-sol"))
    }

    @Test
    fun geminiThinkingLevels() {
        val levels = listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH)
        assertEquals(listOf(Effort.MINIMAL) + levels, GeminiClient.geminiEfforts("gemini-3.1-flash-lite"))
        assertEquals(levels, GeminiClient.geminiEfforts("gemini-3.8-flash"))
        assertEquals(levels, GeminiClient.geminiEfforts("gemini-3.1-pro-preview"))
        assertEquals(levels, GeminiClient.geminiEfforts("gemini-4-hydrogen"))
        assertEquals(listOf(Effort.NONE) + levels, GeminiClient.geminiEfforts("gemini-2.5-flash-lite"))
        fun level(model: String, e: Effort) = GeminiClient.thinkingConfig(model, e)?.get("thinkingLevel")?.toString()?.trim('"')
        assertEquals("minimal", level("gemini-3.1-flash-lite", Effort.MINIMAL))
        assertEquals("medium", level("gemini-3.8-flash", Effort.MEDIUM))
        assertEquals("high", level("gemini-4-hydrogen", Effort.XHIGH))
        assertEquals(null, GeminiClient.thinkingConfig("gemini-3.8-flash", Effort.DEFAULT))
        assertEquals("1024", GeminiClient.thinkingConfig("gemini-2.5-flash-lite", Effort.LOW)?.get("thinkingBudget")?.toString())
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
