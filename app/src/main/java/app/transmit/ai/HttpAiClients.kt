package app.transmit.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
internal fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

/** Executes [request] and returns the parsed JSON body, mapping HTTP failures to [AiException]. */
internal fun OkHttpClient.json(request: Request, providerName: String): JsonObject {
    val response = try {
        newCall(request).execute()
    } catch (e: IOException) {
        throw AiException("Network error talking to $providerName.", AiException.Kind.NETWORK, e)
    }
    response.use { r ->
        val body = r.body?.string().orEmpty()
        val parsed = runCatching { Json.parseToJsonElement(body).obj() }.getOrNull()
        if (!r.isSuccessful) {
            val message = parsed?.get("error")?.let { it.obj()?.get("message").str() ?: it.str() }
                ?: body.take(200).ifBlank { "HTTP ${r.code}" }
            val kind = when (r.code) {
                401 -> AiException.Kind.AUTH
                403 -> if (message.contains("key limit exceeded", ignoreCase = true)) AiException.Kind.KEY_LIMIT else AiException.Kind.AUTH
                429 -> AiException.Kind.RATE_LIMIT
                400, 404, 422 -> AiException.Kind.BAD_REQUEST
                else -> AiException.Kind.OTHER
            }
            throw AiException("$providerName: $message", kind, httpStatus = r.code)
        }
        return parsed ?: throw AiException("$providerName returned an unreadable response.", AiException.Kind.OTHER)
    }
}

/** OpenAI, OpenRouter and any OpenAI-compatible endpoint (chat completions API). */
class OpenAiCompatClient(
    private val type: ProviderType,
    private val apiKey: String,
    baseUrl: String,
    private val http: OkHttpClient,
) : AiClient {
    private val base = baseUrl.trimEnd('/')

    private fun request(path: String) = Request.Builder()
        .url("$base$path")
        .header("Authorization", "Bearer $apiKey")
        .apply { if (type == ProviderType.OPENROUTER) header("X-Title", "Transmit") }

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val root = http.json(request("/models").get().build(), type.label)
        root["data"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { m ->
            val id = m["id"].str() ?: return@mapNotNull null
            when (type) {
                ProviderType.OPENROUTER -> {
                    val pricing = m["pricing"].obj()
                    val prompt = pricing?.get("prompt").num()
                    val completion = pricing?.get("completion").num()
                    val params = m["supported_parameters"].arr().orEmpty().mapNotNull { it.str() }
                    ModelInfo(
                        id = id,
                        name = m["name"].str() ?: id,
                        contextWindow = m["context_length"].long(),
                        maxOutput = m["top_provider"].obj()?.get("max_completion_tokens").long(),
                        price = if (prompt != null && completion != null && prompt >= 0 && completion >= 0) Price(prompt * 1e6, completion * 1e6) else null,
                        efforts = openRouterEfforts(params, m["reasoning"].obj()),
                        created = m["created"].long(),
                    )
                }
                ProviderType.OPENAI -> {
                    if (!isOpenAiChatModel(id)) return@mapNotNull null
                    ModelInfo(id = id, name = id, price = Pricing.builtIn(type, id), efforts = openAiEfforts(id), created = m["created"].long())
                }
                else -> ModelInfo(id = id, name = m["name"].str() ?: id, contextWindow = m["context_length"].long(), created = m["created"].long())
            }
        }.sortedByDescending { it.created ?: 0 }
    }

    override suspend fun complete(request: CompletionRequest): CompletionResult = withContext(Dispatchers.IO) {
        try {
            send(request)
        } catch (e: AiException) {
            // Some models reject a reasoning setting they advertise (e.g. "off"); fall back to their default.
            if (e.kind != AiException.Kind.BAD_REQUEST || request.effort == Effort.DEFAULT) throw e
            send(request.copy(effort = Effort.DEFAULT))
        }
    }

    private fun send(request: CompletionRequest): CompletionResult {
        val body = buildJsonObject {
            put("model", request.model)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", request.system) })
                add(buildJsonObject { put("role", "user"); put("content", request.prompt) })
            }
            when (type) {
                ProviderType.OPENAI -> {
                    put("max_completion_tokens", request.maxTokens)
                    if (request.effort != Effort.DEFAULT) put("reasoning_effort", request.effort.wire)
                }
                ProviderType.OPENROUTER -> {
                    put("max_tokens", request.maxTokens)
                    if (request.effort == Effort.NONE) {
                        putJsonObject("reasoning") { put("enabled", false) }
                    } else if (request.effort != Effort.DEFAULT) {
                        putJsonObject("reasoning") { put("effort", request.effort.wire) }
                    }
                    if (request.fast) putJsonObject("provider") { put("sort", "latency") }
                    putJsonObject("usage") { put("include", true) }
                }
                else -> {
                    put("max_tokens", request.maxTokens)
                    if (request.effort != Effort.DEFAULT) put("reasoning_effort", request.effort.wire)
                }
            }
        }
        val root = http.json(request("/chat/completions").post(body.toString().toRequestBody(JSON_MEDIA)).build(), type.label)
        val choice = root["choices"].arr()?.firstOrNull().obj()
        if (choice?.get("finish_reason").str() == "content_filter") {
            throw AiException("The model's safety filter blocked this message.", AiException.Kind.REFUSAL)
        }
        val message = choice?.get("message").obj()
        message?.get("refusal").str()?.let { throw AiException(it, AiException.Kind.REFUSAL) }
        val content = message?.get("content").let { c ->
            c.str() ?: c.arr()?.mapNotNull { it.obj()?.get("text").str() }?.joinToString("")
        }.orEmpty()
        val usage = root["usage"].obj()
        return CompletionResult(
            text = content.trim(),
            inputTokens = usage?.get("prompt_tokens").long() ?: 0,
            outputTokens = usage?.get("completion_tokens").long() ?: 0,
            reportedCostUsd = if (type == ProviderType.OPENROUTER) usage?.get("cost").num() else null,
            model = root["model"].str() ?: request.model,
        )
    }

    companion object {
        /**
         * Effort levels from OpenRouter's per-model reasoning metadata, plus "Off" unless the model
         * can't disable reasoning. Models that report reasoning without levels get the generic set.
         */
        fun openRouterEfforts(params: List<String>, reasoning: JsonObject?): List<Effort> {
            if ("reasoning" !in params && "include_reasoning" !in params && reasoning == null) return emptyList()
            val listed = reasoning?.get("supported_efforts").arr().orEmpty()
                .mapNotNull { e -> Effort.entries.firstOrNull { it != Effort.DEFAULT && it.wire == e.str() } }
            val levels = listed.ifEmpty { listOf(Effort.MINIMAL, Effort.LOW, Effort.MEDIUM, Effort.HIGH) }
            val canDisable = reasoning?.get("mandatory").bool() != true
            return (if (canDisable) levels + Effort.NONE else levels - Effort.NONE).distinct().sorted()
        }

        /** Not chat models, or not on the chat completions API ("-pro" is Responses-only; "cyber" needs trusted access). */
        private val excluded = listOf(
            "audio", "realtime", "transcribe", "tts", "image", "search", "embedding", "instruct",
            "dall-e", "whisper", "moderation", "codex", "computer-use", "deep-research", "babbage", "davinci",
            "-pro", "cyber",
        )

        fun isOpenAiChatModel(id: String): Boolean {
            val lower = id.lowercase()
            val family = lower.startsWith("gpt-") || lower.startsWith("chatgpt-") || Regex("^o\\d").containsMatchIn(lower)
            return family && excluded.none { lower.contains(it) }
        }

        fun openAiEfforts(id: String): List<Effort> {
            val lower = id.lowercase()
            return when {
                Regex("^o\\d").containsMatchIn(lower) -> listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH)
                // Luna and Terra can turn reasoning off; Sol and Astra can't.
                lower.startsWith("gpt-6") ->
                    (if (lower.contains("luna") || lower.contains("terra")) listOf(Effort.NONE) else emptyList()) +
                        listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.XHIGH, Effort.MAX)
                lower.startsWith("gpt-5") && lower.contains("chat") -> emptyList()
                lower.startsWith("gpt-5.") -> listOf(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH)
                lower.startsWith("gpt-5") -> listOf(Effort.MINIMAL, Effort.LOW, Effort.MEDIUM, Effort.HIGH)
                else -> emptyList()
            }
        }
    }
}

/** Google Gemini via the Generative Language REST API. */
class GeminiClient(
    private val apiKey: String,
    private val http: OkHttpClient,
    private val base: String = "https://generativelanguage.googleapis.com/v1beta",
) : AiClient {

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ModelInfo>()
        var pageToken: String? = null
        do {
            val url = "$base/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val root = http.json(Request.Builder().url(url).header("x-goog-api-key", apiKey).get().build(), "Gemini")
            root["models"].arr().orEmpty().mapNotNull { it.obj() }.forEach { m ->
                val id = m["name"].str()?.removePrefix("models/") ?: return@forEach
                val methods = m["supportedGenerationMethods"].arr().orEmpty().mapNotNull { it.str() }
                if ("generateContent" !in methods || !isGeminiChatModel(id)) return@forEach
                val thinking = m["thinking"].bool() == true
                out += ModelInfo(
                    id = id,
                    name = m["displayName"].str() ?: id,
                    contextWindow = m["inputTokenLimit"].long(),
                    maxOutput = m["outputTokenLimit"].long(),
                    price = Pricing.builtIn(ProviderType.GEMINI, id),
                    efforts = if (thinking) geminiEfforts(id) else emptyList(),
                )
            }
            pageToken = root["nextPageToken"].str()
        } while (!pageToken.isNullOrBlank())
        out
    }

    override suspend fun complete(request: CompletionRequest): CompletionResult = withContext(Dispatchers.IO) {
        try {
            send(request)
        } catch (e: AiException) {
            // A thinking level the model doesn't take (levels are inferred from its name); fall back to its default.
            if (e.kind != AiException.Kind.BAD_REQUEST || request.effort == Effort.DEFAULT) throw e
            send(request.copy(effort = Effort.DEFAULT))
        }
    }

    private fun send(request: CompletionRequest): CompletionResult {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", request.system) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", request.prompt) }) })
                })
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", request.maxTokens)
                thinkingConfig(request.model, request.effort)?.let { put("thinkingConfig", it) }
            }
        }
        val req = Request.Builder()
            .url("$base/models/${request.model}:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val root = http.json(req, "Gemini")
        root["promptFeedback"].obj()?.get("blockReason").str()?.let {
            throw AiException("Gemini blocked this message ($it).", AiException.Kind.REFUSAL)
        }
        val candidate = root["candidates"].arr()?.firstOrNull().obj()
        val finish = candidate?.get("finishReason").str()
        if (finish in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")) {
            throw AiException("Gemini blocked this message ($finish).", AiException.Kind.REFUSAL)
        }
        val text = candidate?.get("content").obj()?.get("parts").arr().orEmpty()
            .mapNotNull { it.obj() }
            .filter { it["thought"].bool() != true }
            .mapNotNull { it["text"].str() }
            .joinToString("")
        val usage = root["usageMetadata"].obj()
        return CompletionResult(
            text = text.trim(),
            inputTokens = usage?.get("promptTokenCount").long() ?: 0,
            outputTokens = (usage?.get("candidatesTokenCount").long() ?: 0) + (usage?.get("thoughtsTokenCount").long() ?: 0),
            model = root["modelVersion"].str() ?: request.model,
        )
    }

    companion object {
        fun isGeminiChatModel(id: String): Boolean {
            val lower = id.lowercase()
            return (lower.startsWith("gemini") || lower.startsWith("gemma")) &&
                listOf("embedding", "image", "tts", "live", "audio", "robotics", "computer-use", "aqa").none { lower.contains(it) }
        }

        /** Gemini 3 and later take a thinking level; 2.x a thinking budget in tokens. */
        private fun takesThinkingLevel(id: String) = Regex("^gemini-([3-9]|\\d{2,})").containsMatchIn(id)

        fun geminiEfforts(id: String): List<Effort> = when {
            takesThinkingLevel(id) -> {
                // "Minimal" is near-off thinking, the fastest. Pro, 3.8 Flash and Gemini 4 don't take it.
                val minimal = id.startsWith("gemini-3") && !id.startsWith("gemini-3.8") && !id.contains("pro")
                (if (minimal) listOf(Effort.MINIMAL) else emptyList()) + listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH)
            }
            id.contains("pro") -> listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH)
            else -> listOf(Effort.NONE, Effort.LOW, Effort.MEDIUM, Effort.HIGH)
        }

        fun thinkingConfig(model: String, effort: Effort): JsonObject? {
            if (effort == Effort.DEFAULT) return null
            if (takesThinkingLevel(model)) {
                val level = when (effort) {
                    Effort.NONE, Effort.MINIMAL -> "minimal"
                    Effort.LOW -> "low"
                    Effort.MEDIUM -> "medium"
                    else -> "high"
                }
                return buildJsonObject { put("thinkingLevel", level) }
            }
            val budget = when (effort) {
                Effort.NONE -> 0
                Effort.MINIMAL -> 512
                Effort.LOW -> 1024
                Effort.MEDIUM -> 8192
                else -> 24576
            }
            return buildJsonObject { put("thinkingBudget", budget) }
        }
    }
}
