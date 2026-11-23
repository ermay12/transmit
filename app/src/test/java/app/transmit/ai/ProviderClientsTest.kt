package app.transmit.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Request shape and response parsing for the provider clients, against a local mock server. */
class ProviderClientsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun anthropic() = AnthropicAiClient("sk-ant-test", server.url("/").toString().trimEnd('/'))

    @Test
    fun anthropicSendsEffortAndFallbackAndParsesText() = runBlocking {
        server.enqueue(json("""
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"text","text":"<m id=\"1\">Hola</m>"}],
             "stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":120,"output_tokens":12}}
        """.trimIndent()))
        val result = anthropic().complete(CompletionRequest("claude-opus-5-5", "SYSTEM", "PROMPT", 512, Effort.MEDIUM))
        assertEquals("<m id=\"1\">Hola</m>", result.text)
        assertEquals(120L, result.inputTokens)
        assertEquals(12L, result.outputTokens)
        assertEquals("claude-opus-5-5", result.model)

        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant-test", req.getHeader("x-api-key"))
        assertTrue(req.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(512, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        assertNull("adaptive thinking is not requested for translation", body["thinking"])
        val system = body["system"]!!
        assertTrue(system.toString().contains("SYSTEM"))
        assertEquals("PROMPT", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.let {
            if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        })
    }

    @Test
    fun anthropicOmitsEffortAndFallbackWhenNotApplicable() = runBlocking {
        server.enqueue(json("""
            {"id":"msg_2","type":"message","role":"assistant","model":"claude-haiku-4-5",
             "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":5,"output_tokens":1}}
        """.trimIndent()))
        anthropic().complete(CompletionRequest("claude-haiku-4-5", "s", "p", 64, Effort.DEFAULT))
        val req = server.takeRequest()
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertNull(body["output_config"])
        assertNull(body["fallbacks"])
        assertTrue(!req.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback"))
    }

    @Test
    fun anthropicRefusalAndAuthErrorsMapToKinds() = runBlocking {
        server.enqueue(json("""
            {"id":"msg_3","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[],"stop_reason":"refusal","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":0}}
        """.trimIndent()))
        try {
            anthropic().complete(CompletionRequest("claude-opus-5-5", "s", "p", 64))
            fail("expected refusal")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.REFUSAL, e.kind)
        }
        server.enqueue(json("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""").setResponseCode(401))
        try {
            anthropic().complete(CompletionRequest("claude-opus-5-5", "s", "p", 64))
            fail("expected auth error")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.AUTH, e.kind)
        }
    }

    @Test
    fun anthropicListsModelsWithEffortCapabilities() = runBlocking {
        server.enqueue(json("""
            {"data":[
              {"id":"claude-opus-5-5","type":"model","display_name":"Claude Opus 5.5","created_at":"2026-05-01T00:00:00Z",
               "max_input_tokens":1000000,"max_tokens":128000,
               "capabilities":{"effort":{"supported":true,"low":{"supported":true},"medium":{"supported":true},
                 "high":{"supported":true},"xhigh":{"supported":true},"max":{"supported":true}}}},
              {"id":"claude-haiku-4-5","type":"model","display_name":"Claude Haiku 4.5","created_at":"2025-10-01T00:00:00Z"}
            ],"has_more":false,"first_id":"claude-opus-5-5","last_id":"claude-haiku-4-5"}
        """.trimIndent()))
        val models = anthropic().listModels()
        assertEquals(listOf("claude-opus-5-5", "claude-haiku-4-5"), models.map { it.id })
        assertEquals(listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.XHIGH, Effort.MAX), models[0].efforts)
        assertEquals(Price(4.0, 20.0), models[0].price)
        assertEquals(emptyList<Effort>(), models[1].efforts)
    }

    @Test
    fun geminiSendsThinkingBudgetAndSkipsThoughtParts() = runBlocking {
        server.enqueue(json("""
            {"candidates":[{"content":{"role":"model","parts":[
                {"text":"thinking…","thought":true},{"text":"<m id=\"1\">Bonjour</m>"}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":8,"thoughtsTokenCount":30},
             "modelVersion":"gemini-2.5-flash"}
        """.trimIndent()))
        val client = GeminiClient("AIza-test", OkHttpClient(), server.url("/v1beta").toString())
        val r = client.complete(CompletionRequest("gemini-2.5-flash", "SYS", "PROMPT", 256, Effort.LOW))
        assertEquals("<m id=\"1\">Bonjour</m>", r.text)
        assertEquals(40L, r.inputTokens)
        assertEquals(38L, r.outputTokens) // thinking tokens are billed as output
        val req = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", req.path)
        assertEquals("AIza-test", req.getHeader("x-goog-api-key"))
        val cfg = Json.parseToJsonElement(req.body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject
        assertEquals(1024, cfg["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun geminiRetriesWithoutAThinkingLevelItRejects() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"Thinking level minimal is not supported","status":"INVALID_ARGUMENT"}}"""))
        server.enqueue(json("""{"candidates":[{"content":{"parts":[{"text":"<m id=\"1\">Hi</m>"}]},"finishReason":"STOP"}]}"""))
        val client = GeminiClient("k", OkHttpClient(), server.url("/v1beta").toString())
        val r = client.complete(CompletionRequest("gemini-3.1-flash-lite", "s", "p", 64, Effort.MINIMAL))
        assertEquals("<m id=\"1\">Hi</m>", r.text)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject
        assertEquals("minimal", first["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject
        assertNull(second["thinkingConfig"])
    }

    @Test
    fun geminiSafetyBlockIsARefusal() = runBlocking {
        server.enqueue(json("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try {
            GeminiClient("k", OkHttpClient(), server.url("/v1beta").toString()).complete(CompletionRequest("gemini-2.5-flash", "s", "p", 64))
            fail("expected refusal")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.REFUSAL, e.kind)
        }
    }

    @Test
    fun openRouterReportsCostAndReasoning() = runBlocking {
        server.enqueue(json("""
            {"id":"gen-1","model":"anthropic/claude-haiku-4.5","choices":[{"index":0,"finish_reason":"stop",
              "message":{"role":"assistant","content":"hola"}}],
             "usage":{"prompt_tokens":10,"completion_tokens":2,"cost":0.00042}}
        """.trimIndent()))
        val client = OpenAiCompatClient(ProviderType.OPENROUTER, "sk-or-test", server.url("/api/v1").toString().trimEnd('/'), OkHttpClient())
        val r = client.complete(CompletionRequest("anthropic/claude-haiku-4.5", "s", "p", 64, Effort.HIGH))
        assertEquals("hola", r.text)
        assertEquals(0.00042, r.reportedCostUsd!!, 1e-12)
        val req = server.takeRequest()
        assertEquals("Bearer sk-or-test", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("true", body["usage"]!!.jsonObject["include"]!!.jsonPrimitive.content)
        assertNull(body["provider"])
    }

    @Test
    fun openRouterFastRoutingAndReasoningFallback() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Reasoning is mandatory for this endpoint and cannot be disabled.","code":400}}""").setResponseCode(400))
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":"<m id=\"1\">mdr</m>"}}],"usage":{"prompt_tokens":9,"completion_tokens":3,"cost":0.00001}}"""))
        val client = OpenAiCompatClient(ProviderType.OPENROUTER, "k", server.url("/api/v1").toString().trimEnd('/'), OkHttpClient())
        val r = client.complete(CompletionRequest("google/gemini-3.5-flash-lite", "s", "p", 64, Effort.NONE, fast = true))
        assertEquals("<m id=\"1\">mdr</m>", r.text)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("false", first["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        assertEquals("latency", first["provider"]!!.jsonObject["sort"]!!.jsonPrimitive.content)
        val retry = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNull(retry["reasoning"])
        assertEquals("latency", retry["provider"]!!.jsonObject["sort"]!!.jsonPrimitive.content)
    }

    @Test
    fun openRouterKeyLimitAndExpiryAreTellable() = runBlocking {
        val client = OpenAiCompatClient(ProviderType.OPENROUTER, "sk-or-test", server.url("/").toString().trimEnd('/'), OkHttpClient())
        val request = CompletionRequest("google/gemma-4-31b-it", "S", "P", 64)
        fun fails(code: Int, body: String): AiException {
            server.enqueue(json(body).setResponseCode(code))
            return try {
                runBlocking { client.complete(request) }
                fail("expected an error")
                throw AssertionError()
            } catch (e: AiException) {
                e
            }
        }
        fails(403, """{"error":{"code":403,"message":"Key limit exceeded (total limit)"}}""").let {
            assertEquals(AiException.Kind.KEY_LIMIT, it.kind)
            assertEquals(403, it.httpStatus)
        }
        fails(401, """{"error":{"code":401,"message":"API key expired."}}""").let {
            assertEquals(AiException.Kind.AUTH, it.kind)
            assertEquals(401, it.httpStatus)
        }
        // The account running out of credit is a service problem, not the user's allowance.
        assertEquals(AiException.Kind.OTHER, fails(402, """{"error":{"code":402,"message":"Insufficient credits"}}""").kind)
        assertEquals(AiException.Kind.AUTH, fails(403, """{"error":{"code":403,"message":"Forbidden"}}""").kind)
    }

    @Test
    fun openRouterEffortsFollowReasoningMetadata() {
        fun meta(s: String) = Json.parseToJsonElement(s).jsonObject
        val params = listOf("max_tokens", "reasoning", "include_reasoning")
        // Mandatory reasoning: no "Off".
        assertEquals(
            listOf(Effort.MINIMAL, Effort.LOW, Effort.MEDIUM, Effort.HIGH),
            OpenAiCompatClient.openRouterEfforts(params, meta("""{"mandatory":true,"supported_efforts":["high","medium","low","minimal"]}""")),
        )
        // Toggle-only reasoning (e.g. Qwen): "Off" plus the generic levels.
        assertEquals(
            listOf(Effort.NONE, Effort.MINIMAL, Effort.LOW, Effort.MEDIUM, Effort.HIGH),
            OpenAiCompatClient.openRouterEfforts(params, meta("""{"mandatory":false,"default_enabled":true}""")),
        )
        assertEquals(
            listOf(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.XHIGH, Effort.MAX),
            OpenAiCompatClient.openRouterEfforts(params, meta("""{"mandatory":false,"supported_efforts":["max","xhigh","high","medium","low","none"]}""")),
        )
        assertEquals(emptyList<Effort>(), OpenAiCompatClient.openRouterEfforts(listOf("max_tokens", "temperature"), null))
    }
}
