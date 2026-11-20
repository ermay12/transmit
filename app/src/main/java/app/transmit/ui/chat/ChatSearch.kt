package app.transmit.ui.chat

import app.transmit.data.ScriptDisplay
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.data.db.shownWithReading
import app.transmit.messaging.Message
import app.transmit.messaging.Reactions
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Search within a conversation (docs/product/chat-extras.md, section 1). It reads the messages cached for the chat
 * and the translations already stored for them, and never asks the AI for anything.
 */

/** [text] folded for matching, and for each of its chars, the range of the source string it came from. */
internal class FoldedText(val text: String, private val starts: IntArray?, private val ends: IntArray?) {
    /** The range of the source string that folded chars [start] until [end] came from. */
    fun source(start: Int, end: Int): IntRange =
        if (starts == null || ends == null) start until end else starts[start] until ends[end - 1]
}

/**
 * Matching that ignores case and accents, so "cafe" finds "café" and "ni hao" finds "nǐ hǎo". Accents are dropped
 * only from Latin, Greek, Cyrillic, Arabic and Hebrew letters, where they're marks a learner may leave out; in other
 * scripts a combining mark changes the letter (Japanese dakuten: カ and ガ), so it stays. CJK is matched as typed.
 */
internal object SearchText {
    /** [s] as it's compared: lower case, accents dropped. */
    fun normalize(s: String): String = fold(s, mapped = false).text

    /** [normalize], keeping where each char came from. */
    fun fold(s: String): FoldedText = fold(s, mapped = true)

    /** Every match of [query] in [text], as ranges of [text]; none for a blank query. */
    fun find(text: String, query: String): List<IntRange> {
        val q = normalize(query.trim())
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val folded = fold(text)
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = folded.text.indexOf(q, from)
            if (i < 0) break
            out += folded.source(i, i + q.length)
            from = i + q.length
        }
        return out
    }

    private fun fold(s: String, mapped: Boolean): FoldedText {
        if (s.all { it.code < 0x80 }) return FoldedText(s.lowercase(), null, null)
        val out = StringBuilder(s.length)
        var starts = IntArray(if (mapped) s.length else 0)
        var ends = IntArray(if (mapped) s.length else 0)
        /** The source range of the last char written; a dropped mark after it widens it. */
        fun emit(cp: Int, from: Int, to: Int) {
            val before = out.length
            out.appendCodePoint(cp)
            if (!mapped) return
            if (out.length > starts.size) {
                starts = starts.copyOf(maxOf(out.length, starts.size * 2))
                ends = ends.copyOf(starts.size)
            }
            for (j in before until out.length) {
                starts[j] = from
                ends[j] = to
            }
        }
        fun widen(to: Int) {
            if (mapped && out.isNotEmpty()) ends[out.length - 1] = to
        }
        // Whether the last letter was one whose accents are dropped, for marks that follow it on their own.
        var accented = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val next = i + Character.charCount(cp)
            when {
                cp < 0x80 -> {
                    emit(if (cp in 'A'.code..'Z'.code) cp + 32 else cp, i, next)
                    accented = Character.isLetter(cp)
                }
                // Emoji presentation selectors: "❤️" and "❤" are the same heart.
                cp == 0xFE0E || cp == 0xFE0F -> widen(next)
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> if (accented) widen(next) else emit(cp, i, next)
                cp.dropsAccents() -> {
                    val decomposed = decompositions.getOrPut(cp) { Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD) }
                    var k = 0
                    var wrote = false
                    while (k < decomposed.length) {
                        val c = decomposed.codePointAt(k)
                        k += Character.charCount(c)
                        if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
                        emit(c.foldCase(), i, next)
                        wrote = true
                    }
                    if (!wrote) widen(next)
                    accented = true
                }
                else -> {
                    emit(Character.toLowerCase(cp), i, next)
                    accented = false
                }
            }
            i = next
        }
        return if (mapped) FoldedText(out.toString(), starts, ends) else FoldedText(out.toString(), null, null)
    }

    private val decompositions = ConcurrentHashMap<Int, String>()

    private fun Int.dropsAccents(): Boolean = when (Character.UnicodeScript.of(this)) {
        Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW -> true
        else -> false
    }

    /** Lower case, and the letters that don't decompose into a base and an accent: ł, ø, đ, ı, and final ς. */
    private fun Int.foldCase(): Int = when (val lower = Character.toLowerCase(this)) {
        0x0142 -> 'l'.code
        0x00F8 -> 'o'.code
        0x0111 -> 'd'.code
        0x0131 -> 'i'.code
        0x03C2 -> 0x03C3
        else -> lower
    }
}

/** One message as search sees it: the folded text of each side of its bubble, null where that side has none. */
internal class SearchDoc(val key: String, val position: Int, val original: String?, val translation: String?)

/**
 * A message that matches, in [ChatSearchIndex.hits] order (newest first). [position] is where it is among the
 * chat's cached messages, newest first; [inOriginal] and [inTranslation] say which side of its bubble matches.
 */
data class SearchHit(val key: String, val position: Int, val inOriginal: Boolean, val inTranslation: Boolean)

internal object ChatSearchIndex {
    /**
     * Messages search can match: not reactions sent as text ('Loved “…”'), which show as a reaction rather than a
     * bubble, and not MMS still on the phone, whose text isn't there yet.
     */
    fun searchable(m: Message): Boolean = !m.mmsPending && !(m.attachments.isEmpty() && Reactions.parse(m.body) != null)

    /**
     * [messages] (newest first) as search sees them. A bubble's other side is its stored translation into the chat's
     * language, as [script] shows it: with the romanization where that shows. A translation that is the same text
     * ([TranslationOrigin.SAME]) adds nothing.
     */
    fun docs(
        messages: List<Message>,
        translations: Map<String, TranslationEntity>,
        script: ScriptDisplay?,
        fold: (String) -> String = SearchText::normalize,
    ): List<SearchDoc> {
        val out = ArrayList<SearchDoc>(messages.size)
        messages.forEachIndexed { i, m ->
            if (!searchable(m)) return@forEachIndexed
            val original = m.body.takeIf { it.isNotBlank() }?.let(fold)
            val translation = translations[m.key]?.takeIf { it.origin != TranslationOrigin.SAME }?.shownWithReading(script)
                ?.takeIf { it.isNotBlank() }?.let(fold)
            if (original != null || translation != null) out += SearchDoc(m.key, i, original, translation)
        }
        return out
    }

    /** The messages among [docs] that [query] matches, newest first. */
    fun hits(docs: List<SearchDoc>, query: String): List<SearchHit> {
        val q = SearchText.normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        return docs.mapNotNull { d ->
            val inOriginal = d.original?.contains(q) == true
            val inTranslation = d.translation?.contains(q) == true
            if (inOriginal || inTranslation) SearchHit(d.key, d.position, inOriginal, inTranslation) else null
        }
    }
}

enum class SearchStatus {
    NONE,
    /** Older messages are being fetched from the phone to look for an older match. */
    LOOKING_BACK,
    /** Nothing older matched. */
    NO_MORE,
}

/** What the search bar and the list show while searching. */
data class SearchUi(
    val open: Boolean = false,
    /** The text [hits] are for: what's typed, trimmed, once typing pauses. */
    val query: String = "",
    /** Matching messages, newest first. */
    val hits: List<SearchHit> = emptyList(),
    /** Which of [hits] is current; 0 is the newest. */
    val index: Int = 0,
    /** [hits] are in for [query], so "No matches" doesn't show before the messages are read. */
    val searched: Boolean = false,
    /** How many cached messages [hits] were found among. */
    val corpus: Int = 0,
    val status: SearchStatus = SearchStatus.NONE,
    /** Goes up each time the list should go to the current hit. */
    val jump: Int = 0,
) {
    private val byKey: Map<String, SearchHit> by lazy { hits.associateBy { it.key } }

    val current: SearchHit? get() = hits.getOrNull(index)
    val noMatches: Boolean get() = searched && query.isNotEmpty() && hits.isEmpty()
    /** Up: to an older match, or to look further back from the oldest. */
    val canGoOlder: Boolean get() = hits.isNotEmpty() && status != SearchStatus.LOOKING_BACK
    /** Down: to a newer match. */
    val canGoNewer: Boolean get() = index > 0

    fun hit(key: String): SearchHit? = byKey[key]

    /** How [key]'s bubble marks its matches; null when it has none. */
    fun markFor(key: String): SearchMark? = hit(key)?.let { SearchMark(query, current = it.key == current?.key) }

    /** The next older match; null from the oldest. */
    fun older(): SearchUi? = if (index < hits.lastIndex) copy(index = index + 1, status = SearchStatus.NONE, jump = jump + 1) else null

    /** The next newer match; null from the newest. */
    fun newer(): SearchUi? = if (index > 0) copy(index = index - 1, status = SearchStatus.NONE, jump = jump + 1) else null

    /** The newest match (the keyboard's search key). */
    fun newest(): SearchUi = copy(index = 0, status = SearchStatus.NONE, jump = if (hits.isEmpty()) jump else jump + 1)

    /**
     * [hits] for [query] among [corpus] messages. A new query starts at the newest match; for the same query (a
     * message came in, or a translation) the current match stays current.
     */
    fun withHits(query: String, hits: List<SearchHit>, corpus: Int): SearchUi {
        if (query != this.query) {
            return copy(
                query = query, hits = hits, index = 0, searched = true, corpus = corpus, status = SearchStatus.NONE,
                jump = if (hits.isEmpty()) jump else jump + 1,
            )
        }
        val key = current?.key
        val i = hits.indexOfFirst { it.key == key }
        return copy(hits = hits, index = if (i >= 0) i else index.coerceAtMost(hits.lastIndex).coerceAtLeast(0), searched = true, corpus = corpus)
    }
}

/**
 * How a bubble marks matches of [query]: [current] ones more strongly. [revealed]: the bubble shows its other side
 * for the current match ([revealing]), so the change fades as a translation's arrival does.
 */
data class SearchMark(val query: String, val current: Boolean, val revealed: Boolean = false)

/** [item] as its bubble shows while searching ([revealing] the current match), and how it marks its matches. */
internal fun SearchUi.bubble(item: MessageUi): Pair<MessageUi, SearchMark?> {
    val shown = item.revealing(current)
    return shown to markFor(item.key)?.let { if (shown !== item) it.copy(revealed = true) else it }
}

/**
 * This bubble as it shows while [hit] is the current match: when the match is only on the side that isn't showing
 * (the original under a translation, or the translation under the original), that side; afterwards, as it was.
 */
internal fun MessageUi.revealing(hit: SearchHit?): MessageUi {
    if (hit == null || hit.key != key) return this
    val hidden = awaiting && !showOriginal
    val hasTranslation = translation != null && translation.origin != TranslationOrigin.SAME
    return when {
        // Its translation (or the translation's romanization) is on its way, so nothing shows: the matching side does.
        hidden -> when {
            hit.inOriginal -> copy(showOriginal = true)
            hit.inTranslation && hasTranslation -> copy(awaiting = false)
            else -> this
        }
        showingTranslation -> if (!hit.inTranslation && hit.inOriginal) copy(showOriginal = true) else this
        else -> if (!hit.inOriginal && hit.inTranslation && hasTranslation) copy(showOriginal = false) else this
    }
}

/**
 * The chat's search: [open], [type], then [older] and [newer] through the matches. It reads [cached], every cached
 * message of the chat, newest first, again whenever [changes] says the cache changed; matches take their
 * translations from [translations] and show them as [script] does. Past the oldest match, [requestOlder] asks the
 * phone for older messages, a few pages at most; [reveal] loads the newest so many messages into the chat's list,
 * so a match further back is in it.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ChatSearch(
    private val scope: CoroutineScope,
    private val cached: suspend () -> List<Message>,
    private val translations: Flow<Map<String, TranslationEntity>>,
    private val script: Flow<ScriptDisplay?>,
    private val changes: Flow<Unit>,
    /** Asks for messages older than the oldest cached; false when there's nothing older to ask for. */
    private val requestOlder: () -> Boolean,
    private val reveal: (count: Int) -> Unit,
    private val work: CoroutineContext = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(SearchUi())
    val state: StateFlow<SearchUi> = _state.asStateFlow()

    private val typed = MutableStateFlow("")
    private var job: Job? = null
    private var lookBack: Job? = null
    /** The phone said there's nothing older: further looks back go straight to "No more matches". */
    private var noOlder = false
    /** Work that waits until search closes (romanizing what came into view): search makes no AI calls. */
    private val deferred = LinkedHashMap<String, () -> Unit>()

    fun open() {
        if (_state.value.open) return
        _state.value = SearchUi(open = true)
        typed.value = ""
        noOlder = false
        job = scope.launch {
            val messages = MutableStateFlow<List<Message>?>(null)
            launch { merge(flowOf(Unit), changes.debounce(CHANGES_MS)).collectLatest { messages.value = cached() } }
            // Folded texts by source text: a translation or a new message doesn't refold the rest.
            val folds = HashMap<String, String>()
            val docs = combine(messages.filterNotNull(), translations, script) { list, tr, s ->
                list.size to ChatSearchIndex.docs(list, tr, s) { text -> folds.getOrPut(text) { SearchText.normalize(text) } }
            }.flowOn(work)
            val queries = typed.debounce(TYPING_MS).map { it.trim() }.distinctUntilChanged()
            combine(docs, queries, ::Pair)
                .mapLatest { (d, q) -> Triple(q, ChatSearchIndex.hits(d.second, q), d.first) }
                .flowOn(work)
                .collect { (q, hits, corpus) -> found(q, hits, corpus) }
        }
    }

    fun close() {
        job?.cancel()
        lookBack?.cancel()
        job = null
        lookBack = null
        _state.value = SearchUi()
        typed.value = ""
        val later = deferred.values.toList()
        deferred.clear()
        later.forEach { it() }
    }

    /** The search field's text changed. */
    fun type(text: String) {
        typed.value = text
    }

    /** The keyboard's search key: the newest match. */
    fun newest() = go(_state.value.newest())

    /** Down: the next newer match. */
    fun newer() {
        val next = _state.value.newer() ?: return
        lookBack?.cancel()
        go(next)
    }

    /** Up: the next older match, or past the oldest, a bounded look further back on the phone. */
    fun older() {
        val s = _state.value
        if (!s.open || !s.canGoOlder) return
        s.older()?.let {
            go(it)
            return
        }
        if (noOlder) {
            _state.value = s.copy(status = SearchStatus.NO_MORE)
            return
        }
        lookBack = scope.launch { lookFurtherBack() }
    }

    /** Runs [action] now, or once search closes; a later one for the same [key] replaces it. */
    fun afterSearch(key: String, action: () -> Unit) {
        if (_state.value.open) deferred[key] = action else action()
    }

    private fun found(query: String, hits: List<SearchHit>, corpus: Int) {
        val before = _state.value
        if (!before.open) return
        if (query != before.query) lookBack?.cancel()
        go(before.withHits(query, hits, corpus))
    }

    private fun go(next: SearchUi) {
        val before = _state.value
        _state.value = next
        if (next.jump != before.jump) next.current?.let { reveal(it.position + 1) }
    }

    private suspend fun lookFurtherBack() {
        val oldest = _state.value.hits.lastOrNull()?.key ?: return
        _state.update { it.copy(status = SearchStatus.LOOKING_BACK) }
        for (page in 1..LOOK_BACK_PAGES) {
            val corpus = _state.value.corpus
            if (!requestOlder()) {
                noOlder = true
                break
            }
            // The page lands in the cache, which is read and searched again.
            val grown = withTimeoutOrNull(PAGE_MS) { _state.first { it.corpus > corpus } } ?: break
            val i = grown.hits.indexOfFirst { it.key == oldest }
            if (i < 0) break
            if (i < grown.hits.lastIndex) {
                go(grown.copy(index = i + 1, status = SearchStatus.NONE, jump = grown.jump + 1))
                return
            }
        }
        _state.update { if (it.status == SearchStatus.LOOKING_BACK) it.copy(status = SearchStatus.NO_MORE) else it }
    }

    internal companion object {
        const val TYPING_MS = 250L
        const val CHANGES_MS = 300L
        /** Pages (of 50 messages) fetched from the phone for one step past the oldest match. */
        const val LOOK_BACK_PAGES = 4
        /** How long one page may take to arrive before the look back stops. */
        const val PAGE_MS = 10_000L
    }
}
