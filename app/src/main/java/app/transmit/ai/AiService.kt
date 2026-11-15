package app.transmit.ai

import app.transmit.data.AppSettings
import app.transmit.data.SecretStore
import app.transmit.data.SettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Builds provider clients from settings + stored keys and caches each provider's model list. */
class AiService(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    private val models = ConcurrentHashMap<ProviderType, List<ModelInfo>>()
    private val clients = ConcurrentHashMap<String, AiClient>()

    fun apiKey(provider: ProviderType): String? = secrets.get(provider.secretName)

    fun hasKey(provider: ProviderType) = !apiKey(provider).isNullOrBlank()

    fun setApiKey(provider: ProviderType, key: String?) {
        secrets.put(provider.secretName, key)
        clients.keys.removeAll { it.startsWith(provider.name) }
        models.remove(provider)
    }

    fun client(provider: ProviderType, s: AppSettings, overrideKey: String? = null): AiClient {
        val key = overrideKey ?: apiKey(provider)
            ?: throw AiException("Add your ${provider.label} API key in Settings.", AiException.Kind.NO_KEY)
        val cacheKey = "${provider.name}:${key.hashCode()}:${s.customBaseUrl}"
        if (overrideKey == null) clients[cacheKey]?.let { return it }
        val client = when (provider) {
            ProviderType.ANTHROPIC -> AnthropicAiClient(key)
            ProviderType.OPENAI -> OpenAiCompatClient(provider, key, "https://api.openai.com/v1", http)
            ProviderType.OPENROUTER -> OpenAiCompatClient(provider, key, "https://openrouter.ai/api/v1", http)
            ProviderType.CUSTOM -> {
                if (s.customBaseUrl.isBlank()) throw AiException("Set the base URL for your OpenAI-compatible provider.", AiException.Kind.NO_KEY)
                OpenAiCompatClient(provider, key, s.customBaseUrl, http)
            }
            ProviderType.GEMINI -> GeminiClient(key, http)
        }
        if (overrideKey == null) clients[cacheKey] = client
        return client
    }

    /** Validates a key by listing models; returns how many usable models it can see. */
    suspend fun testKey(provider: ProviderType, key: String): Int {
        val s = settings.current()
        val list = client(provider, s, overrideKey = key).listModels()
        models[provider] = list
        settings.cacheModels(provider, encode(list))
        return list.size
    }

    suspend fun listModels(provider: ProviderType, refresh: Boolean = false): List<ModelInfo> {
        if (!refresh) {
            models[provider]?.let { return it }
            settings.cachedModels(provider)?.let { cached ->
                decode(cached).takeIf { it.isNotEmpty() }?.let { models[provider] = it; return it }
            }
        }
        val list = client(provider, settings.current()).listModels()
        models[provider] = list
        settings.cacheModels(provider, encode(list))
        return list
    }

    suspend fun modelInfo(provider: ProviderType, id: String): ModelInfo? =
        runCatching { listModels(provider) }.getOrNull()?.firstOrNull { it.id == id }

    /** Price for a model: user override > provider-reported > built-in table. */
    suspend fun price(provider: ProviderType, model: String, s: AppSettings): Price? =
        s.priceOverride(provider, model)
            ?: models[provider]?.firstOrNull { it.id == model }?.price
            ?: Pricing.builtIn(provider, model)
            ?: modelInfo(provider, model)?.price

    private fun encode(list: List<ModelInfo>): String = buildJsonObject {
        put("v", CACHE_VERSION)
        put("models", encodeList(list))
    }.toString()

    private fun encodeList(list: List<ModelInfo>) = buildJsonArray {
        list.forEach { m ->
            add(buildJsonObject {
                put("id", m.id)
                put("name", m.name)
                m.contextWindow?.let { put("ctx", it) }
                m.maxOutput?.let { put("out", it) }
                m.price?.let { put("pin", it.inputPerM); put("pout", it.outputPerM); put("pest", it.estimated) }
                put("efforts", m.efforts.joinToString(",") { it.name })
                m.created?.let { put("created", it) }
            })
        }
    }

    /** An older cache format decodes as empty, so the list is fetched again. */
    private fun decode(json: String): List<ModelInfo> = runCatching {
        val root = Json.parseToJsonElement(json).obj()?.takeIf { it["v"].long() == CACHE_VERSION.toLong() }
        root?.get("models").arr().orEmpty().mapNotNull { it.obj() }.map { o ->
            ModelInfo(
                id = o["id"].str()!!,
                name = o["name"].str() ?: o["id"].str()!!,
                contextWindow = o["ctx"].long(),
                maxOutput = o["out"].long(),
                price = o["pin"].num()?.let { pin -> Price(pin, o["pout"].num() ?: 0.0, o["pest"].bool() ?: false) },
                efforts = o["efforts"].str().orEmpty().split(',').filter { it.isNotBlank() }
                    .mapNotNull { runCatching { Effort.valueOf(it) }.getOrNull() },
                created = o["created"].long(),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        /** Bump when cached [ModelInfo] fields change meaning (2: OpenRouter efforts from reasoning metadata). */
        const val CACHE_VERSION = 2
    }
}
