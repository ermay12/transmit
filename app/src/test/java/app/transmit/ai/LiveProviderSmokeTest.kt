package app.transmit.ai

import app.transmit.data.Languages
import app.transmit.data.Level
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Real calls to a provider with the app's own clients and prompts, for checking a provider end to end. Skipped unless
 * a key is set, so it never runs in CI. Costs a few cents. Run one provider with, for example:
 *
 *   TRANSMIT_LIVE_ANTHROPIC_KEY=… ./gradlew :app:testDebugUnitTest --tests '*LiveProviderSmokeTest*'
 *
 * The messages are made up; never put real ones here.
 */
class LiveProviderSmokeTest {
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()

    private val texts = listOf(
        "1" to "t'es dispo ce soir ? on se fait un resto vers 20h",
        "2" to "mdr j'avoue, trop hâte de te voir 😂",
        "3" to "ok je te tiens au courant",
    )

    private fun key(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private suspend fun AiClient.check(provider: ProviderType, translateModel: String, tutorModel: String) {
        val models = listModels()
        println("$provider: ${models.size} models")
        listOf(translateModel, tutorModel).forEach { id ->
            val info = models.firstOrNull { it.id == id }
            println("  $id listed=${info != null} efforts=${info?.efforts} price=${Pricing.builtIn(provider, id)}")
            assertTrue("$id should be listed", info != null)
        }
        val translateEffort = models.first { it.id == translateModel }.efforts.minOrNull() ?: Effort.DEFAULT
        val t = timed {
            complete(CompletionRequest(translateModel, Prompts.translateSystem(Languages.get("en")), Prompts.translateUser(texts, emptyList()), 4096, translateEffort, fast = true))
        }
        val parsed = Prompts.parseTranslations(t.first.text, texts.map { it.first })
        println("  translate $translateModel @$translateEffort: ${t.second} ms, ${t.first.inputTokens} in / ${t.first.outputTokens} out")
        parsed.forEach { (id, text) -> println("    $id: $text") }
        assertTrue("all three translated", parsed.keys == texts.map { it.first }.toSet())

        val tutorEffort = models.first { it.id == tutorModel }.efforts.let { if (Effort.MEDIUM in it) Effort.MEDIUM else Effort.DEFAULT }
        val e = timed {
            complete(CompletionRequest(
                tutorModel,
                Prompts.tutorSystem(Languages.get("en"), Languages.get("fr"), Level.BEGINNER),
                Prompts.explainUser(texts[0].second, parsed["1"].orEmpty(), Languages.get("en")),
                8000, tutorEffort,
            ))
        }
        println("  explain $tutorModel @$tutorEffort: ${e.second} ms, ${e.first.inputTokens} in / ${e.first.outputTokens} out, ${e.first.text.length} chars")
        assertTrue("explanation not empty", e.first.text.isNotBlank())

        val f = timed {
            complete(CompletionRequest(
                tutorModel,
                Prompts.feedbackSystem(Languages.get("en"), Languages.get("fr"), Level.BEGINNER),
                Prompts.feedbackUser("I'm free tonight", "je suis libre ce soir", Languages.get("en"), Languages.get("fr")),
                8000, tutorEffort,
            ))
        }
        val feedback = Prompts.parseFeedback(f.first.text)
        println("  feedback $tutorModel @$tutorEffort: ${f.second} ms, parsed=$feedback")
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun anthropic() = runBlocking {
        val key = key("TRANSMIT_LIVE_ANTHROPIC_KEY")
        assumeTrue("no Anthropic key", key != null)
        val client = AnthropicAiClient(key!!)
        val translate = System.getenv("TRANSMIT_LIVE_TRANSLATE_MODEL") ?: "claude-haiku-5-5"
        val tutor = System.getenv("TRANSMIT_LIVE_TUTOR_MODEL") ?: "claude-opus-5-5"
        client.check(ProviderType.ANTHROPIC, translate, tutor)
    }

    @Test
    fun openAi() = runBlocking {
        val key = key("TRANSMIT_LIVE_OPENAI_KEY")
        assumeTrue("no OpenAI key", key != null)
        OpenAiCompatClient(ProviderType.OPENAI, key!!, "https://api.openai.com/v1", http).check(ProviderType.OPENAI, "gpt-6-luna", "gpt-6.2-sol")
    }

    @Test
    fun gemini() = runBlocking {
        val key = key("TRANSMIT_LIVE_GEMINI_KEY")
        assumeTrue("no Gemini key", key != null)
        GeminiClient(key!!, http).check(ProviderType.GEMINI, "gemini-3.1-flash-lite", "gemini-3.8-flash")
    }
}
