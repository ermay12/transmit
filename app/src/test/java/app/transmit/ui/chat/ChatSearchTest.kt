package app.transmit.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import app.transmit.data.ScriptDisplay
import app.transmit.data.db.TranslationEntity
import app.transmit.data.db.TranslationOrigin
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Message
import app.transmit.messaging.MessageKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {
    private fun message(key: String, body: String, date: Long, outgoing: Boolean = false, mmsPending: Boolean = false) =
        Message(
            key = key, id = 0, kind = MessageKind.RCS, threadId = 1, sender = if (outgoing) null else "+15555550142", recipients = emptyList(),
            body = body, date = date, dateSent = date, outgoing = outgoing, status = DeliveryStatus.RECEIVED, read = true, subId = 0,
            mmsPending = mmsPending,
        )

    private fun translation(key: String, text: String, origin: String = TranslationOrigin.AI, romanized: String? = null) =
        TranslationEntity(key, "es", 1, 0, text, origin, null, 0.0, 0, 0, 0, romanized)

    // ---- matching ---------------------------------------------------------------------------------

    @Test
    fun matchingIgnoresCaseAndAccents() {
        assertEquals("cafe", SearchText.normalize("Café"))
        assertEquals("ecole", SearchText.normalize("ÉCOLE"))
        assertEquals("nino manana", SearchText.normalize("Niño mañana"))
        // Pinyin tones, Vietnamese, German, Polish, Turkish, Greek.
        assertEquals("ni hao", SearchText.normalize("Nǐ hǎo"))
        assertEquals("pho", SearchText.normalize("phở"))
        assertEquals("uber", SearchText.normalize("Über"))
        assertEquals("lodz", SearchText.normalize("Łódź"))
        assertEquals("istanbul", SearchText.normalize("İstanbul"))
        // Greek: a final sigma is the same letter as a capital one.
        assertEquals(SearchText.normalize("ΟΔΟΣ"), SearchText.normalize("οδός"))
        // Accents typed as separate marks fold the same way.
        assertEquals("cafe", SearchText.normalize("café"))
        // "❤️" and "❤" are the same heart.
        assertEquals(SearchText.normalize("❤"), SearchText.normalize("❤️"))
    }

    @Test
    fun marksThatChangeTheLetterStay() {
        // Japanese dakuten: か and が are different letters, whether written precomposed or with the mark.
        assertNotEquals(SearchText.normalize("か"), SearchText.normalize("が"))
        assertNotEquals(SearchText.normalize("か"), SearchText.normalize("が"))
        // Hindi vowel signs too.
        assertNotEquals(SearchText.normalize("कल"), SearchText.normalize("कुल"))
        // CJK is plain substring matching.
        assertEquals(listOf(2..3), SearchText.find("我们明天见面吧", "明天"))
        assertEquals(listOf(0..1), SearchText.find("ありがとう", "あり"))
    }

    @Test
    fun matchesMapBackToTheOriginalText() {
        assertEquals(listOf(3..6), SearchText.find("Un CAFÉ noir", "cafe"))
        // A separate accent mark stays inside the match.
        assertEquals(listOf(0..4), SearchText.find("café noir", "cafe"))
        // Emoji are two chars; what follows them still lines up.
        assertEquals(listOf(3..6), SearchText.find("🌮 Taco Tuesday", "taco"))
        assertEquals(listOf(0..5), SearchText.find("Nǐ hǎo", "ni hao"))
        assertEquals(listOf(0..1), SearchText.find("❤️ you", "❤"))
        // Every match, without overlaps.
        assertEquals(listOf(0..1, 3..4, 6..7), SearchText.find("La la LÁ", "la"))
        assertEquals(listOf(0..1), SearchText.find("aaa", "aa"))
        // The query is folded and trimmed too.
        assertEquals(listOf(10..15), SearchText.find("Nos vemos mañana", "  MANANA "))
        assertEquals(emptyList<IntRange>(), SearchText.find("Hola", "  "))
        assertEquals(emptyList<IntRange>(), SearchText.find("Hola", "adios"))
    }

    @Test
    fun aFewThousandMessagesSearchQuickly() {
        val messages = (0 until 5_000).map { i -> message("g$i", "Mensaje número $i: ¿Quedamos mañana en el café a las ${i % 24}?", 5_000L - i) }
        val translations = messages.associate { it.key to translation(it.key, "Message number ${it.key}: shall we meet at the café tomorrow?") }
        val start = System.nanoTime()
        val docs = ChatSearchIndex.docs(messages, translations, null)
        val hits = ChatSearchIndex.hits(docs, "cafe")
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(5_000, hits.size)
        assertTrue("took $ms ms", ms < 2_000)
    }

    // ---- what matches -----------------------------------------------------------------------------

    @Test
    fun hitsAreNewestFirstWithTheirPlaceInTheChat() {
        val messages = listOf(
            message("g3", "Dinner at 7?", 3),
            message("g2", "No idea", 2),
            message("g1", "What's for dinner", 1),
        )
        val hits = ChatSearchIndex.hits(ChatSearchIndex.docs(messages, emptyMap(), null), "DINNER")
        assertEquals(listOf("g3", "g1"), hits.map { it.key })
        assertEquals(listOf(0, 2), hits.map { it.position })
        assertTrue(hits.all { it.inOriginal && !it.inTranslation })
    }

    @Test
    fun aMessageIsFoundByItsTranslation() {
        val messages = listOf(message("g2", "So exciting! I'll take care of dinner.", 2, outgoing = true), message("g1", "Hey!", 1))
        val translations = mapOf(
            "g2" to translation("g2", "¡Qué emoción! Yo me encargo de la cena.", TranslationOrigin.SELF),
            // Nothing to translate: the same text adds nothing.
            "g1" to translation("g1", "cena", TranslationOrigin.SAME),
        )
        val docs = ChatSearchIndex.docs(messages, translations, null)
        assertEquals(listOf(SearchHit("g2", 0, inOriginal = false, inTranslation = true)), ChatSearchIndex.hits(docs, "cena"))
        assertEquals(listOf(SearchHit("g2", 0, inOriginal = false, inTranslation = true)), ChatSearchIndex.hits(docs, "emocion"))
        assertEquals(listOf(SearchHit("g2", 0, inOriginal = true, inTranslation = false)), ChatSearchIndex.hits(docs, "dinner"))
    }

    @Test
    fun theTranslationIsSearchedAsItShows() {
        val messages = listOf(message("g1", "Hello", 1))
        val translations = mapOf("g1" to translation("g1", "你好", romanized = "nǐ hǎo"))
        fun hits(script: ScriptDisplay?, query: String) = ChatSearchIndex.hits(ChatSearchIndex.docs(messages, translations, script), query).map { it.key }
        // Pinyin is searched where it shows, tone marks or not.
        assertEquals(listOf("g1"), hits(ScriptDisplay.BOTH, "ni hao"))
        assertEquals(listOf("g1"), hits(ScriptDisplay.BOTH, "你好"))
        assertEquals(listOf("g1"), hits(ScriptDisplay.ROMANIZED, "ni hao"))
        assertEquals(emptyList<String>(), hits(ScriptDisplay.ROMANIZED, "你好"))
        assertEquals(emptyList<String>(), hits(ScriptDisplay.CHARACTERS, "ni hao"))
        assertEquals(listOf("g1"), hits(ScriptDisplay.CHARACTERS, "你好"))
    }

    @Test
    fun reactionsAndMessagesWithoutTextDontMatch() {
        val messages = listOf(
            message("g5", "Loved “See you at dinner”", 5),
            message("g4", "Reacted 😂 to “dinner”", 4),
            message("g3", "", 3, mmsPending = true),
            message("g2", "", 2),
            message("g1", "See you at dinner", 1),
        )
        val docs = ChatSearchIndex.docs(messages, emptyMap(), null)
        assertEquals(listOf("g1"), docs.map { it.key })
        assertEquals(listOf("g1"), ChatSearchIndex.hits(docs, "dinner").map { it.key })
    }

    // ---- stepping through matches -----------------------------------------------------------------

    private val three = listOf(SearchHit("g9", 0, true, false), SearchHit("g5", 4, true, false), SearchHit("g1", 8, false, true))

    @Test
    fun upGoesToOlderMatchesAndDownToNewer() {
        val s = SearchUi(open = true).withHits("taco", three, 9)
        assertEquals("g9", s.current?.key)
        assertEquals(1, s.jump)
        assertFalse(s.canGoNewer)
        assertNull(s.newer())
        val older = s.older()!!
        assertEquals("g5", older.current?.key)
        assertEquals(2, older.jump)
        val oldest = older.older()!!
        assertEquals("g1", oldest.current?.key)
        // Past the oldest is a look further back, not another step.
        assertNull(oldest.older())
        assertTrue(oldest.canGoOlder)
        assertEquals("g5", oldest.newer()?.current?.key)
        assertEquals("g9", oldest.newest().current?.key)
    }

    @Test
    fun theCurrentMatchStaysWhenMessagesComeIn() {
        val s = SearchUi(open = true).withHits("taco", three, 9).older()!!
        // A new matching message arrives on top: the same match stays current, and the list doesn't move.
        val more = listOf(SearchHit("g10", 0, true, false)) + three.map { it.copy(position = it.position + 1) }
        val after = s.withHits("taco", more, 10)
        assertEquals("g5", after.current?.key)
        assertEquals(2, after.index)
        assertEquals(s.jump, after.jump)
        // A new query starts again at the newest.
        val next = after.withHits("tacos", listOf(SearchHit("g1", 9, true, false)), 10)
        assertEquals("g1", next.current?.key)
        assertEquals(0, next.index)
        assertEquals(after.jump + 1, next.jump)
        // No matches: "No matches", nothing to step through.
        val none = next.withHits("burrito", emptyList(), 10)
        assertTrue(none.noMatches)
        assertFalse(none.canGoOlder)
        assertFalse(SearchUi(open = true).withHits("", emptyList(), 10).noMatches)
    }

    @Test
    fun theCurrentMatchIsMarkedMoreStrongly() {
        val s = SearchUi(open = true).withHits("taco", three, 9)
        assertEquals(SearchMark("taco", current = true), s.markFor("g9"))
        assertEquals(SearchMark("taco", current = false), s.markFor("g5"))
        assertNull(s.markFor("g2"))
    }

    // ---- the side of the bubble that shows --------------------------------------------------------

    private fun bubble(showOriginal: Boolean = false, awaiting: Boolean = false, translated: Boolean = true) = MessageUi(
        message = message("g1", "See you at dinner", 1), reactions = emptyList(),
        translation = if (translated) translation("g1", "Nos vemos en la cena") else null, showOriginal = showOriginal,
        firstInRun = true, lastInRun = true, newDay = false, translatable = true, awaiting = awaiting,
    )

    @Test
    fun theCurrentMatchShowsTheSideItIsOn() {
        val inOriginal = SearchHit("g1", 0, inOriginal = true, inTranslation = false)
        val inTranslation = SearchHit("g1", 0, inOriginal = false, inTranslation = true)
        // Translated, the match only in the original: the original shows while it's current.
        assertEquals("See you at dinner", bubble().revealing(inOriginal).displayText)
        // Switched to the original, the match only in the translation: the translation shows.
        assertEquals("Nos vemos en la cena", bubble(showOriginal = true).revealing(inTranslation).displayText)
        // The side showing matches: nothing changes.
        val both = SearchHit("g1", 0, inOriginal = true, inTranslation = true)
        val translated = bubble()
        assertSame(translated, translated.revealing(both))
        // Waiting for its translation: the original shows instead of the placeholder.
        val waiting = bubble(awaiting = true, translated = false).revealing(inOriginal)
        assertTrue(waiting.showOriginal)
        // Afterwards (another match current), the bubble is as it was.
        val b = bubble()
        assertSame(b, b.revealing(SearchHit("g2", 1, true, false)))
        assertSame(b, b.revealing(null))
    }

    // ---- highlighting -----------------------------------------------------------------------------

    @Test
    fun highlightsKeepLinksAndInlineContent() {
        val link = TextLinkStyles(SpanStyle(color = Color.White))
        val content = buildAnnotatedString {
            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append("Plans"); pop()
            append('\n')
            append("Dinner at ")
            withLink(LinkAnnotation.Clickable("Date", link) {}) { append("7 on Friday") }
            append("  ")
            pushStringAnnotation("androidx.compose.foundation.text.inlineContent", "flag"); append("·"); pop()
        }
        val item = MessageUi(
            message = message("g1", "Dinner at 7 on Friday", 1).copy(subject = "Plans"), reactions = emptyList(), translation = null,
            showOriginal = false, firstInRun = true, lastInRun = true, newDay = false, translatable = true, awaiting = false,
        )
        assertSame(content, highlightBubble(content, item, null))
        assertSame(content, highlightBubble(content, item, SearchMark("tacos", current = true)))
        val marked = highlightBubble(content, item, SearchMark("FRIDAY", current = true))
        assertEquals(content.text, marked.text)
        val highlight = marked.spanStyles.single { it.item.background != Color.Unspecified }
        assertEquals("Friday", marked.text.substring(highlight.start, highlight.end))
        // The link still works, in the highlight's colour; the subject's style and the marker stay.
        val links = marked.getLinkAnnotations(0, marked.length)
        assertEquals("7 on Friday", marked.text.substring(links.single().start, links.single().end))
        assertEquals(Color.Unspecified, links.single().item.styles?.style?.color)
        assertTrue(marked.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && it.start == 0 && it.end == 5 })
        assertEquals("flag", marked.getStringAnnotations(0, marked.length).single().item)
        // The subject isn't searched: only the message text is.
        assertSame(content, highlightBubble(content, item, SearchMark("plans", current = false)))
    }

    // ---- the search itself ------------------------------------------------------------------------

    private class Phone(var messages: List<Message>) {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val translations = MutableStateFlow<Map<String, TranslationEntity>>(emptyMap())
        var asked = 0
        /** What asking for older messages does: by default, nothing more is there. */
        var older: () -> Boolean = { false }
        val revealed = mutableListOf<Int>()
    }

    private fun TestScope.search(phone: Phone) = ChatSearch(
        scope = backgroundScope,
        cached = { phone.messages },
        translations = phone.translations,
        script = MutableStateFlow(null),
        changes = phone.changes,
        requestOlder = { phone.asked++; phone.older() },
        reveal = { phone.revealed += it },
        work = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.settle() {
        advanceTimeBy(ChatSearch.TYPING_MS + ChatSearch.CHANGES_MS + 1)
        runCurrent()
    }

    private val chat = listOf(
        message("g4", "Tacos tonight?", 4),
        message("g3", "Sure", 3),
        message("g2", "Loved “Tacos tonight?”", 2),
        message("g1", "The taco place downtown", 1),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun typingFindsMatchesAndGoesToTheNewest() = runTest {
        val phone = Phone(chat)
        val search = search(phone)
        search.open()
        search.type("TACO")
        settle()
        val s = search.state.value
        assertEquals(listOf("g4", "g1"), s.hits.map { it.key })
        assertEquals("g4", s.current?.key)
        assertEquals(listOf(1), phone.revealed)
        search.older()
        assertEquals("g1", search.state.value.current?.key)
        // The oldest match is the fourth message: the list loads that far.
        assertEquals(listOf(1, 4), phone.revealed)
        // A translation arriving is searched too.
        phone.translations.value = mapOf("g3" to TranslationEntity("g3", "es", 1, 0, "Claro, unos tacos", TranslationOrigin.AI, null, 0.0, 0, 0, 0))
        settle()
        assertEquals(listOf("g4", "g3", "g1"), search.state.value.hits.map { it.key })
        assertEquals("g1", search.state.value.current?.key)
        search.close()
        assertFalse(search.state.value.open)
        assertEquals(0, phone.asked)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pastTheOldestMatchItLooksFurtherBackOnThePhone() = runTest {
        val phone = Phone(chat)
        val older = message("g0", "Taco Tuesday?", 0)
        phone.older = { true }
        val search = search(phone)
        search.open()
        search.type("taco")
        settle()
        search.older()
        search.older()
        runCurrent()
        assertEquals(SearchStatus.LOOKING_BACK, search.state.value.status)
        assertEquals(1, phone.asked)
        assertFalse(search.state.value.canGoOlder)
        // The page arrives with an older match: that's the current one now.
        phone.messages = chat + older
        phone.changes.emit(Unit)
        settle()
        val s = search.state.value
        assertEquals(SearchStatus.NONE, s.status)
        assertEquals("g0", s.current?.key)
        assertEquals("3 of 3", "${s.index + 1} of ${s.hits.size}")
        assertEquals(5, phone.revealed.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun withNothingOlderItSaysNoMoreMatches() = runTest {
        val phone = Phone(chat)
        val search = search(phone)
        search.open()
        search.type("taco")
        settle()
        search.older()
        search.older()
        runCurrent()
        assertEquals(SearchStatus.NO_MORE, search.state.value.status)
        assertEquals(1, phone.asked)
        // Known now: the phone isn't asked again.
        search.older()
        assertEquals(SearchStatus.NO_MORE, search.state.value.status)
        assertEquals(1, phone.asked)
        // Stepping on clears it.
        search.newer()
        assertEquals(SearchStatus.NONE, search.state.value.status)
        assertEquals("g4", search.state.value.current?.key)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theLookBackIsBounded() = runTest {
        val phone = Phone(chat)
        var date = 0L
        // Every page arrives, with nothing that matches.
        phone.older = {
            phone.messages = phone.messages + (1..50).map { message("o${date}_$it", "Nothing here", date - it) }
            date -= 100
            phone.changes.tryEmit(Unit)
            true
        }
        val search = search(phone)
        search.open()
        search.type("taco")
        settle()
        search.older()
        search.older()
        repeat(ChatSearch.LOOK_BACK_PAGES + 2) { settle() }
        assertEquals(SearchStatus.NO_MORE, search.state.value.status)
        assertEquals(ChatSearch.LOOK_BACK_PAGES, phone.asked)
        assertEquals("g1", search.state.value.current?.key)

        // A page that never arrives stops the look back too.
        val stuck = Phone(chat).apply { older = { true } }
        val s2 = search(stuck)
        s2.open()
        s2.type("taco")
        settle()
        s2.older()
        s2.older()
        runCurrent()
        assertEquals(SearchStatus.LOOKING_BACK, s2.state.value.status)
        advanceTimeBy(ChatSearch.PAGE_MS + 1)
        runCurrent()
        assertEquals(SearchStatus.NO_MORE, s2.state.value.status)
        assertEquals(1, stuck.asked)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun workForTheAiWaitsUntilSearchCloses() = runTest {
        val search = search(Phone(chat))
        val ran = mutableListOf<String>()
        search.afterSearch("g1") { ran += "g1" }
        assertEquals(listOf("g1"), ran)
        search.open()
        search.afterSearch("g2") { ran += "g2" }
        search.afterSearch("g2") { ran += "g2" }
        search.afterSearch("g3") { ran += "g3" }
        assertEquals(listOf("g1"), ran)
        search.close()
        assertEquals(listOf("g1", "g2", "g3"), ran)
    }
}
