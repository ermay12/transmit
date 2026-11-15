package app.transmit.ai

import app.transmit.data.AppSettings
import app.transmit.data.ContactsRepository
import app.transmit.data.Language
import app.transmit.data.SettingsRepository
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.data.db.TransmitDao
import app.transmit.smart.Otp
import app.transmit.telephony.Message
import app.transmit.telephony.MessagingRepository
import app.transmit.telephony.Reactions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Decides what to translate, batches requests, dedupes in-flight work and stores the results. */
class TranslationManager(
    private val dao: TransmitDao,
    private val tutor: Tutor,
    private val policy: ChatPolicy,
    private val repo: MessagingRepository,
    private val settings: SettingsRepository,
    private val contacts: ContactsRepository,
) {
    sealed interface Event {
        val threadId: Long
        data class BudgetReached(override val threadId: Long, val message: String) : Event
        data class Failed(override val threadId: Long, val error: AiException) : Event
    }

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** Message keys currently being translated (for shimmer placeholders). */
    val pending: StateFlow<Set<String>> = _pending
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    /** Message keys whose last translation attempt failed or came back empty, so the UI stops waiting for them. */
    val failed: StateFlow<Set<String>> = _failed
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    /** Problems hit by automatic translation, surfaced as unobtrusive banners. */
    val events: SharedFlow<Event> = _events

    fun isTranslatable(m: Message): Boolean {
        if (m.body.isBlank() || Reactions.parse(m.body) != null) return false
        return m.body.replace(URL, " ").any { it.isLetter() }
    }

    /**
     * Automatic translation skips verification codes: they're needed at once rather than after a round trip,
     * and they shouldn't reach the AI provider. A tap still translates one.
     */
    fun isAutoTranslatable(m: Message): Boolean = isTranslatable(m) && Otp.find(m.body) == null

    /** The messages automatic translation covers: the newest few translatable ones. */
    fun autoCandidates(messages: List<Message>, s: AppSettings): List<Message> = messages
        .filter { isAutoTranslatable(it) && (!it.outgoing || s.translateOutgoing) }
        .sortedByDescending { it.date }
        .take(s.backfillCount)

    suspend fun autoTranslate(threadId: Long, messages: List<Message>) {
        val s = settings.current()
        val eff = policy.effective(threadId, s)
        if (!eff.autoTranslate) return
        translate(threadId, autoCandidates(messages, s), messages, eff.target, s, auto = true)
    }

    suspend fun translateIncoming(threadId: Long, key: String) {
        val message = repo.message(key) ?: return
        val s = settings.current()
        val eff = policy.effective(threadId, s)
        if (!eff.autoTranslate || !isAutoTranslatable(message)) return
        val recent = repo.messages(threadId, s.contextMessages + 2)
        translate(threadId, listOf(message), recent, eff.target, s, auto = true)
    }

    /** User-initiated translation. Throws [BudgetExceededException] / [AiException] for the UI to handle. */
    suspend fun translateNow(message: Message, all: List<Message>, lang: Language, overrideBudget: Boolean = false, refresh: Boolean = false) {
        if (!isTranslatable(message)) return
        translate(message.threadId, listOf(message), all, lang, settings.current(), auto = false, overrideBudget = overrideBudget, refresh = refresh)
    }

    suspend fun storeSelf(key: String, threadId: Long, lang: Language, text: String) {
        if (text.isBlank()) return
        dao.upsertTranslation(
            TranslationEntity(key, lang.code, threadId, 0, text.trim(), TranslationOrigin.SELF, null, 0.0, 0, 0, System.currentTimeMillis()),
        )
    }

    private suspend fun translate(
        threadId: Long,
        candidates: List<Message>,
        all: List<Message>,
        lang: Language,
        s: AppSettings,
        auto: Boolean,
        overrideBudget: Boolean = false,
        refresh: Boolean = false,
    ) {
        if (candidates.isEmpty()) return
        val existing = if (refresh) emptySet() else dao.translations(candidates.map { it.key }, lang.code).map { it.msgKey }.toSet()
        val todo = candidates.filter { it.key !in existing && inFlight.add("${it.key}|${lang.code}") }
        if (todo.isEmpty()) return
        _pending.update { it + todo.map { m -> m.key } }
        val saved = mutableSetOf<String>()
        try {
            val remaining = mutableListOf<Message>()
            for (m in todo) {
                val reuse = if (refresh) null else dao.translationBySource(m.body.trim().hashCode(), lang.code)
                if (reuse != null) {
                    save(m, lang, reuse.text, reuse.origin, reuse.model, 0.0, 0, 0)
                    saved += m.key
                } else {
                    remaining += m
                }
            }
            for (chunk in remaining.sortedBy { it.date }.chunked(8)) {
                val items = chunk.mapIndexed { i, m -> (i + 1).toString() to m.body.trim() }
                val purpose = if (chunk.all { it.outgoing }) Purpose.TRANSLATE_OUTGOING else Purpose.TRANSLATE_INCOMING
                val batch = tutor.translate(items, lang, contextFor(chunk.first(), all, s.contextMessages), threadId, purpose, overrideBudget)
                val share = 1.0 / chunk.size
                chunk.forEachIndexed { i, m ->
                    val text = batch.texts[(i + 1).toString()] ?: return@forEachIndexed
                    save(
                        m, lang, text, TranslationOrigin.AI, batch.cost.model, batch.cost.costUsd * share,
                        (batch.cost.inputTokens * share).toLong(), (batch.cost.outputTokens * share).toLong(),
                    )
                    saved += m.key
                }
            }
        } catch (e: BudgetExceededException) {
            if (!auto) throw e
            _events.tryEmit(Event.BudgetReached(threadId, e.message.orEmpty()))
        } catch (e: AiException) {
            if (!auto) throw e
            _events.tryEmit(Event.Failed(threadId, e))
        } finally {
            todo.forEach { inFlight.remove("${it.key}|${lang.code}") }
            val keys = todo.map { it.key }.toSet()
            _failed.update { it - saved + (keys - saved) }
            _pending.update { it - keys }
        }
    }

    private suspend fun save(m: Message, lang: Language, text: String, origin: String, model: String?, cost: Double, inTok: Long, outTok: Long) {
        dao.upsertTranslation(
            TranslationEntity(
                msgKey = m.key, lang = lang.code, threadId = m.threadId, sourceHash = m.body.trim().hashCode(), text = text,
                origin = origin, model = model, costUsd = cost, inputTokens = inTok, outputTokens = outTok, createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun contextFor(first: Message, all: List<Message>, count: Int): List<ContextLine> {
        if (count <= 0) return emptyList()
        return all.asSequence()
            .filter { it.date < first.date && it.key != first.key && it.body.isNotBlank() && Reactions.parse(it.body) == null }
            .sortedByDescending { it.date }
            .take(count)
            .toList()
            .reversed()
            .map { ContextLine(it.outgoing, it.sender?.let { a -> contacts.lookup(a).name }, it.body) }
    }

    private companion object {
        val URL = Regex("https?://\\S+|www\\.\\S+")
    }
}
