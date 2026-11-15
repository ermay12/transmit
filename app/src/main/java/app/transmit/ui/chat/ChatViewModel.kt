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
import app.transmit.data.ContactInfo
import app.transmit.data.Language
import app.transmit.data.db.ChatSettingsEntity
import app.transmit.data.db.DraftEntity
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.telephony.AttachmentTooLargeException
import app.transmit.telephony.DeliveryStatus
import app.transmit.telephony.Message
import app.transmit.telephony.OutgoingAttachment
import app.transmit.telephony.PhoneNumbers
import app.transmit.telephony.Reactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val feedbackAttempt: String = "",
    /** The native text was just prepared from the target text; nudge the user to send. */
    val readyToSend: Boolean = false,
) {
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
) {
    val key get() = message.key
    /** True when the bubble currently shows the target-language text. */
    val showingTranslation get() = translation != null && !showOriginal && translation.origin != TranslationOrigin.SAME
    val displayText get() = if (showingTranslation) translation!!.text else message.body
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
    val people = MutableStateFlow<Map<String, ContactInfo>>(emptyMap())
    private val raw = MutableStateFlow<List<Message>>(emptyList())
    val loaded = MutableStateFlow(false)
    private var limit = PAGE
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val chat = MutableStateFlow<EffectiveChat?>(null)
    val showOriginal = MutableStateFlow<Set<String>>(emptySet())
    val compose = MutableStateFlow(ComposeUi(native = initialText.orEmpty(), attachments = initialAttachments))
    val sheet = MutableStateFlow<Sheet?>(null)
    val budgetPrompt = MutableStateFlow<BudgetPrompt?>(null)
    val banner = MutableStateFlow<String?>(null)
    val downloading = MutableStateFlow<Set<String>>(emptySet())
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts

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

    val items: StateFlow<List<MessageUi>> =
        combine(raw, translations, c.translations.pending, showOriginal, expected) { list, tr, pending, orig, exp ->
            build(list, tr, pending, orig, exp)
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
            raw.value = first
            if (first.any { m -> m.sender != null && m.sender !in people.value }) refreshPeople()
            loaded.value = true
        }
        val list = c.messaging.messages(threadId, limit)
        raw.value = list
        if (list.any { m -> m.sender != null && m.sender !in people.value }) refreshPeople()
        downloading.update { keys -> keys.filter { k -> list.any { it.key == k && it.mmsPending } }.toSet() }
        if (active) markRead()
        autoTranslate()
    }

    /**
     * Participants plus every sender in view. Senders are stored however the carrier wrote them
     * ("5551234567" in a thread whose participant is "+15551234567"), so each gets its own entry;
     * the contacts cache matches numbers loosely.
     */
    private suspend fun refreshPeople() {
        val addresses = (recipients.value + raw.value.mapNotNull { it.sender }).distinct()
        if (addresses.isNotEmpty()) people.value = c.contacts.lookupAll(addresses)
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

    private fun markRead() {
        if (raw.value.none { !it.read && !it.outgoing }) {
            c.notifier.cancel(threadId)
            return
        }
        viewModelScope.launch {
            c.messaging.markThreadRead(threadId)
            c.notifier.cancel(threadId)
        }
    }

    // ---- message list --------------------------------------------------------------------------

    private fun build(
        desc: List<Message>,
        tr: Map<String, TranslationEntity>,
        pending: Set<String>,
        orig: Set<String>,
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

        return visible.mapIndexed { i, m ->
            val prev = visible.getOrNull(i - 1)
            val next = visible.getOrNull(i + 1)
            val translatable = c.translations.isTranslatable(m)
            MessageUi(
                message = m,
                reactions = reactions[m.key].orEmpty().filterValues { it.isNotEmpty() }
                    .map { (emoji, who) -> ReactionUi(emoji, who.size, ME in who) },
                translation = tr[m.key],
                showOriginal = m.key in orig,
                firstInRun = prev == null || !grouped(prev, m),
                lastInRun = next == null || !grouped(m, next) || m.status == DeliveryStatus.FAILED,
                newDay = prev == null || !sameDay(prev.date, m.date),
                translatable = translatable,
                awaiting = tr[m.key] == null && translatable && (m.key in pending || m.key in expected),
            )
        }
    }

    /** Tap on a bubble: switches between original and translation, reveals a hidden one, or translates. */
    fun toggleOriginal(item: MessageUi) {
        if (item.translation == null && item.translatable && !item.awaiting) {
            translate(item.message)
            return
        }
        showOriginal.update { if (item.key in it) it - item.key else it + item.key }
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

    fun react(item: MessageUi, emoji: String) {
        sheet.value = null
        val mine = item.reactions.firstOrNull { it.mine }
        val m = item.message
        val text = Reactions.format(emoji, m.body, targetIsMedia = m.attachments.isNotEmpty(), remove = mine?.emoji == emoji)
        viewModelScope.launch {
            runCatching { c.sender.send(recipients.value, text) }.onFailure { toast(sendError(it)) }
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
        viewModelScope.launch { c.messaging.deleteMessage(m) }
    }

    fun retry(m: Message) {
        sheet.value = null
        viewModelScope.launch { runCatching { c.sender.retry(m) }.onFailure { toast(sendError(it)) } }
    }

    fun download(m: Message) {
        downloading.update { it + m.key }
        c.incoming.retryDownload(m)
    }

    fun simLabel(subId: Int): String? = c.subscriptions.label(subId)

    // ---- compose -------------------------------------------------------------------------------

    fun onNativeChange(value: String) = compose.update {
        it.copy(native = value, nativeAi = false, stale = it.targetAi && it.target.isNotBlank(), readyToSend = false)
    }

    fun onTargetChange(value: String) = compose.update {
        it.copy(target = value, targetAi = false, stale = false, readyToSend = false, feedback = if (value == it.feedbackAttempt) it.feedback else null)
    }

    fun clearTarget() = compose.update { it.copy(target = "", targetAi = false, stale = false, feedback = null) }

    fun clearNative() = compose.update { it.copy(native = "", nativeAi = false, readyToSend = false) }

    fun addAttachments(uris: List<Uri>) {
        val resolver = c.context.contentResolver
        val picked = uris.map { OutgoingAttachment(it, resolver.getType(it) ?: "application/octet-stream") }
        compose.update { it.copy(attachments = (it.attachments + picked).distinctBy { a -> a.uri }.take(10)) }
    }

    fun removeAttachment(a: OutgoingAttachment) = compose.update { it.copy(attachments = it.attachments - a) }

    fun applyCorrection(text: String) = compose.update { it.copy(target = text, targetAi = true, stale = false, feedback = null) }

    fun dismissFeedback() = compose.update { it.copy(feedback = null) }

    fun onAi(overrideBudget: Boolean = false) {
        val st = compose.value
        val eff = chat.value ?: return
        val s = settings.value ?: return
        when (st.aiMode) {
            AiMode.TRANSLATE -> runAi(Busy.TRANSLATE, { onAi(true) }) {
                val r = c.tutor.translate(listOf("1" to st.native.trim()), eff.target, contextLines(), threadId, Purpose.TRANSLATE_DRAFT, overrideBudget)
                val text = r.texts.getValue("1")
                compose.update { it.copy(target = text, targetAi = true, stale = false, feedback = null) }
            }
            AiMode.FEEDBACK -> runAi(Busy.FEEDBACK, { onAi(true) }) {
                val intended = st.native.takeIf { it.isNotBlank() && !st.nativeAi }
                val r = c.tutor.feedback(intended, st.target, eff.target, threadId, overrideBudget)
                compose.update { cur ->
                    val fillNative = (cur.native.isBlank() || cur.nativeAi) && r.feedback.translation.isNotBlank()
                    cur.copy(
                        feedback = r.feedback,
                        feedbackAttempt = st.target,
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
        val to = recipients.value
        if (to.isEmpty()) return
        compose.update { it.copy(busy = Busy.SEND) }
        viewModelScope.launch {
            try {
                val result = c.sender.send(to, st.native, st.attachments)
                val targetText = st.target.takeIf { it.isNotBlank() && !st.stale }
                if (targetText != null && eff != null) c.translations.storeSelf(result.key, threadId, eff.target, targetText)
                compose.value = ComposeUi()
                c.dao.deleteDraft(threadId)
            } catch (e: Exception) {
                compose.update { it.copy(busy = null) }
                toast(sendError(e))
            }
        }
    }

    private fun sendError(e: Throwable): String = when {
        e is AttachmentTooLargeException -> e.message.orEmpty()
        e is SecurityException || !c.isDefaultSmsApp() -> "Make Transmit your default SMS app to send messages"
        else -> "Couldn't send: ${e.message}"
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
        val n = settings.value?.contextMessages ?: 4
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

    fun toggleAutoTranslate() {
        val now = chat.value?.autoTranslate ?: return
        setAutoTranslate(!now)
        toast(if (now) "Auto-translate off for this chat" else "Auto-translate on")
    }

    fun deleteConversation(onDone: () -> Unit) {
        viewModelScope.launch {
            c.messaging.deleteThread(threadId)
            c.dao.deleteDraft(threadId)
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
    }
}
