package app.transmit.ai

import app.transmit.access.AccessManager
import app.transmit.access.forAccess
import app.transmit.data.AppSettings
import app.transmit.data.ContactsRepository
import app.transmit.data.Language
import app.transmit.data.ScriptDisplay
import app.transmit.data.Scripts
import app.transmit.data.SettingsRepository
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.data.db.TransmitDao
import app.transmit.smart.Otp
import app.transmit.messaging.Message
import app.transmit.messaging.MessagingRepository
import app.transmit.messaging.Reactions
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
    private val access: AccessManager,
) {
    sealed interface Event {
        val threadId: Long
        data class BudgetReached(override val threadId: Long, val block: AiBlock) : Event
        data class Failed(override val threadId: Long, val error: AiException) : Event
    }

    /** The settings AI calls run with: the managed ones in trial and subscription. */
    private suspend fun aiSettings(): AppSettings = settings.current().forAccess(access.current())

    /** Whether any AI can run now; without it, automatic work leaves messages in their original language. */
    private suspend fun aiAvailable() = access.current().canUseAi

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** Message keys currently being translated (for shimmer placeholders). */
    val pending: StateFlow<Set<String>> = _pending
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    /** Message keys whose last translation attempt failed or came back empty, so the UI stops waiting for them. */
    val failed: StateFlow<Set<String>> = _failed
    private val _romanFailed = MutableStateFlow<Set<String>>(emptySet())
    /** Message keys whose last romanization attempt failed, so the UI shows the characters instead of waiting. */
    val romanFailed: StateFlow<Set<String>> = _romanFailed
    /** The user's own pinyin or romaji being written in characters, and what the AI already had. */
    data class Conversions(
        val running: Set<String> = emptySet(),
        /** Message keys the AI wrote in characters this run, or left as they were: not pinyin or romaji after all. */
        val tried: Set<String> = emptySet(),
    )
    private val _conversions = MutableStateFlow(Conversions())
    val conversions: StateFlow<Conversions> = _conversions
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

    /**
     * Whether a translation of [m] is still on its way, so a notification may wait for it. False once it failed or
     * when no AI is set up: a message is never hidden for a translation that won't come.
     */
    fun expectsAutoTranslation(m: Message): Boolean =
        isAutoTranslatable(m) && m.key !in _failed.value && access.access.value?.canUseAi == true

    /** The messages automatic translation covers: the newest few translatable ones. */
    fun autoCandidates(messages: List<Message>, s: AppSettings): List<Message> = messages
        .filter { isAutoTranslatable(it) && (!it.outgoing || s.translateOutgoing) }
        .sortedByDescending { it.date }
        .take(s.backfillCount)

    suspend fun autoTranslate(threadId: Long, messages: List<Message>) {
        val s = aiSettings()
        val eff = policy.effective(threadId, s)
        if (!eff.autoTranslate) return
        val candidates = autoCandidates(messages, s)
        translate(threadId, candidates, messages, eff.target, s, auto = true)
        // The user's own pinyin or romaji among as many of the newest messages, where the chat shows characters.
        val newest = messages.filter { isTranslatable(it) }.sortedByDescending { it.date }.take(s.backfillCount)
        toCharacters(threadId, newest.filter { it.outgoing }.map { it.key }, eff.target, auto = true)
        // Also covers translations made before their romanization was shown.
        if (s.romanizesAutomatically(eff.target)) romanize(threadId, candidates.map { it.key }, eff.target, auto = true)
    }

    suspend fun translateIncoming(threadId: Long, key: String) {
        val message = repo.message(key) ?: return
        val s = aiSettings()
        val eff = policy.effective(threadId, s)
        if (!eff.autoTranslate || !isAutoTranslatable(message)) return
        val recent = repo.messages(threadId, s.contextMessages + 2)
        translate(threadId, listOf(message), recent, eff.target, s, auto = true)
        // Before the notification is updated, which shows the romanization when that's all the user reads.
        if (s.romanizesAutomatically(eff.target)) romanize(threadId, listOf(key), eff.target, auto = true)
    }

    /** User-initiated translation. Throws [AiBlockedException] / [AiException] for the UI to handle. */
    suspend fun translateNow(message: Message, all: List<Message>, lang: Language, overrideBudget: Boolean = false, refresh: Boolean = false) {
        if (!isTranslatable(message)) return
        val s = aiSettings()
        translate(message.threadId, listOf(message), all, lang, s, auto = false, overrideBudget = overrideBudget, refresh = refresh)
        // Reported as a banner if it fails: the translation itself worked, and a retry shouldn't redo it.
        if (s.romanizesAutomatically(lang)) romanize(message.threadId, listOf(message.key), lang, auto = true, overrideBudget = overrideBudget)
    }

    /** The user's own [text] in [lang] for a message they sent, with its [romanized] form when known. */
    suspend fun storeSelf(key: String, threadId: Long, lang: Language, text: String, romanized: String? = null) {
        if (text.isBlank()) return
        val roman = romanized ?: lang.romanization?.let { Scripts.local(text.trim(), it) }
        dao.upsertTranslation(
            TranslationEntity(key, lang.code, threadId, 0, text.trim(), TranslationOrigin.SELF, null, 0.0, 0, 0, System.currentTimeMillis(), roman),
        )
    }

    /**
     * Writes the user's own [lang] texts of [keys] that are in pinyin or romaji in characters, when [lang] is shown
     * with characters. The pinyin or romaji they wrote stays as the romanization, and the message that went out tells
     * the AI which words they meant. [auto] work skips texts the AI already had and reports problems as [events];
     * otherwise they're thrown.
     */
    suspend fun toCharacters(threadId: Long, keys: Collection<String>, lang: Language, auto: Boolean, overrideBudget: Boolean = false) {
        val r = lang.romanization ?: return
        if (keys.isEmpty() || settings.current().scriptDisplay(lang) == ScriptDisplay.ROMANIZED) return
        if (auto && !aiAvailable()) return
        val tried = _conversions.value.tried
        val todo = dao.translations(keys.distinct(), lang.code).filter { t ->
            t.origin == TranslationOrigin.SELF && (!auto || t.msgKey !in tried) && Scripts.isRomanized(t.text, r) &&
                inFlight.add("${t.msgKey}|${t.lang}|c")
        }
        if (todo.isEmpty()) return
        val keysTodo = todo.map { it.msgKey }.toSet()
        _conversions.update { it.copy(running = it.running + keysTodo) }
        try {
            // Placeholders and deleted messages have no message to go by; a placeholder's replacement gets its turn.
            val sent = todo.mapNotNull { t -> repo.message(t.msgKey)?.let { t to it.body } }
            for (chunk in sent.chunked(12)) {
                val ids = chunk.indices.map { (it + 1).toString() }
                val batch = tutor.toCharacters(ids.zip(chunk.map { it.first.text }), ids.zip(chunk.map { it.second }).toMap(), lang, threadId, overrideBudget)
                chunk.forEachIndexed { i, (t, _) ->
                    _conversions.update { it.copy(tried = it.tried + t.msgKey) }
                    val text = batch.texts[ids[i]]?.takeIf { Scripts.needsReading(it) } ?: return@forEachIndexed
                    dao.setCharacters(t.msgKey, t.lang, t.text, text, t.romanized ?: Scripts.local(t.text, r) ?: t.text)
                }
            }
        } catch (e: AiBlockedException) {
            if (!auto) throw e
            _events.tryEmit(Event.BudgetReached(threadId, e.block))
        } catch (e: AiException) {
            if (!auto) throw e
            _events.tryEmit(Event.Failed(threadId, e))
        } finally {
            todo.forEach { inFlight.remove("${it.msgKey}|${it.lang}|c") }
            _conversions.update { it.copy(running = it.running - keysTodo) }
        }
    }

    /**
     * Adds the romanization to the stored [lang] translations of [keys] that lack one. Text without characters to
     * read is done on the device. [auto] work reports problems as [events]; otherwise they're thrown.
     */
    suspend fun romanize(threadId: Long, keys: Collection<String>, lang: Language, auto: Boolean, overrideBudget: Boolean = false) {
        val r = lang.romanization ?: return
        if (keys.isEmpty()) return
        val rows = dao.translations(keys.distinct(), lang.code)
            .filter { it.romanized == null && it.origin != TranslationOrigin.SAME && it.text.isNotBlank() }
        val todo = rows.filter { inFlight.add("${it.msgKey}|${lang.code}|r") }
        if (todo.isEmpty()) return
        val keysTodo = todo.map { it.msgKey }.toSet()
        val done = mutableSetOf<String>()
        // Without AI only the device's own romanization runs; the rest show their characters rather than wait.
        val remoteAllowed = !auto || aiAvailable()
        try {
            val remote = mutableListOf<TranslationEntity>()
            for (t in todo) {
                val local = Scripts.local(t.text, r)
                if (local != null) {
                    dao.setRomanized(t.msgKey, lang.code, t.text, local)
                    done += t.msgKey
                } else {
                    remote += t
                }
            }
            for (chunk in remote.takeIf { remoteAllowed }.orEmpty().chunked(12)) {
                val batch = tutor.romanize(chunk.mapIndexed { i, t -> (i + 1).toString() to t.text }, lang, threadId, overrideBudget)
                chunk.forEachIndexed { i, t ->
                    val text = batch.texts[(i + 1).toString()] ?: return@forEachIndexed
                    dao.setRomanized(t.msgKey, lang.code, t.text, text)
                    done += t.msgKey
                }
            }
        } catch (e: AiBlockedException) {
            if (!auto) throw e
            _events.tryEmit(Event.BudgetReached(threadId, e.block))
        } catch (e: AiException) {
            if (!auto) throw e
            _events.tryEmit(Event.Failed(threadId, e))
        } finally {
            todo.forEach { inFlight.remove("${it.msgKey}|${lang.code}|r") }
            _romanFailed.update { it - done + (keysTodo - done) }
        }
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
        if (auto && !aiAvailable()) {
            // Shown in the original at once, rather than waiting for a translation that won't come.
            _failed.update { it + candidates.map { m -> m.key } }
            return
        }
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
                    save(m, lang, reuse.text, reuse.origin, reuse.model, 0.0, 0, 0, reuse.romanized)
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
        } catch (e: AiBlockedException) {
            if (!auto) throw e
            _events.tryEmit(Event.BudgetReached(threadId, e.block))
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

    private suspend fun save(
        m: Message, lang: Language, text: String, origin: String, model: String?, cost: Double, inTok: Long, outTok: Long, romanized: String? = null,
    ) {
        dao.upsertTranslation(
            TranslationEntity(
                msgKey = m.key, lang = lang.code, threadId = m.threadId, sourceHash = m.body.trim().hashCode(), text = text,
                origin = origin, model = model, costUsd = cost, inputTokens = inTok, outputTokens = outTok, createdAt = System.currentTimeMillis(),
                romanized = romanized,
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
