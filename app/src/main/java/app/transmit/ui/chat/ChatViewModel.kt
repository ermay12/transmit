package app.transmit.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.transmit.AppContainer
import app.transmit.ai.AiException
import app.transmit.ai.BudgetExceededException
import app.transmit.ai.CallCost
import app.transmit.ai.ContextLine
import app.transmit.ai.EffectiveChat
import app.transmit.ai.Feedback
import app.transmit.ai.Purpose
import app.transmit.ai.TranslationManager
import app.transmit.data.AppSettings
import app.transmit.data.ContactInfo
import app.transmit.data.Language
import app.transmit.data.Languages
import app.transmit.data.Romanization
import app.transmit.data.ScriptDisplay
import app.transmit.data.Scripts
import app.transmit.data.db.ChatSettingsEntity
import app.transmit.data.db.DraftEntity
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.smart.Otp
import app.transmit.smart.SmartLinks
import app.transmit.gm.NotConnectedException
import app.transmit.gm.PhoneNotRespondingException
import app.transmit.messaging.AttachmentTooLargeException
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Message
import app.transmit.messaging.OutgoingAttachment
import app.transmit.messaging.PhoneNumbers
import app.transmit.messaging.Reactions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

enum class AiMode { NONE, TRANSLATE, FEEDBACK, EXPLAIN }
enum class Busy { TRANSLATE, FEEDBACK, EXPLAIN, SEND }

data class ComposeUi(
    val native: String = "",
    val target: String = "",
    /** The native box was filled by the AI (from the target box). */
    val nativeAi: Boolean = false,
    /** The target box was filled by the AI (from the native box). */
    val targetAi: Boolean = false,
    /** The AI-filled target no longer matches the edited native text. */
    val stale: Boolean = false,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val busy: Busy? = null,
    val feedback: Feedback? = null,
    /** The native text was just prepared from the target text; nudge the user to send. */
    val readyToSend: Boolean = false,
    /** A practice-language text and its romanization, which shows under the box. */
    val reading: Pair<String, String>? = null,
    /** The romanization of the current practice-language text is on its way. */
    val readingBusy: Boolean = false,
    /** The message being replied to. */
    val replyTo: Quote? = null,
) {
    /** The romanization under the practice-language box: the previous text's, dimmed, while the new one is on its way. */
    val targetReading: String?
        get() = reading?.takeIf { target.isNotBlank() && (it.first == target || readingBusy) }?.second?.takeIf { it != target.trim() }
    val readingStale get() = reading != null && reading.first != target

    val aiMode: AiMode
        get() = when {
            target.isNotBlank() && !targetAi -> AiMode.FEEDBACK
            target.isNotBlank() && targetAi && !stale -> AiMode.EXPLAIN
            native.isNotBlank() -> AiMode.TRANSLATE
            else -> AiMode.NONE
        }
    val canSend get() = busy == null && (native.isNotBlank() || target.isNotBlank() || attachments.isNotEmpty())
}

data class ReactionUi(val emoji: String, val count: Int, val mine: Boolean)

/** A message a reply quotes, as its own bubble shows it. */
data class Quote(
    val key: String,
    val outgoing: Boolean,
    /** Who sent it, for incoming messages. */
    val sender: String?,
    val text: String,
    /** The type of its first attachment. */
    val mediaType: String? = null,
    /** Its first photo or video, for a thumbnail. */
    val thumbnail: Uri? = null,
    /** Its translation is on its way, so its text is hidden. */
    val hidden: Boolean = false,
    /** Not among the cached messages: older than they go back, or deleted. */
    val missing: Boolean = false,
) {
    /** The phone's id, which a reply points at. */
    val messageId get() = key.removePrefix("g")

    /** Its text, or for a message without any, what kind of attachment it is. */
    val summary: String
        get() = when {
            text.isNotBlank() -> text
            mediaType == null -> ""
            mediaType.startsWith("image/") -> "📷 Photo"
            mediaType.startsWith("video/") -> "🎬 Video"
            mediaType.startsWith("audio/") -> "🎵 Audio"
            else -> "📎 Attachment"
        }

    companion object {
        fun missing(key: String) = Quote(key, outgoing = false, sender = null, text = "", missing = true)
    }
}

/** This message as a reply quotes it: the text its bubble shows, translated or not. */
fun MessageUi.toQuote() = Quote(
    key = key,
    outgoing = message.outgoing,
    sender = message.sender,
    text = displayText.ifBlank { message.subject.orEmpty() },
    mediaType = message.attachments.firstOrNull()?.mimeType,
    thumbnail = message.attachments.firstOrNull { (it.isImage || it.isVideo) && !it.pending }?.uri,
    hidden = awaiting && !showOriginal,
)

data class MessageUi(
    val message: Message,
    val reactions: List<ReactionUi>,
    val translation: TranslationEntity?,
    val showOriginal: Boolean,
    val firstInRun: Boolean,
    val lastInRun: Boolean,
    val newDay: Boolean,
    val translatable: Boolean,
    /** An incoming message whose translation is on its way; it stays hidden unless tapped. */
    val awaiting: Boolean,
    /** A verification code in an incoming message. */
    val code: String? = null,
    /** Offer a "Copy code" chip: the conversation's newest code, while it's fresh. */
    val codeChip: Boolean = false,
    /** How the chat's practice language is shown; null when it has no romanization. */
    val script: ScriptDisplay? = null,
    /** Tapped to show the romanization under the characters (with [ScriptDisplay.CHARACTERS]). */
    val showRoman: Boolean = false,
    /** The last try at romanizing the translation failed, so the characters show without it. */
    val romanFailed: Boolean = false,
    /** The user's own text is in pinyin or romaji though the chat shows characters; a tap writes it in them. */
    val needsCharacters: Boolean = false,
    /** The message this one replies to. */
    val quote: Quote? = null,
) {
    val key get() = message.key
    /** True when the bubble currently shows the target-language text. */
    val showingTranslation get() = translation != null && !showOriginal && translation.origin != TranslationOrigin.SAME

    private val romanWanted get() = script == ScriptDisplay.BOTH || script == ScriptDisplay.ROMANIZED || (script == ScriptDisplay.CHARACTERS && showRoman)

    /** The translation's romanization is wanted on screen and hasn't arrived. */
    val romanMissing: Boolean
        get() = romanWanted && translation != null && translation.origin != TranslationOrigin.SAME && translation.romanized == null && !romanFailed

    /** Romanized as soon as it's on screen; with characters only, a tap asks for it instead. */
    val romanizesOnSight get() = romanMissing && script != ScriptDisplay.CHARACTERS

    private val romanUnder get() = showingTranslation && (script == ScriptDisplay.BOTH || (script == ScriptDisplay.CHARACTERS && showRoman))

    val displayText: String
        get() = when {
            !showingTranslation -> message.body
            script == ScriptDisplay.ROMANIZED -> translation!!.romanized ?: translation.text
            else -> translation!!.text
        }

    /** The romanization shown under [displayText]. */
    val subtext: String? get() = if (romanUnder) translation!!.romanized?.takeIf { it != translation.text } else null

    /** The romanization would show under the text but is on its way. */
    val subtextPending get() = romanUnder && romanMissing
}

sealed interface Sheet {
    data class Actions(val item: MessageUi) : Sheet
    data class Emoji(val item: MessageUi) : Sheet
    data class Details(val item: MessageUi) : Sheet
    data class Explain(
        val original: String,
        val rendered: String,
        val lang: Language,
        val cacheKey: String,
        val text: String? = null,
        val cost: CallCost? = null,
        val cached: Boolean = false,
        val error: String? = null,
    ) : Sheet
    data object ChatInfo : Sheet
}

data class BudgetPrompt(val message: String, val retry: () -> Unit)

@OptIn(FlowPreview::class)
class ChatViewModel(
    private val c: AppContainer,
    val threadId: Long,
    initialText: String?,
    initialAttachments: List<OutgoingAttachment>,
) : ViewModel() {
    val recipients = MutableStateFlow<List<String>>(emptyList())
    val groupPhoto = MutableStateFlow<android.net.Uri?>(null)
    val people = MutableStateFlow<Map<String, ContactInfo>>(emptyMap())
    private val raw = MutableStateFlow<List<Message>>(emptyList())
    /** Messages replies in [raw] quote that aren't in it themselves, from further back in the cache. */
    private val quoted = MutableStateFlow<Map<String, Message>>(emptyMap())
    val loaded = MutableStateFlow(false)
    private var limit = PAGE
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Finds addresses, dates and codes in bubbles as they're drawn. */
    val smart: SmartLinks get() = c.smart
    val chat = MutableStateFlow<EffectiveChat?>(null)
    val showOriginal = MutableStateFlow<Set<String>>(emptySet())
    /** Messages tapped to show their romanization under the characters. */
    private val showRoman = MutableStateFlow<Set<String>>(emptySet())
    /** How the chat's practice language is shown; null when it has no romanization. */
    val script: StateFlow<ScriptDisplay?> = combine(settings, chat) { s, eff -> eff?.target?.let { s?.scriptDisplay(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val romanRequests = Channel<String>(Channel.UNLIMITED)
    /** Romanizations of compose-box texts, most recently used last. */
    private val romanCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 100
    }
    val compose = MutableStateFlow(ComposeUi(native = initialText.orEmpty(), attachments = initialAttachments))
    val sheet = MutableStateFlow<Sheet?>(null)
    val budgetPrompt = MutableStateFlow<BudgetPrompt?>(null)
    val banner = MutableStateFlow<String?>(null)
    val downloading = MutableStateFlow<Set<String>>(emptySet())
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts
    private val _jumps = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** A message to scroll to and highlight: the original of a tapped quote, once it's in the list. */
    val jumps: SharedFlow<String> = _jumps

    val spent: StateFlow<Double> = c.dao.threadSpentSinceFlow(threadId, c.budget.periodStart())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    /** Whether the conversation is on screen (marks messages read as they arrive). */
    var active = false
        set(value) {
            field = value
            c.notifier.activeThreadId = if (value) threadId else c.notifier.activeThreadId.takeIf { it != threadId }
            if (value) markRead()
        }

    private val translations: StateFlow<Map<String, TranslationEntity>> =
        combine(c.dao.translationsForThread(threadId), chat) { list, eff ->
            val lang = eff?.target?.code
            list.filter { it.lang == lang }.associateBy { it.msgKey }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Keys of incoming messages that automatic translation will cover and hasn't given up on. */
    private val expected: Flow<Set<String>> =
        combine(raw, chat, settings, c.translations.failed) { list, eff, s, failed ->
            if (eff?.autoTranslate != true || s == null) emptySet()
            else c.translations.autoCandidates(list, s).filter { !it.outgoing && it.key !in failed }.mapTo(HashSet()) { it.key }
        }

    /** How each message is shown, apart from its translation. */
    private data class Views(
        val original: Set<String>,
        val roman: Set<String>,
        val script: ScriptDisplay?,
        val romanFailed: Set<String>,
        val conversions: TranslationManager.Conversions,
    )

    private val views: Flow<Views> = combine(showOriginal, showRoman, script, c.translations.romanFailed, c.translations.conversions, ::Views)

    val items: StateFlow<List<MessageUi>> =
        combine(combine(raw, quoted, ::Pair), translations, c.translations.pending, views, expected) { (list, q), tr, pending, v, exp ->
            build(list, q, tr, pending, v, exp)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val r = c.messaging.recipientsForThread(threadId)
            recipients.value = r
            refreshPeople()
            chat.value = c.policy.effective(threadId, c.settings.current(), r)
            val draft = c.dao.draft(threadId)
            if (draft != null && compose.value.native.isEmpty()) {
                compose.update { it.copy(native = draft.native, target = draft.target) }
            }
            reload()
        }
        viewModelScope.launch { for (request in reloads) load() }
        viewModelScope.launch { c.messaging.changes.debounce(150).collect { reload() } }
        viewModelScope.launch {
            combine(c.settings.settings, c.dao.chatSettingsFlow(threadId), c.contacts.version) { s, _, _ -> s }.collect { s ->
                val r = recipients.value
                refreshPeople()
                val before = chat.value
                chat.value = c.policy.effective(threadId, s, r.ifEmpty { null })
                if (before != null && (before.target != chat.value?.target || before.autoTranslate != chat.value?.autoTranslate)) autoTranslate()
            }
        }
        viewModelScope.launch {
            c.translations.events.filter { it.threadId == threadId }.collect { e ->
                banner.value = when (e) {
                    is app.transmit.ai.TranslationManager.Event.BudgetReached -> e.message
                    is app.transmit.ai.TranslationManager.Event.Failed -> e.error.message
                }
            }
        }
        // Translations romanized as their bubbles come into view, a batch at a time.
        viewModelScope.launch {
            for (first in romanRequests) {
                delay(120)
                val keys = mutableSetOf(first)
                while (true) keys += romanRequests.tryReceive().getOrNull() ?: break
                val lang = chat.value?.target ?: continue
                try {
                    c.translations.romanize(threadId, keys, lang, auto = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Problems are reported through the translation events; keep serving later requests.
                }
            }
        }
        // A switch of how the practice language shows does what opening the chat does: with characters, the user's
        // own pinyin among the newest messages is written in them.
        viewModelScope.launch { script.filterNotNull().drop(1).collect { autoTranslate() } }
        // The practice-language box always shows its romanization.
        viewModelScope.launch {
            combine(compose.map { it.target }.distinctUntilChanged(), chat.map { it?.target }.distinctUntilChanged(), ::Pair)
                .collectLatest { (text, lang) -> updateReading(text, lang) }
        }
        viewModelScope.launch {
            compose.map { it.native to it.target }.distinctUntilChanged().debounce(600).collect { (n, t) ->
                if (n.isBlank() && t.isBlank()) c.dao.deleteDraft(threadId)
                else c.dao.upsertDraft(DraftEntity(threadId, n, t, System.currentTimeMillis()))
            }
        }
    }

    /** Loads run one at a time; requests made during one collapse into a single follow-up. */
    private fun reload() {
        reloads.trySend(Unit)
    }

    private suspend fun load() {
        // The newest screenful first so a long history doesn't hold up the conversation, then the full page.
        if (!loaded.value) {
            val first = c.messaging.messages(threadId, FIRST_SCREEN)
            quoted.value = quotedBy(first)
            raw.value = first
            if (first.any { m -> m.sender != null && m.sender !in people.value }) refreshPeople()
            loaded.value = true
        }
        val list = c.messaging.messages(threadId, limit)
        quoted.value = quotedBy(list)
        raw.value = list
        if (list.any { m -> m.sender != null && m.sender !in people.value }) refreshPeople()
        downloading.update { keys -> keys.filter { k -> list.any { it.key == k && it.mmsPending } }.toSet() }
        if (active) markRead()
        autoTranslate()
    }

    /** The messages replies in [list] quote that [list] doesn't hold, from the cache. */
    private suspend fun quotedBy(list: List<Message>): Map<String, Message> {
        val keys = list.mapNotNullTo(HashSet()) { it.replyTo }
        list.forEach { keys.remove(it.key) }
        return keys.mapNotNull { c.messaging.message(it) }.associateBy { it.key }
    }

    /**
     * Participants plus every sender in view. Senders are stored however the carrier wrote them
     * ("5551234567" in a thread whose participant is "+15551234567"), so each gets its own entry;
     * the contacts cache matches numbers loosely.
     */
    private suspend fun refreshPeople() {
        val addresses = (recipients.value + raw.value.mapNotNull { it.sender }).distinct()
        if (addresses.isNotEmpty()) people.value = c.contacts.lookupAll(addresses)
        groupPhoto.value = c.messaging.groupPhotoForThread(threadId)
    }

    private fun autoTranslate() {
        val list = raw.value
        if (list.isEmpty()) return
        viewModelScope.launch { c.translations.autoTranslate(threadId, list) }
    }

    fun loadMore() {
        // Only once the page asked for is in: flinging past the top asks many times, and so does the empty list.
        if (raw.value.size < limit) return
        limit += PAGE
        reload()
    }

    /**
     * Clears the conversation's unread state whenever it's on screen. Not only when a loaded message is
     * unread: the phone's unread flag for the conversation can be set while every message is read.
     */
    private fun markRead() {
        viewModelScope.launch {
            c.messaging.markThreadRead(threadId)
            c.notifier.cancel(threadId)
        }
    }

    // ---- message list --------------------------------------------------------------------------

    private fun build(
        desc: List<Message>,
        quoted: Map<String, Message>,
        tr: Map<String, TranslationEntity>,
        pending: Set<String>,
        views: Views,
        expected: Set<String>,
    ): List<MessageUi> {
        val asc = desc.asReversed()
        val reactions = HashMap<String, LinkedHashMap<String, MutableSet<String>>>()
        val visible = ArrayList<Message>(asc.size)
        for (m in asc) {
            val r = if (m.attachments.isEmpty()) Reactions.parse(m.body) else null
            if (r != null) {
                val target = Reactions.target(r, visible.asReversed())
                if (target != null) {
                    val reactor = if (m.outgoing) ME else m.sender?.let(PhoneNumbers::matchKey) ?: "?"
                    val map = reactions.getOrPut(target.key) { LinkedHashMap() }
                    if (r.removed) {
                        map[r.emoji]?.remove(reactor)
                    } else {
                        map.values.forEach { it.remove(reactor) }
                        map.getOrPut(r.emoji) { LinkedHashSet() }.add(reactor)
                    }
                    continue
                }
            }
            visible += m
        }
        val cal1 = Calendar.getInstance()
        val cal2 = Calendar.getInstance()
        fun sameDay(a: Long, b: Long): Boolean {
            cal1.timeInMillis = a
            cal2.timeInMillis = b
            return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
        }
        fun sameSender(a: String?, b: String?) = a == b || (a != null && b != null && PhoneNumbers.same(a, b))
        fun grouped(a: Message, b: Message) =
            a.outgoing == b.outgoing && sameSender(a.sender, b.sender) && kotlin.math.abs(b.date - a.date) < RUN_GAP && sameDay(a.date, b.date)

        val codes = HashMap<String, String>()
        for (m in visible) if (!m.outgoing) Otp.find(m.body)?.let { codes[m.key] = it.code }
        val chipKey = visible.lastOrNull { it.key in codes }?.takeIf { System.currentTimeMillis() - it.date < CODE_CHIP_MS }?.key

        val list = visible.mapIndexed { i, m ->
            val prev = visible.getOrNull(i - 1)
            val next = visible.getOrNull(i + 1)
            val translatable = c.translations.isTranslatable(m)
            val t = tr[m.key]
            // Someone who reads only the romanization waits for it, as for the translation.
            val romanWait = views.script == ScriptDisplay.ROMANIZED && !m.outgoing && t != null && t.origin != TranslationOrigin.SAME &&
                t.romanized == null && m.key !in views.romanFailed
            val r = t?.let { Languages.get(it.lang).romanization }
            val converting = m.key in views.conversions.running
            val needsCharacters = r != null && t.origin == TranslationOrigin.SELF && !converting && m.key !in views.conversions.tried &&
                (views.script == ScriptDisplay.CHARACTERS || views.script == ScriptDisplay.BOTH) && Scripts.isRomanized(t.text, r)
            MessageUi(
                message = m,
                reactions = m.reactions.map { ReactionUi(it.emoji, it.count, it.mine) }.let { native ->
                    // Google Messages tracks reactions itself; tapback texts it didn't recognize add to those.
                    native + reactions[m.key].orEmpty().filterValues { it.isNotEmpty() }
                        .filterKeys { e -> native.none { it.emoji == e } }
                        .map { (emoji, who) -> ReactionUi(emoji, who.size, ME in who) }
                },
                translation = t,
                showOriginal = m.key in views.original,
                firstInRun = prev == null || !grouped(prev, m),
                lastInRun = next == null || !grouped(m, next) || m.status == DeliveryStatus.FAILED,
                newDay = prev == null || !sameDay(prev.date, m.date),
                translatable = translatable,
                awaiting = (translatable && ((t == null && (m.key in pending || m.key in expected)) || romanWait)) || converting,
                code = codes[m.key],
                codeChip = m.key == chipKey,
                script = views.script,
                showRoman = m.key in views.roman,
                romanFailed = m.key in views.romanFailed,
                needsCharacters = needsCharacters,
            )
        }
        if (list.none { it.message.replyTo != null }) return list
        val byKey = list.associateBy { it.key }
        return list.map { item ->
            val key = item.message.replyTo ?: return@map item
            val quote = byKey[key]?.toQuote()
                ?: quoted[key]?.let { q ->
                    // Further back than the list: shown as its bubble would be, translated unless switched to the original.
                    val t = tr[q.key]
                    MessageUi(
                        message = q, reactions = emptyList(), translation = t, showOriginal = q.key in views.original,
                        firstInRun = true, lastInRun = true, newDay = false, translatable = c.translations.isTranslatable(q),
                        awaiting = false, script = views.script,
                    ).toQuote()
                }
                ?: Quote.missing(key)
            item.copy(quote = quote)
        }
    }

    /** Scrolls to the message a tapped quote shows, paging back through older messages until it's in the list. */
    fun showQuoted(quote: Quote) {
        val key = quote.key
        if (items.value.any { it.key == key }) {
            _jumps.tryEmit(key)
            return
        }
        viewModelScope.launch {
            val found = withTimeoutOrNull(FIND_QUOTED_MS) {
                items.first { list ->
                    val there = list.any { it.key == key }
                    // A full page is in, so there may be more behind it; a short one means the phone is fetching older ones.
                    if (!there && raw.value.size >= limit) {
                        limit += PAGE
                        reload()
                    }
                    there
                }
            }
            if (found != null) _jumps.emit(key) else toast("Couldn't find the original message")
        }
    }

    /**
     * Tap on a bubble: switches between original and translation, reveals a hidden one, or translates. With
     * characters only, the translation first gets its romanization underneath, then the original shows. The user's
     * own pinyin or romaji, beyond the newest messages, is written in characters.
     */
    fun toggleOriginal(item: MessageUi) {
        if (item.translation == null && item.translatable && !item.awaiting) {
            translate(item.message)
            return
        }
        if (item.needsCharacters && item.showingTranslation) {
            toCharacters(item)
            return
        }
        val key = item.key
        val tr = item.translation
        when {
            item.script == ScriptDisplay.CHARACTERS && item.showingTranslation && !item.showRoman && tr != null && Scripts.needsReading(tr.text) -> {
                showRoman.update { it + key }
                if (tr.romanized == null) romanize(item)
            }
            item.showRoman -> {
                showRoman.update { it - key }
                showOriginal.update { it + key }
            }
            else -> showOriginal.update { if (key in it) it - key else it + key }
        }
    }

    /** A bubble whose romanization should show came into view without one. */
    fun romanizeOnSight(key: String) {
        romanRequests.trySend(key)
    }

    private fun toCharacters(item: MessageUi, overrideBudget: Boolean = false) {
        val lang = chat.value?.target ?: return
        viewModelScope.launch {
            try {
                c.translations.toCharacters(threadId, listOf(item.key), lang, auto = false, overrideBudget = overrideBudget)
            } catch (e: BudgetExceededException) {
                budgetPrompt.value = BudgetPrompt(e.message.orEmpty()) { toCharacters(item, true) }
            } catch (e: AiException) {
                toast(e.message)
            }
        }
    }

    private fun romanize(item: MessageUi, overrideBudget: Boolean = false) {
        val lang = chat.value?.target ?: return
        viewModelScope.launch {
            try {
                c.translations.romanize(threadId, listOf(item.key), lang, auto = false, overrideBudget = overrideBudget)
            } catch (e: BudgetExceededException) {
                budgetPrompt.value = BudgetPrompt(e.message.orEmpty()) { romanize(item, true) }
            } catch (e: AiException) {
                toast(e.message)
            }
        }
    }

    fun translate(m: Message, refresh: Boolean = false, overrideBudget: Boolean = false) {
        val lang = chat.value?.target ?: return
        viewModelScope.launch {
            try {
                c.translations.translateNow(m, raw.value, lang, overrideBudget, refresh)
                showOriginal.update { it - m.key }
            } catch (e: BudgetExceededException) {
                budgetPrompt.value = BudgetPrompt(e.message.orEmpty()) { translate(m, refresh, true) }
            } catch (e: AiException) {
                toast(e.message)
            }
        }
    }

    /** Starts a reply to [item]; sending the draft sends it as one. */
    fun replyTo(item: MessageUi) {
        sheet.value = null
        if (item.message.replyable) compose.update { it.copy(replyTo = item.toQuote()) }
    }

    fun cancelReply() = compose.update { it.copy(replyTo = null) }

    fun react(item: MessageUi, emoji: String) {
        sheet.value = null
        viewModelScope.launch {
            runCatching { c.sender.react(item.message, emoji) }.onFailure { toast(sendError(it)) }
        }
    }

    fun explain(item: MessageUi, refresh: Boolean = false, overrideBudget: Boolean = false) {
        val lang = chat.value?.target ?: return
        val m = item.message
        val rendered = item.translation?.text
        if (rendered == null) {
            // Translate first, then explain the result.
            viewModelScope.launch {
                try {
                    c.translations.translateNow(m, raw.value, lang, overrideBudget)
                    items.value.firstOrNull { it.key == m.key }?.takeIf { it.translation != null }?.let { explain(it, refresh, overrideBudget) }
                } catch (e: BudgetExceededException) {
                    budgetPrompt.value = BudgetPrompt(e.message.orEmpty()) { explain(item, refresh, true) }
                } catch (e: AiException) {
                    toast(e.message)
                }
            }
            return
        }
        openExplanation(m.body, rendered, lang, "msg:${m.key}:${lang.code}", refresh, overrideBudget)
    }

    private fun openExplanation(original: String, rendered: String, lang: Language, key: String, refresh: Boolean, overrideBudget: Boolean) {
        sheet.value = Sheet.Explain(original, rendered, lang, key)
        viewModelScope.launch {
            try {
                val e = c.tutor.explain(original, rendered, lang, threadId, key, overrideBudget, refresh)
                sheet.update { s -> if (s is Sheet.Explain && s.cacheKey == key) s.copy(text = e.text, cost = e.cost, cached = e.cached, error = null) else s }
            } catch (e: BudgetExceededException) {
                sheet.update { s -> if (s is Sheet.Explain && s.cacheKey == key) s.copy(error = e.message) else s }
                budgetPrompt.value = BudgetPrompt(e.message.orEmpty()) { openExplanation(original, rendered, lang, key, refresh, true) }
            } catch (e: AiException) {
                sheet.update { s -> if (s is Sheet.Explain && s.cacheKey == key) s.copy(error = e.message) else s }
            }
        }
    }

    fun refreshExplanation() {
        val s = sheet.value as? Sheet.Explain ?: return
        openExplanation(s.original, s.rendered, s.lang, s.cacheKey, refresh = true, overrideBudget = false)
    }

    fun delete(m: Message) {
        sheet.value = null
        viewModelScope.launch { runCatching { c.messaging.deleteMessage(m) }.onFailure { toast(sendError(it, "delete")) } }
    }

    fun retry(m: Message) {
        sheet.value = null
        viewModelScope.launch { runCatching { c.sender.retry(m) }.onFailure { toast(sendError(it)) } }
    }

    /** MMS downloads happen on the phone; Google Messages for Web can't trigger one. */
    fun download(m: Message) {
        toast("Open Google Messages on your phone to download this message")
    }

    fun simLabel(subId: Int): String? = null

    val bridge = c.bridge.state

    fun useHere() = c.bridge.useHere()

    // ---- compose -------------------------------------------------------------------------------

    fun onNativeChange(value: String) = compose.update {
        it.copy(native = value, nativeAi = false, stale = it.targetAi && it.target.isNotBlank(), readyToSend = false)
    }

    // Feedback stays up while the user revises (the card diffs the live draft against the correction);
    // it goes away when dismissed, applied, re-checked, replaced by a translation, or sent.
    fun onTargetChange(value: String) = compose.update {
        it.copy(target = value, targetAi = false, stale = false, readyToSend = false)
    }

    fun clearTarget() = compose.update { it.copy(target = "", targetAi = false, stale = false) }

    fun clearNative() = compose.update { it.copy(native = "", nativeAi = false, readyToSend = false) }

    fun addAttachments(uris: List<Uri>) {
        val resolver = c.context.contentResolver
        val picked = uris.map { OutgoingAttachment(it, resolver.getType(it) ?: "application/octet-stream") }
        compose.update { it.copy(attachments = (it.attachments + picked).distinctBy { a -> a.uri }.take(10)) }
    }

    fun removeAttachment(a: OutgoingAttachment) = compose.update { it.copy(attachments = it.attachments - a) }

    fun applyCorrection(text: String) = compose.update { it.copy(target = text, targetAi = true, stale = false, feedback = null) }

    /** Keeps the romanization under the practice-language box in step with its [text]. */
    private suspend fun updateReading(text: String, lang: Language?) {
        val r = lang?.romanization
        if (r == null || text.isBlank()) {
            compose.update { it.copy(reading = null, readingBusy = false) }
            return
        }
        val known = Scripts.local(text.trim(), r) ?: romanCache[text.trim()]
        if (known != null) {
            compose.update { it.copy(reading = text to known, readingBusy = false) }
            return
        }
        compose.update { it.copy(readingBusy = true) }
        // Typing pauses before asking the AI; text it just wrote is romanized at once.
        if (!compose.value.targetAi) delay(READING_DELAY_MS)
        val reading = romanizeDraft(text, lang)
        compose.update { it.copy(reading = reading?.let { v -> text to v }, readingBusy = false) }
    }

    /** [text]'s romanization from the device, the cache or the AI; null when it can't be had. */
    private suspend fun romanizeDraft(text: String, lang: Language): String? {
        val r = lang.romanization ?: return null
        val key = text.trim()
        Scripts.local(key, r)?.let { return it }
        romanCache[key]?.let { return it }
        return try {
            c.tutor.romanize(listOf("1" to key), lang, threadId).texts["1"]?.also { romanCache[key] = it }
        } catch (e: AiException) {
            null
        } catch (e: BudgetExceededException) {
            null
        }
    }

    private fun rememberReading(text: String, romanized: String) {
        if (text.isNotBlank() && romanized.isNotBlank()) romanCache[text.trim()] = romanized.trim()
    }

    fun dismissFeedback() = compose.update { it.copy(feedback = null) }

    fun onAi(overrideBudget: Boolean = false) {
        val st = compose.value
        val eff = chat.value ?: return
        val s = settings.value ?: return
        when (st.aiMode) {
            AiMode.TRANSLATE -> runAi(Busy.TRANSLATE, { onAi(true) }) {
                val r = c.tutor.translate(listOf("1" to st.native.trim()), eff.target, contextLines(), threadId, Purpose.TRANSLATE_DRAFT, overrideBudget)
                val text = r.texts.getValue("1")
                // Someone who reads only the romanization gets the draft in it, characters if that fails.
                val romanized = if (s.scriptDisplay(eff.target) == ScriptDisplay.ROMANIZED) romanizeDraft(text, eff.target) else null
                compose.update { it.copy(target = romanized ?: text, targetAi = true, stale = false, feedback = null) }
            }
            AiMode.FEEDBACK -> runAi(Busy.FEEDBACK, { onAi(true) }) {
                val intended = st.native.takeIf { it.isNotBlank() && !st.nativeAi }
                val r = c.tutor.feedback(intended, st.target, eff.target, threadId, overrideBudget, s.scriptDisplay(eff.target))
                rememberReading(r.feedback.corrected, r.feedback.correctedRomanized)
                rememberReading(r.feedback.natural, r.feedback.naturalRomanized)
                compose.update { cur ->
                    val fillNative = (cur.native.isBlank() || cur.nativeAi) && r.feedback.translation.isNotBlank()
                    cur.copy(
                        feedback = r.feedback,
                        native = if (fillNative) r.feedback.translation else cur.native,
                        nativeAi = if (fillNative) true else cur.nativeAi,
                    )
                }
            }
            AiMode.EXPLAIN -> {
                val key = "draft:${(st.native + "|" + st.target + "|" + eff.target.code).hashCode()}"
                openExplanation(st.native.ifBlank { st.target }, st.target, eff.target, key, refresh = false, overrideBudget = overrideBudget)
            }
            AiMode.NONE -> Unit
        }
        if (s.native == eff.target) toast("Your native and practice languages are the same")
    }

    fun send(overrideBudget: Boolean = false) {
        val st = compose.value
        if (!st.canSend) return
        val eff = chat.value
        val s = settings.value
        if (st.native.isBlank() && st.target.isNotBlank() && eff != null && s != null) {
            // Friends get the native-language text, so prepare it first and let the user confirm.
            runAi(Busy.TRANSLATE, { send(true) }) {
                val r = c.tutor.translate(listOf("1" to st.target.trim()), s.native, contextLines(), threadId, Purpose.TRANSLATE_DRAFT, overrideBudget)
                compose.update { it.copy(native = r.texts.getValue("1"), nativeAi = true, readyToSend = true) }
            }
            return
        }
        compose.update { it.copy(busy = Busy.SEND) }
        viewModelScope.launch {
            try {
                val targetText = st.target.takeIf { it.isNotBlank() && !st.stale }
                val romanized = targetText?.let { st.reading?.takeIf { r -> r.first == st.target }?.second ?: romanCache[it.trim()] }
                // Stored before the message appears, so it never shows untranslated first.
                c.sender.sendToThread(threadId, st.native, st.attachments, replyTo = st.replyTo?.messageId) { key ->
                    if (targetText != null && eff != null) c.translations.storeSelf(key, threadId, eff.target, targetText, romanized)
                }
                compose.value = ComposeUi()
                c.dao.deleteDraft(threadId)
            } catch (e: Exception) {
                compose.update { it.copy(busy = null) }
                toast(sendError(e))
            }
        }
    }

    private fun sendError(e: Throwable, verb: String = "send"): String = when (e) {
        is AttachmentTooLargeException -> e.message.orEmpty()
        is NotConnectedException -> "Not connected to Google Messages"
        is PhoneNotRespondingException -> e.message.orEmpty()
        else -> "Couldn't $verb: ${e.message}"
    }

    private fun runAi(busy: Busy, retry: () -> Unit, block: suspend () -> Unit) {
        if (compose.value.busy != null) return
        compose.update { it.copy(busy = busy) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: BudgetExceededException) {
                budgetPrompt.value = BudgetPrompt(e.message.orEmpty(), retry)
            } catch (e: AiException) {
                toast(e.message)
            } catch (e: NoSuchElementException) {
                toast("The model returned no translation")
            } finally {
                compose.update { it.copy(busy = null) }
            }
        }
    }

    private fun contextLines(): List<ContextLine> {
        val n = settings.value?.contextMessages ?: AppSettings().contextMessages
        return raw.value.asSequence()
            .filter { it.body.isNotBlank() && Reactions.parse(it.body) == null }
            .take(n).toList().reversed()
            .map { ContextLine(it.outgoing, it.sender?.let { a -> people.value[a]?.name }, it.body) }
    }

    private fun toast(message: String?) {
        _toasts.tryEmit(message ?: "Something went wrong")
    }

    // ---- per-chat settings -----------------------------------------------------------------------

    private fun updateChat(block: (ChatSettingsEntity) -> ChatSettingsEntity) {
        viewModelScope.launch {
            val cur = c.dao.chatSettings(threadId) ?: ChatSettingsEntity(threadId)
            c.dao.upsertChatSettings(block(cur))
        }
    }

    fun setAutoTranslate(value: Boolean?) = updateChat { it.copy(autoTranslate = value) }
    fun setChatLanguage(code: String?) = updateChat { it.copy(targetLang = code) }
    fun setChatBudget(usd: Double?) = updateChat { it.copy(monthlyBudgetUsd = usd) }
    fun setPinned(value: Boolean) = updateChat { it.copy(pinned = value) }
    fun setMuted(value: Boolean) = updateChat { it.copy(muted = value) }
    fun setChatName(value: String?) = updateChat { it.copy(name = value?.trim()?.takeIf(String::isNotEmpty)) }

    /** For every chat in the languages written with [r]. */
    fun setScriptDisplay(r: Romanization, value: ScriptDisplay) {
        viewModelScope.launch { c.settings.setScriptDisplay(r, value) }
    }

    fun toggleAutoTranslate() {
        val now = chat.value?.autoTranslate ?: return
        setAutoTranslate(!now)
        toast(if (now) "Auto-translate off for this chat" else "Auto-translate on")
    }

    fun deleteConversation(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                c.messaging.deleteThread(threadId)
            } catch (e: Exception) {
                toast(sendError(e, "delete"))
                return@launch
            }
            c.dao.deleteDraft(threadId)
            // Android can reuse a deleted thread's id; a new chat must not inherit this one's name or settings.
            c.dao.deleteChatSettings(threadId)
            onDone()
        }
    }

    override fun onCleared() {
        if (c.notifier.activeThreadId == threadId) c.notifier.activeThreadId = null
    }

    private companion object {
        const val PAGE = 150
        const val FIRST_SCREEN = 30
        const val ME = "me"
        const val RUN_GAP = 5 * 60_000L
        const val CODE_CHIP_MS = 60 * 60_000L
        /** How long to page back for a tapped quote's original before giving up. */
        const val FIND_QUOTED_MS = 8_000L
        /** A pause in typing this long asks for the draft's romanization. */
        const val READING_DELAY_MS = 900L
    }
}
