package app.transmit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.transmit.ai.Effort
import app.transmit.ai.Price
import app.transmit.ai.ProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class NotificationContent(val label: String) { TRANSLATED("Translation"), ORIGINAL("Original"), HIDDEN("Hidden") }

/** Which model slot a setting applies to: cheap auto-translation vs. the tutor (explain / feedback). */
enum class ModelSlot { TRANSLATE, TUTOR }

data class ChatDefaults(val autoTranslate: Boolean, val monthlyBudgetUsd: Double)

data class AppSettings(
    val onboarded: Boolean = false,
    val nativeLang: String = "en",
    val targetLang: String = "es",
    val level: Level = Level.BEGINNER,
    val provider: ProviderType = ProviderType.OPENROUTER,
    val translateModel: String? = null,
    /** "Auto" resolves to the model's fastest setting for translation (see Tutor.effortFor). */
    val translateEffort: Effort = Effort.DEFAULT,
    val tutorModel: String? = null,
    val tutorEffort: Effort = Effort.MEDIUM,
    val customBaseUrl: String = "",
    val monthlyBudgetUsd: Double = 10.0,
    val contactDefaults: ChatDefaults = ChatDefaults(autoTranslate = true, monthlyBudgetUsd = 10.0),
    val unknownDefaults: ChatDefaults = ChatDefaults(autoTranslate = true, monthlyBudgetUsd = 2.0),
    val contextMessages: Int = 8,
    val translateOutgoing: Boolean = true,
    val backfillCount: Int = 4,
    val notificationContent: NotificationContent = NotificationContent.TRANSLATED,
    val deliveryReports: Boolean = false,
    val priceOverrides: Map<String, Price> = emptyMap(),
    /** Per romanization; [ScriptDisplay.BOTH] when unset. */
    val scriptDisplays: Map<Romanization, ScriptDisplay> = emptyMap(),
) {
    val native get() = Languages.get(nativeLang)
    val target get() = Languages.get(targetLang)

    fun model(slot: ModelSlot) = when (slot) {
        ModelSlot.TRANSLATE -> translateModel
        ModelSlot.TUTOR -> tutorModel ?: translateModel
    }

    fun effort(slot: ModelSlot) = when (slot) {
        ModelSlot.TRANSLATE -> translateEffort
        ModelSlot.TUTOR -> tutorEffort
    }

    fun priceOverride(provider: ProviderType, model: String) = priceOverrides["${provider.name}:$model"]

    /** How [lang] is shown; null for languages without a romanization. */
    fun scriptDisplay(lang: Language): ScriptDisplay? = lang.romanization?.let { scriptDisplays[it] ?: ScriptDisplay.BOTH }

    /** Whether [lang] text gets romanized as it's translated, rather than only when asked. */
    fun romanizesAutomatically(lang: Language): Boolean = scriptDisplay(lang).let { it != null && it != ScriptDisplay.CHARACTERS }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val nativeLang = stringPreferencesKey("native_lang")
        val targetLang = stringPreferencesKey("target_lang")
        val level = stringPreferencesKey("level")
        val provider = stringPreferencesKey("provider")
        fun model(p: ProviderType, slot: ModelSlot) = stringPreferencesKey("model_${slot.name}_${p.name}")
        fun effort(p: ProviderType, slot: ModelSlot) = stringPreferencesKey("effort_${slot.name}_${p.name}")
        val customBaseUrl = stringPreferencesKey("custom_base_url")
        val monthlyBudget = doublePreferencesKey("monthly_budget")
        val contactAuto = booleanPreferencesKey("contact_auto")
        val contactBudget = doublePreferencesKey("contact_budget")
        val unknownAuto = booleanPreferencesKey("unknown_auto")
        val unknownBudget = doublePreferencesKey("unknown_budget")
        val contextMessages = intPreferencesKey("context_messages")
        val translateOutgoing = booleanPreferencesKey("translate_outgoing")
        val backfill = intPreferencesKey("backfill")
        val notificationContent = stringPreferencesKey("notification_content")
        val deliveryReports = booleanPreferencesKey("delivery_reports")
        val prices = stringPreferencesKey("price_overrides")
        val recentLangs = stringPreferencesKey("recent_langs")
        fun scriptDisplay(r: Romanization) = stringPreferencesKey("script_display_${r.name}")
        fun modelCache(p: ProviderType) = stringPreferencesKey("model_cache_${p.name}")
    }

    private val defaults = AppSettings(nativeLang = Languages.deviceDefault().code).let {
        if (it.nativeLang == it.targetLang) it.copy(targetLang = "en") else it
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> read(p) }

    suspend fun current(): AppSettings = settings.first()

    /**
     * Language codes picked in any language picker, most recent first. Until the first pick, the
     * languages the user set up with.
     */
    val recentLanguages: Flow<List<String>> = context.dataStore.data.map(::recentLanguages).distinctUntilChanged()

    private fun recentLanguages(p: Preferences): List<String> =
        p[K.recentLangs]?.split(',')?.filter { it.isNotEmpty() }
            ?: listOf(p[K.targetLang] ?: defaults.targetLang, p[K.nativeLang] ?: defaults.nativeLang)

    suspend fun noteLanguageUsed(code: String) = edit { p ->
        p[K.recentLangs] = Languages.used(recentLanguages(p), code).joinToString(",")
    }

    private fun read(p: Preferences): AppSettings {
        val provider = p[K.provider]?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() } ?: defaults.provider
        // The tutor starts on a moderate effort where the provider's levels are known, else on its default.
        val knowsEffort = provider == ProviderType.ANTHROPIC || provider == ProviderType.OPENAI || provider == ProviderType.GEMINI
        fun effort(slot: ModelSlot, fallback: Effort) =
            p[K.effort(provider, slot)]?.let { runCatching { Effort.valueOf(it) }.getOrNull() } ?: if (knowsEffort) fallback else Effort.DEFAULT
        return AppSettings(
            onboarded = p[K.onboarded] ?: false,
            nativeLang = p[K.nativeLang] ?: defaults.nativeLang,
            targetLang = p[K.targetLang] ?: defaults.targetLang,
            level = p[K.level]?.let { runCatching { Level.valueOf(it) }.getOrNull() } ?: defaults.level,
            provider = provider,
            translateModel = p[K.model(provider, ModelSlot.TRANSLATE)] ?: defaultModel(provider, ModelSlot.TRANSLATE),
            translateEffort = effort(ModelSlot.TRANSLATE, defaults.translateEffort),
            tutorModel = p[K.model(provider, ModelSlot.TUTOR)] ?: defaultModel(provider, ModelSlot.TUTOR),
            tutorEffort = effort(ModelSlot.TUTOR, defaults.tutorEffort),
            customBaseUrl = p[K.customBaseUrl] ?: "",
            monthlyBudgetUsd = p[K.monthlyBudget] ?: defaults.monthlyBudgetUsd,
            contactDefaults = ChatDefaults(p[K.contactAuto] ?: defaults.contactDefaults.autoTranslate, p[K.contactBudget] ?: defaults.contactDefaults.monthlyBudgetUsd),
            unknownDefaults = ChatDefaults(p[K.unknownAuto] ?: defaults.unknownDefaults.autoTranslate, p[K.unknownBudget] ?: defaults.unknownDefaults.monthlyBudgetUsd),
            contextMessages = p[K.contextMessages] ?: defaults.contextMessages,
            translateOutgoing = p[K.translateOutgoing] ?: defaults.translateOutgoing,
            backfillCount = p[K.backfill] ?: defaults.backfillCount,
            notificationContent = p[K.notificationContent]?.let { runCatching { NotificationContent.valueOf(it) }.getOrNull() } ?: defaults.notificationContent,
            deliveryReports = p[K.deliveryReports] ?: defaults.deliveryReports,
            priceOverrides = p[K.prices]?.let(::decodePrices).orEmpty(),
            scriptDisplays = Romanization.entries.mapNotNull { r ->
                p[K.scriptDisplay(r)]?.let { v -> runCatching { r to ScriptDisplay.valueOf(v) }.getOrNull() }
            }.toMap(),
        )
    }

    /** Sensible starting models per provider; the user can pick any listed model. */
    private fun defaultModel(provider: ProviderType, slot: ModelSlot): String? = when (provider) {
        ProviderType.ANTHROPIC -> "claude-opus-5-5"
        ProviderType.OPENAI -> if (slot == ModelSlot.TRANSLATE) "gpt-5-mini" else "gpt-5"
        ProviderType.GEMINI -> if (slot == ModelSlot.TRANSLATE) "gemini-2.5-flash" else "gemini-2.5-pro"
        ProviderType.OPENROUTER -> "google/gemma-4-31b-it"
        ProviderType.CUSTOM -> null
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setOnboarded(value: Boolean) = edit { it[K.onboarded] = value }
    suspend fun setLanguages(native: String, target: String) = edit {
        it[K.nativeLang] = native
        it[K.targetLang] = target
    }
    suspend fun setNativeLang(code: String) = edit { it[K.nativeLang] = code }
    suspend fun setTargetLang(code: String) = edit { it[K.targetLang] = code }
    suspend fun setLevel(level: Level) = edit { it[K.level] = level.name }
    suspend fun setProvider(provider: ProviderType) = edit { it[K.provider] = provider.name }
    suspend fun setModel(provider: ProviderType, slot: ModelSlot, model: String) = edit { it[K.model(provider, slot)] = model }
    suspend fun setEffort(provider: ProviderType, slot: ModelSlot, effort: Effort) = edit { it[K.effort(provider, slot)] = effort.name }
    suspend fun setCustomBaseUrl(url: String) = edit { it[K.customBaseUrl] = url.trim() }
    suspend fun setMonthlyBudget(usd: Double) = edit { it[K.monthlyBudget] = usd }
    suspend fun setContactDefaults(d: ChatDefaults) = edit {
        it[K.contactAuto] = d.autoTranslate
        it[K.contactBudget] = d.monthlyBudgetUsd
    }
    suspend fun setUnknownDefaults(d: ChatDefaults) = edit {
        it[K.unknownAuto] = d.autoTranslate
        it[K.unknownBudget] = d.monthlyBudgetUsd
    }
    suspend fun setContextMessages(n: Int) = edit { it[K.contextMessages] = n }
    suspend fun setTranslateOutgoing(v: Boolean) = edit { it[K.translateOutgoing] = v }
    suspend fun setBackfill(n: Int) = edit { it[K.backfill] = n }
    suspend fun setNotificationContent(v: NotificationContent) = edit { it[K.notificationContent] = v.name }
    suspend fun setDeliveryReports(v: Boolean) = edit { it[K.deliveryReports] = v }
    suspend fun setScriptDisplay(r: Romanization, v: ScriptDisplay) = edit { it[K.scriptDisplay(r)] = v.name }

    suspend fun setPriceOverride(provider: ProviderType, model: String, price: Price?) = edit { p ->
        val map = p[K.prices]?.let(::decodePrices).orEmpty().toMutableMap()
        val key = "${provider.name}:$model"
        if (price == null) map.remove(key) else map[key] = price
        p[K.prices] = encodePrices(map)
    }

    suspend fun cachedModels(provider: ProviderType): String? = context.dataStore.data.first()[K.modelCache(provider)]
    suspend fun cacheModels(provider: ProviderType, json: String) = edit { it[K.modelCache(provider)] = json }

    private fun encodePrices(map: Map<String, Price>): String = buildJsonObject {
        map.forEach { (k, v) -> put(k, buildJsonObject { put("in", v.inputPerM); put("out", v.outputPerM) }) }
    }.toString()

    private fun decodePrices(json: String): Map<String, Price> = runCatching {
        Json.parseToJsonElement(json).jsonObject.mapValues { (_, v) ->
            val o = v as JsonObject
            Price(o["in"]!!.jsonPrimitive.double, o["out"]!!.jsonPrimitive.double)
        }
    }.getOrDefault(emptyMap())
}
