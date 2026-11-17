package app.transmit.ai

import app.transmit.data.AppSettings
import app.transmit.data.Language
import app.transmit.data.ModelSlot
import app.transmit.data.ScriptDisplay
import app.transmit.data.SettingsRepository
import app.transmit.data.db.ExplanationEntity
import app.transmit.data.db.TransmitDao
import app.transmit.data.db.UsageEntity
import java.util.Locale

enum class Purpose { TRANSLATE_INCOMING, TRANSLATE_OUTGOING, TRANSLATE_DRAFT, ROMANIZE, EXPLAIN, FEEDBACK }

/** What one AI call cost, for display and for the usage ledger. */
data class CallCost(val model: String, val inputTokens: Long, val outputTokens: Long, val costUsd: Double, val priced: Boolean)

data class TranslationBatch(val texts: Map<String, String>, val cost: CallCost)
data class Explanation(val text: String, val cost: CallCost?, val cached: Boolean)
data class FeedbackResult(val feedback: Feedback, val cost: CallCost)

class BudgetExceededException(val status: BudgetStatus) : Exception(
    when (status) {
        is BudgetStatus.GlobalExceeded -> "Monthly AI budget reached (${usd(status.spent)} of ${usd(status.limit)})."
        is BudgetStatus.ChatExceeded -> "This chat's AI budget is used up (${usd(status.spent)} of ${usd(status.limit)})."
        BudgetStatus.Ok -> "Budget OK"
    },
)

fun usd(v: Double): String = when {
    v == 0.0 -> "$0"
    v < 0.01 -> "<$0.01"
    v < 10 -> String.format(Locale.US, "$%.2f", v)
    else -> String.format(Locale.US, "$%.0f", v)
}

/**
 * The language-learning operations: translate, explain and give feedback. Every call is checked
 * against the budgets first and recorded in the usage ledger afterwards.
 */
class Tutor(
    private val ai: AiService,
    private val settings: SettingsRepository,
    private val budget: BudgetManager,
    private val dao: TransmitDao,
) {
    suspend fun translate(
        items: List<Pair<String, String>>,
        to: Language,
        context: List<ContextLine>,
        threadId: Long?,
        purpose: Purpose,
        overrideBudget: Boolean = false,
    ): TranslationBatch {
        val s = settings.current()
        val (text, cost) = run(
            s, ModelSlot.TRANSLATE, threadId, purpose, overrideBudget,
            system = Prompts.translateSystem(to),
            prompt = Prompts.translateUser(items, context),
            maxTokens = 4096 + items.sumOf { it.second.length } * 2,
            fast = true,
        )
        val parsed = Prompts.parseTranslations(text, items.map { it.first })
        if (parsed.isEmpty()) throw AiException("The model returned no translation.", AiException.Kind.OTHER)
        return TranslationBatch(parsed, cost)
    }

    /** Writes [items] (id to text in [lang]) in [lang]'s romanization, on the translation model. */
    suspend fun romanize(
        items: List<Pair<String, String>>,
        lang: Language,
        threadId: Long?,
        overrideBudget: Boolean = false,
    ): TranslationBatch {
        val r = lang.romanization ?: throw IllegalArgumentException("${lang.name} has no romanization")
        val s = settings.current()
        val (text, cost) = run(
            s, ModelSlot.TRANSLATE, threadId, Purpose.ROMANIZE, overrideBudget,
            system = Prompts.romanizeSystem(lang, r),
            prompt = Prompts.translateUser(items, emptyList()),
            // Pinyin and romaji take several tokens per character.
            maxTokens = 4096 + items.sumOf { it.second.length } * 6,
            fast = true,
        )
        val parsed = Prompts.parseTranslations(text, items.map { it.first }).filterValues { it.isNotBlank() }
        if (parsed.isEmpty()) throw AiException("The model returned no ${r.label}.", AiException.Kind.OTHER)
        return TranslationBatch(parsed, cost)
    }

    /**
     * Writes [items] (id to [lang] text written in its romanization) in [lang] script, staying with the syllables
     * written. [sent] (id to the text message that went out) tells which words were meant.
     */
    suspend fun toCharacters(
        items: List<Pair<String, String>>,
        sent: Map<String, String>,
        lang: Language,
        threadId: Long?,
        overrideBudget: Boolean = false,
    ): TranslationBatch {
        val r = lang.romanization ?: throw IllegalArgumentException("${lang.name} has no romanization")
        val s = settings.current()
        val (text, cost) = run(
            s, ModelSlot.TRANSLATE, threadId, Purpose.ROMANIZE, overrideBudget,
            system = Prompts.charactersSystem(lang, r),
            prompt = Prompts.charactersUser(items, sent),
            maxTokens = 4096 + items.sumOf { it.second.length } * 2,
            fast = true,
        )
        val parsed = Prompts.parseTranslations(text, items.map { it.first }).filterValues { it.isNotBlank() }
        if (parsed.isEmpty()) throw AiException("The model returned no ${lang.name}.", AiException.Kind.OTHER)
        return TranslationBatch(parsed, cost)
    }

    suspend fun explain(
        original: String,
        rendered: String,
        renderedLang: Language,
        threadId: Long?,
        cacheKey: String?,
        overrideBudget: Boolean = false,
        refresh: Boolean = false,
    ): Explanation {
        if (cacheKey != null && !refresh) {
            dao.explanation(cacheKey)?.let { return Explanation(it.text, null, cached = true) }
        }
        val s = settings.current()
        val (text, cost) = run(
            s, ModelSlot.TUTOR, threadId, Purpose.EXPLAIN, overrideBudget,
            system = Prompts.tutorSystem(s.native, renderedLang, s.level),
            prompt = Prompts.explainUser(original, rendered, renderedLang),
            maxTokens = 8000,
        )
        if (text.isBlank()) throw AiException("The model returned an empty explanation.", AiException.Kind.OTHER)
        if (cacheKey != null) {
            dao.upsertExplanation(ExplanationEntity(cacheKey, text, cost.model, cost.costUsd, System.currentTimeMillis()))
        }
        return Explanation(text, cost, cached = false)
    }

    suspend fun feedback(
        intended: String?,
        attempt: String,
        target: Language,
        threadId: Long?,
        overrideBudget: Boolean = false,
        display: ScriptDisplay? = null,
    ): FeedbackResult {
        val s = settings.current()
        val (text, cost) = run(
            s, ModelSlot.TUTOR, threadId, Purpose.FEEDBACK, overrideBudget,
            system = Prompts.feedbackSystem(s.native, target, s.level, display),
            prompt = Prompts.feedbackUser(intended, attempt, s.native, target),
            maxTokens = 8000,
        )
        return FeedbackResult(Prompts.parseFeedback(text), cost)
    }

    /**
     * The stored effort, unless the model is known not to support it: sending an effort to a model
     * without reasoning controls is rejected by most providers. For translation, "Auto" means the
     * fastest setting the model allows: thinking made translations many times slower for no gain.
     */
    private suspend fun effortFor(s: AppSettings, slot: ModelSlot, model: String): Effort {
        val wanted = s.effort(slot)
        if (wanted == Effort.DEFAULT && slot != ModelSlot.TRANSLATE) return wanted
        val info = ai.modelInfo(s.provider, model) ?: return wanted
        if (wanted == Effort.DEFAULT) return info.efforts.minOrNull() ?: Effort.DEFAULT
        return if (wanted in info.efforts) wanted else Effort.DEFAULT
    }

    private suspend fun run(
        s: AppSettings,
        slot: ModelSlot,
        threadId: Long?,
        purpose: Purpose,
        overrideBudget: Boolean,
        system: String,
        prompt: String,
        maxTokens: Int,
        fast: Boolean = false,
    ): Pair<String, CallCost> {
        val model = s.model(slot)
            ?: throw AiException("Choose a ${if (slot == ModelSlot.TRANSLATE) "translation" else "tutor"} model in Settings.", AiException.Kind.NO_MODEL)
        if (!overrideBudget) {
            val status = budget.status(threadId, s)
            if (status != BudgetStatus.Ok) throw BudgetExceededException(status)
        }
        val client = ai.client(s.provider, s)
        val result = client.complete(CompletionRequest(model, system, prompt, maxTokens, effortFor(s, slot, model), fast))
        val price = ai.price(s.provider, result.model, s) ?: ai.price(s.provider, model, s)
        val cost = result.reportedCostUsd ?: price?.cost(result.inputTokens, result.outputTokens) ?: 0.0
        val callCost = CallCost(result.model, result.inputTokens, result.outputTokens, cost, priced = result.reportedCostUsd != null || price != null)
        dao.insertUsage(
            UsageEntity(
                threadId = threadId, at = System.currentTimeMillis(), provider = s.provider.name, model = result.model,
                purpose = purpose.name, inputTokens = result.inputTokens, outputTokens = result.outputTokens, costUsd = cost,
            ),
        )
        return result.text to callCost
    }
}
