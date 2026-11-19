package app.transmit.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.transmit.AppContainer
import app.transmit.data.ContactInfo
import app.transmit.data.Languages
import app.transmit.data.chatTitle
import app.transmit.data.db.ChatSettingsEntity
import app.transmit.data.db.shown
import app.transmit.messaging.Conversation
import app.transmit.messaging.Reactions
import app.transmit.ui.access.BannerDismiss
import app.transmit.ui.access.TrialStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUi(
    val conversation: Conversation,
    val people: List<ContactInfo>,
    val title: String,
    val snippet: String,
    val snippetTranslated: Boolean,
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
    val draft: String?,
) {
    val threadId get() = conversation.threadId
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    private val conversations = MutableStateFlow<List<Conversation>?>(null)
    val bridge = c.bridge.state
    /** False until the phone's conversation list has arrived, so an empty list isn't mistaken for no chats. */
    val synced = c.bridge.synced
    val canRead = MutableStateFlow(c.messaging.canRead())
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val showArchived = MutableStateFlow(false)
    /** False until every conversation is loaded; the newest few show first. */
    val complete = MutableStateFlow(false)
    private var loadJob: Job? = null

    val spent: StateFlow<Double> = c.dao.spentSinceFlow(c.budget.periodStart())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val access = c.access.access
    val allowance = c.access.allowance
    val warningDismissed = c.access.warningDismissed
    val offBannerDismissed = c.access.offBannerDismissed

    fun dismissBanner(kind: BannerDismiss) {
        c.scope.launch {
            when (kind) {
                BannerDismiss.TRANSLATION_OFF -> c.access.dismissOffBanner()
                BannerDismiss.NEARLY_USED_UP -> c.access.dismissWarning()
            }
        }
    }

    val canBuy get() = c.purchases.available

    /** "Start your free week" on the translation-is-off banner. */
    val trial = TrialStarter(viewModelScope, c.access)

    /** Takes the subscription back from the other device that has it. */
    fun useSubscriptionHere() {
        c.scope.launch { c.access.useHere() }
    }

    /** Corrects the allowance with the backend's figure when the app comes back. */
    fun reconcileAllowance() {
        c.scope.launch { c.access.reconcile() }
    }

    /**
     * Reaction snippet key → key of the message it reacts to (null when not found), so the preview
     * can quote that message's translation. Found in the background; a reaction's target never changes.
     */
    private val reactionTargets = MutableStateFlow<Map<String, String?>>(emptyMap())

    private val snippetTranslations = combine(conversations.filterNotNull(), reactionTargets) { list, targets ->
        list.flatMap { conv -> listOfNotNull(conv.snippetKey, conv.snippetKey?.let(targets::get)) }.take(900)
    }.distinctUntilChanged().flatMapLatest { keys ->
        if (keys.isEmpty()) flowOf(emptyList()) else c.dao.translationsForKeys(keys)
    }

    val items: StateFlow<List<ConversationUi>?> = combine(
        combine(conversations, reactionTargets, ::Pair), c.dao.allChatSettings(), c.dao.drafts(), snippetTranslations, c.contacts.version,
    ) { (list, targets), chatSettings, drafts, translations, _ ->
        if (list == null) return@combine null
        val s = c.settings.current()
        val cs = chatSettings.associateBy { it.threadId }
        val draftMap = drafts.associateBy { it.threadId }
        val byKey = translations.groupBy { it.msgKey }
        c.contacts.prefetch(list.flatMap { it.recipients })
        list.map { conv ->
            val people = conv.recipients.map { c.contacts.lookup(it) }
            val settings: ChatSettingsEntity? = cs[conv.threadId]
            val lang = settings?.targetLang ?: s.targetLang
            val reaction = Reactions.parse(conv.snippet)
            // A reaction quotes its target in the original language; use the target's translation.
            val translatedKey = if (reaction != null) conv.snippetKey?.let(targets::get) else conv.snippetKey
            val translation = translatedKey?.let { key -> byKey[key]?.firstOrNull { it.lang == lang } }
            val shown = translation?.shown(s.scriptDisplay(Languages.get(lang)))
            ConversationUi(
                conversation = conv,
                people = people,
                title = chatTitle(people, settings?.name),
                snippet = when {
                    reaction != null -> "${reaction.emoji} “${shown ?: reaction.quoted ?: "📷"}”"
                    shown != null -> shown
                    else -> conv.snippet
                },
                snippetTranslated = translation != null,
                pinned = settings?.pinned == true,
                muted = settings?.muted == true,
                archived = settings?.archived(conv.date) == true,
                draft = draftMap[conv.threadId]?.let { d -> d.native.ifBlank { d.target }.takeIf { it.isNotBlank() } },
            )
        }.sortedWith(compareByDescending<ConversationUi> { it.pinned }.thenByDescending { it.conversation.date })
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Search covers archived conversations too. */
    val filtered: StateFlow<List<ConversationUi>?> = combine(items, query, showArchived) { list, q, archived ->
        if (list == null) return@combine null
        if (q.isBlank()) return@combine list.filter { it.archived == archived }
        val needle = q.trim().lowercase()
        val digits = needle.filter { it.isDigit() }
        list.filter { item ->
            item.title.lowercase().contains(needle) ||
                item.people.any { it.name?.lowercase()?.contains(needle) == true } ||
                item.snippet.lowercase().contains(needle) ||
                item.conversation.snippet.lowercase().contains(needle) ||
                (digits.length >= 3 && item.conversation.recipients.any { it.filter(Char::isDigit).contains(digits) })
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val archivedCount: StateFlow<Int> = items.map { list -> list.orEmpty().count { it.archived } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    init {
        refresh()
        viewModelScope.launch {
            c.messaging.changes.debounce(250).collect { refresh() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            conversations.filterNotNull().collect { list -> findReactionTargets(list) }
        }
    }

    private suspend fun findReactionTargets(list: List<Conversation>) {
        val known = reactionTargets.value
        val found = list.mapNotNull { conv ->
            val key = conv.snippetKey?.takeIf { it !in known } ?: return@mapNotNull null
            val r = Reactions.parse(conv.snippet)?.takeIf { it.quoted != null } ?: return@mapNotNull null
            key to c.messaging.reactionTarget(conv.threadId, conv.date, r)?.key
        }
        if (found.isNotEmpty()) reactionTargets.update { it + found }
    }

    fun refresh() {
        canRead.value = c.messaging.canRead()
        // The first load reads the latest state anyway; restarting it would only delay the list.
        if (conversations.value == null && loadJob?.isActive == true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // A screenful first so a long history doesn't hold up the list, then the rest.
            if (conversations.value == null) conversations.value = c.messaging.conversations(limit = FIRST_PAGE)
            conversations.value = c.messaging.conversations()
            complete.value = true
        }
    }

    fun useHere() = c.bridge.useHere()

    fun toggleSelect(threadId: Long) {
        selection.value = selection.value.let { if (threadId in it) it - threadId else it + threadId }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun markSelectedRead() = forSelected { c.messaging.markThreadRead(it); c.notifier.cancel(it) }

    fun deleteSelected() = forSelected { c.messaging.deleteThread(it); c.dao.deleteDraft(it); c.dao.deleteChatSettings(it) }

    fun archiveSelected() {
        val ids = selection.value
        selection.value = emptySet()
        setArchived(ids, !showArchived.value)
    }

    /** Archives or restores [ids]; archived chats remember their newest message so later ones bring them back. */
    fun setArchived(ids: Set<Long>, archived: Boolean) {
        val dates = items.value.orEmpty().filter { it.threadId in ids }.associate { it.threadId to it.conversation.date }
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) {
                val cur = c.dao.chatSettings(id) ?: ChatSettingsEntity(id)
                c.dao.upsertChatSettings(cur.copy(archivedAt = if (archived) dates[id] ?: System.currentTimeMillis() else null))
            }
        }
    }

    fun pinSelected() {
        val items = items.value.orEmpty().filter { it.threadId in selection.value }
        val pin = items.any { !it.pinned }
        forSelected { id ->
            val cur = c.dao.chatSettings(id) ?: ChatSettingsEntity(id)
            c.dao.upsertChatSettings(cur.copy(pinned = pin))
        }
    }

    fun muteSelected() {
        val items = items.value.orEmpty().filter { it.threadId in selection.value }
        val mute = items.any { !it.muted }
        forSelected { id ->
            val cur = c.dao.chatSettings(id) ?: ChatSettingsEntity(id)
            c.dao.upsertChatSettings(cur.copy(muted = mute))
        }
    }

    private fun forSelected(block: suspend (Long) -> Unit) {
        val ids = selection.value
        selection.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id -> runCatching { block(id) }.onFailure { android.util.Log.w("Home", "Action on $id failed", it) } }
            refresh()
        }
    }

    fun targetFlag(): String = Languages.get(settings.value?.targetLang).flag

    private companion object {
        const val FIRST_PAGE = 40
    }
}
