package app.transmit.ai

enum class ProviderType(val label: String, val keyHint: String, val keyUrl: String) {
    ANTHROPIC("Anthropic", "sk-ant-…", "https://console.anthropic.com/settings/keys"),
    OPENAI("OpenAI", "sk-…", "https://platform.openai.com/api-keys"),
    GEMINI("Google Gemini", "AIza…", "https://aistudio.google.com/apikey"),
    OPENROUTER("OpenRouter", "sk-or-…", "https://openrouter.ai/keys"),
    CUSTOM("OpenAI-compatible", "API key", ""),
    ;

    val secretName get() = "api_key_$name"
}

/** Reasoning effort. [DEFAULT] sends nothing and lets the provider decide. */
enum class Effort(val wire: String, val label: String) {
    DEFAULT("", "Auto"),
    NONE("none", "Off"),
    MINIMAL("minimal", "Min"),
    LOW("low", "Low"),
    MEDIUM("medium", "Med"),
    HIGH("high", "High"),
    XHIGH("xhigh", "X-High"),
    MAX("max", "Max"),
}

/** USD per million tokens. [estimated] marks built-in guesses the user may want to correct. */
data class Price(val inputPerM: Double, val outputPerM: Double, val estimated: Boolean = false) {
    fun cost(inputTokens: Long, outputTokens: Long) = (inputTokens * inputPerM + outputTokens * outputPerM) / 1_000_000.0
}

data class ModelInfo(
    val id: String,
    val name: String,
    val contextWindow: Long? = null,
    val maxOutput: Long? = null,
    val price: Price? = null,
    /** Supported effort levels besides [Effort.DEFAULT]. */
    val efforts: List<Effort> = emptyList(),
    val created: Long? = null,
)

data class CompletionRequest(
    val model: String,
    val system: String,
    val prompt: String,
    val maxTokens: Int,
    val effort: Effort = Effort.DEFAULT,
    /** Latency matters more than price: OpenRouter routes to whichever provider is answering fastest. */
    val fast: Boolean = false,
)

data class CompletionResult(
    val text: String,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Exact cost when the provider reports it (OpenRouter). */
    val reportedCostUsd: Double? = null,
    val model: String,
)

class AiException(message: String, val kind: Kind, cause: Throwable? = null, val httpStatus: Int? = null) : Exception(message, cause) {
    /** [KEY_LIMIT]: OpenRouter's per-key spending limit is reached (403 "Key limit exceeded"). */
    enum class Kind { NO_KEY, NO_MODEL, AUTH, KEY_LIMIT, RATE_LIMIT, NETWORK, REFUSAL, BAD_REQUEST, BUDGET, OTHER }
}

interface AiClient {
    suspend fun listModels(): List<ModelInfo>
    suspend fun complete(request: CompletionRequest): CompletionResult
}
