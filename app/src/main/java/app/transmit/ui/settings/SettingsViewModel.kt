package app.transmit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.transmit.AppContainer
import app.transmit.ai.AiException
import app.transmit.ai.Effort
import app.transmit.ai.ModelInfo
import app.transmit.ai.Price
import app.transmit.ai.ProviderType
import app.transmit.data.AppSettings
import app.transmit.data.ChatDefaults
import app.transmit.data.Level
import app.transmit.data.ModelSlot
import app.transmit.data.NotificationContent
import app.transmit.data.Romanization
import app.transmit.data.ScriptDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Ok(val models: Int) : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

data class ModelsState(val loading: Boolean = false, val models: List<ModelInfo> = emptyList(), val error: String? = null)

data class SpendRow(val label: String, val cost: Double, val calls: Int)

data class UsageUi(
    val total: Double = 0.0,
    val limit: Double = 0.0,
    val resetsAt: Long = 0,
    val byPurpose: List<SpendRow> = emptyList(),
    val byChat: List<SpendRow> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val models = MutableStateFlow(ModelsState())
    val keyVersion = c.secrets.version
    val spent: StateFlow<Double> = c.dao.spentSinceFlow(c.budget.periodStart())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val bridge = c.bridge.state
    val isPaired get() = c.bridge.isPaired

    fun unpair() {
        viewModelScope.launch { c.bridge.unpair() }
    }
    fun hasKey(p: ProviderType) = c.ai.hasKey(p)

    /** Key preview that never reveals more than the last four characters. */
    fun maskedKey(p: ProviderType): String? = c.ai.apiKey(p)?.takeIf { it.isNotBlank() }?.let { "••••" + it.takeLast(4) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setNative(code: String) = launch {
        val s = c.settings.current()
        // Keep native and practice languages distinct by swapping when they collide.
        if (code == s.targetLang) c.settings.setLanguages(code, s.nativeLang) else c.settings.setNativeLang(code)
    }

    fun setTarget(code: String) = launch {
        val s = c.settings.current()
        if (code == s.nativeLang) c.settings.setLanguages(s.targetLang, code) else c.settings.setTargetLang(code)
    }

    fun setLevel(level: Level) = launch { c.settings.setLevel(level) }

    fun setProvider(p: ProviderType) = launch {
        c.settings.setProvider(p)
        keyCheck.value = KeyCheck.Idle
        models.value = ModelsState()
    }

    fun setCustomBaseUrl(url: String) = launch { c.settings.setCustomBaseUrl(url) }

    /** Validates then stores a key. The key is only ever kept in the encrypted store. */
    fun saveKey(p: ProviderType, key: String, onSaved: () -> Unit = {}) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        keyCheck.value = KeyCheck.Checking
        launch {
            try {
                val n = c.ai.testKey(p, trimmed)
                c.ai.setApiKey(p, trimmed)
                c.ai.listModels(p)
                keyCheck.value = KeyCheck.Ok(n)
                onSaved()
            } catch (e: AiException) {
                keyCheck.value = KeyCheck.Failed(e.message ?: "Key check failed")
            } catch (e: Exception) {
                keyCheck.value = KeyCheck.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun removeKey(p: ProviderType) {
        c.ai.setApiKey(p, null)
        keyCheck.value = KeyCheck.Idle
        models.value = ModelsState()
    }

    fun loadModels(refresh: Boolean = false) {
        models.value = models.value.copy(loading = true, error = null)
        launch {
            val p = c.settings.current().provider
            models.value = try {
                ModelsState(models = c.ai.listModels(p, refresh))
            } catch (e: AiException) {
                ModelsState(models = models.value.models, error = e.message)
            } catch (e: Exception) {
                ModelsState(models = models.value.models, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun setModel(slot: ModelSlot, id: String) = launch {
        val s = c.settings.current()
        c.settings.setModel(s.provider, slot, id)
        // Drop an effort the new model can't use.
        val info = models.value.models.firstOrNull { it.id == id }
        if (info != null && info.efforts.isNotEmpty() && s.effort(slot) !in info.efforts && s.effort(slot) != Effort.DEFAULT) {
            c.settings.setEffort(s.provider, slot, Effort.DEFAULT)
        }
    }

    fun setEffort(slot: ModelSlot, effort: Effort) = launch { c.settings.setEffort(c.settings.current().provider, slot, effort) }

    fun setPriceOverride(model: String, price: Price?) = launch { c.settings.setPriceOverride(c.settings.current().provider, model, price) }

    fun setMonthlyBudget(v: Double) = launch { c.settings.setMonthlyBudget(v) }
    fun setContactDefaults(d: ChatDefaults) = launch { c.settings.setContactDefaults(d) }
    fun setUnknownDefaults(d: ChatDefaults) = launch { c.settings.setUnknownDefaults(d) }
    fun setContextMessages(n: Int) = launch { c.settings.setContextMessages(n) }
    fun setTranslateOutgoing(v: Boolean) = launch { c.settings.setTranslateOutgoing(v) }
    fun setBackfill(n: Int) = launch { c.settings.setBackfill(n) }
    fun setNotificationContent(v: NotificationContent) = launch { c.settings.setNotificationContent(v) }
    fun setDeliveryReports(v: Boolean) = launch { c.settings.setDeliveryReports(v) }
    fun setScriptDisplay(r: Romanization, v: ScriptDisplay) = launch { c.settings.setScriptDisplay(r, v) }
    fun setOnboarded() = launch { c.settings.setOnboarded(true) }

    suspend fun price(model: String): Price? {
        val s = c.settings.current()
        return c.ai.price(s.provider, model, s)
    }

    // ---- usage ---------------------------------------------------------------------------------

    val usage: StateFlow<UsageUi> = c.settings.settings.flatMapLatest { s ->
        val since = c.budget.periodStart()
        combine(c.dao.spentSinceFlow(since), c.dao.spendByPurpose(since), c.dao.spendByThread(since)) { total, purposes, threads ->
            val chats = withContext(Dispatchers.IO) {
                threads.map { t ->
                    val label = t.threadId?.let { id ->
                        val r = c.messaging.recipientsForThread(id)
                        if (r.isEmpty()) "Deleted chat"
                        else c.dao.chatSettings(id)?.name ?: r.joinToString(", ") { c.contacts.lookup(it).displayName }
                    } ?: "Drafts & tools"
                    SpendRow(label, t.cost, t.calls)
                }
            }
            UsageUi(
                total = total,
                limit = s.monthlyBudgetUsd,
                resetsAt = c.budget.nextReset(),
                byPurpose = purposes.map { SpendRow(purposeLabel(it.purpose), it.cost, it.calls) },
                byChat = chats,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsageUi())

    private fun purposeLabel(p: String) = when (p) {
        "TRANSLATE_INCOMING" -> "Incoming translations"
        "TRANSLATE_OUTGOING" -> "Sent-message translations"
        "TRANSLATE_DRAFT" -> "Compose translations"
        "ROMANIZE" -> "Pinyin & romaji"
        "EXPLAIN" -> "Explanations"
        "FEEDBACK" -> "Feedback"
        else -> p
    }
}
