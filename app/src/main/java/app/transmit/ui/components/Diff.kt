package app.transmit.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Word-level diff (character-level for scripts without spaces) used to highlight corrections.
 *
 * A word the learner got right stays unmarked when only the punctuation around or inside it changes. Punctuation is
 * a token of its own, so an added full stop marks just the full stop. A word that differs only in case is the same word,
 * with the letters whose case changed marked as added ("ok" → "**O**k"). So is a word that differs only in its
 * apostrophe style (' and ’) or kind of space. Where a stretch differs only in punctuation and spacing ("cest" →
 * "c'est", "est ce" → "est-ce"), just the marks are shown, not the words.
 */
object Diff {
    enum class Op { SAME, ADD, DEL }
    data class Piece(val text: String, val op: Op)

    private val unspacedScripts = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
    )

    /** Character-level for scripts written without spaces (or long text that has almost none), else word-level. */
    fun charLevel(s: String): Boolean =
        s.codePoints().anyMatch { Character.UnicodeScript.of(it) in unspacedScripts } ||
            (s.length >= 12 && s.count { it == ' ' } < s.length / 12)

    /**
     * Spaces (no-break ones too), words, and each punctuation mark or symbol on its own. A word keeps the apostrophes
     * and hyphens inside it ("c'est", "don't", "well-known"), so those read as one word.
     */
    private val wordTokens = Regex("[\\s\\p{Z}]+|[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*|[^\\s\\p{Z}\\p{L}\\p{M}\\p{N}]")

    fun tokens(s: String, charLevel: Boolean = charLevel(s)): List<String> = when {
        s.isEmpty() -> emptyList()
        charLevel -> s.codePoints().toArray().map { String(Character.toChars(it)) }
        else -> wordTokens.findAll(s).map { it.value }.toList()
    }

    fun diff(before: String, after: String): List<Piece> {
        // Both sides must be split the same way, or a one-character fix shows as the whole text replaced.
        val charLevel = charLevel(before) || charLevel(after)
        val steps = align(tokens(before, charLevel), tokens(after, charLevel)) ?: return listOf(Piece(after, Op.SAME))
        val out = Pieces()
        var k = 0
        while (k < steps.size) {
            // A hunk: the changes between two words that match, with the spaces among them (spaces match anywhere,
            // so they would otherwise split one change in two: "est ce" → "est-ce" pairs up the space after "ce").
            var end = k
            while (end < steps.size && steps[end].changeOrSpace) end++
            if (end == k) {
                out.step(steps[k++])
                continue
            }
            val hunk = steps.subList(k, end)
            val marks = if (charLevel || hunk.all { it.matched }) null else marksOnly(hunk)
            if (marks != null) out.addAll(marks) else hunk.forEach(out::step)
            k = end
        }
        return out.list
    }

    /** Between a removal and an addition that touch, so "~~para~~de" doesn't read as one word ("parade"). */
    const val GAP = " "

    /**
     * What's shown, in order: blank removals dropped, and [GAP] (unstyled) between a removal and an addition that would
     * otherwise run together.
     */
    fun shown(pieces: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        for (p in pieces) {
            if (p.op == Op.DEL && p.text.isBlank()) continue
            val prev = out.lastOrNull()
            val touching = prev != null && prev.op != Op.SAME && p.op != Op.SAME && prev.op != p.op &&
                !prev.text.last().isWhitespace() && !p.text.first().isWhitespace()
            if (touching) out += Piece(GAP, Op.SAME)
            out += p
        }
        return out
    }

    /** Inline rendering: removed text struck through, added text bold in [add], with a gap where the two meet. */
    fun annotated(before: String, after: String, add: Color, del: Color): AnnotatedString = buildAnnotatedString {
        shown(diff(before, after)).forEach { p ->
            when (p.op) {
                Op.SAME -> append(p.text)
                Op.ADD -> withStyle(SpanStyle(color = add, fontWeight = FontWeight.SemiBold)) { append(p.text) }
                Op.DEL -> withStyle(SpanStyle(color = del, textDecoration = TextDecoration.LineThrough)) { append(p.text) }
            }
        }
    }

    // ---- alignment -----------------------------------------------------------------------------

    /** One token of the draft ([a]) and the correction ([b]) that match, or a token only one of them has. */
    private class Step(val a: String?, val b: String?) {
        val matched get() = a != null && b != null
        val changeOrSpace get() = !matched || b!!.isBlank()
    }

    /** The longest run of [alike] tokens [a] and [b] share, as steps through both; null when too long to compare. */
    private fun align(a: List<String>, b: List<String>): List<Step>? {
        val n = a.size
        val m = b.size
        if (n.toLong() * m > 250_000) return null
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (alike(a[i], b[j])) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val steps = ArrayList<Step>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                alike(a[i], b[j]) -> steps += Step(a[i++], b[j++])
                lcs[i + 1][j] >= lcs[i][j + 1] -> steps += Step(a[i++], null)
                else -> steps += Step(null, b[j++])
            }
        }
        while (i < n) steps += Step(a[i++], null)
        while (j < m) steps += Step(null, b[j++])
        return steps
    }

    private const val APOSTROPHES = "'’‘ʼ"
    private const val QUOTES = "\"“”"

    /** Characters a correction can swap without the learner having got anything wrong. */
    private fun sameMark(x: Char, y: Char) =
        x == y || (x in APOSTROPHES && y in APOSTROPHES) || (x in QUOTES && y in QUOTES) || (x.isWhitespace() && y.isWhitespace())

    /** [x] and [y] are one letter in two cases. */
    private fun caseOnly(x: Char, y: Char) = x != y && x.equals(y, ignoreCase = true)

    /** The same token, or the same apart from case, apostrophe or quote style, or the kind of space. */
    private fun alike(x: String, y: String): Boolean =
        x == y || (x.isBlank() && y.isBlank()) ||
            (x.length == y.length && x.indices.all { sameMark(x[it], y[it]) || caseOnly(x[it], y[it]) })

    private val notLetters = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /**
     * The hunk shown mark by mark, when its letters are the draft's (apart from case) and only punctuation and spacing
     * changed. Null otherwise, or when that would hide a change: a space added or removed on its own is invisible, so
     * "alot" → "a lot" stays a replaced word.
     */
    private fun marksOnly(hunk: List<Step>): List<Piece>? {
        val before = hunk.joinToString("") { it.a.orEmpty() }
        val after = hunk.joinToString("") { it.b.orEmpty() }
        if (before.replace(notLetters, "").lowercase() != after.replace(notLetters, "").lowercase()) return null
        val steps = align(tokens(before, charLevel = true), tokens(after, charLevel = true)) ?: return null
        val out = Pieces()
        steps.forEach(out::step)
        val pieces = out.list
        val hidden = pieces.indices.any { i ->
            val p = pieces[i]
            p.op != Op.SAME && p.text.isBlank() &&
                listOfNotNull(pieces.getOrNull(i - 1), pieces.getOrNull(i + 1)).none { it.op != Op.SAME && it.text.isNotBlank() }
        }
        return pieces.takeUnless { hidden }
    }

    /** Pieces in order, each run of one kind merged. */
    private class Pieces {
        val list = mutableListOf<Piece>()

        fun push(text: String, op: Op) {
            if (text.isEmpty()) return
            val prev = list.lastOrNull()
            if (prev != null && prev.op == op) list[list.lastIndex] = Piece(prev.text + text, op) else list += Piece(text, op)
        }

        fun addAll(pieces: List<Piece>) = pieces.forEach { push(it.text, it.op) }

        fun step(s: Step) {
            when {
                s.a != null && s.b != null -> matched(s.a, s.b)
                s.a != null -> push(s.a, Op.DEL)
                else -> push(s.b!!, Op.ADD)
            }
        }

        /** The correction's form of a token the draft has too, with the letters whose case changed marked as added. */
        private fun matched(a: String, b: String) {
            if (a == b || a.length != b.length) return push(b, Op.SAME)
            b.indices.forEach { i -> push(b[i].toString(), if (caseOnly(a[i], b[i])) Op.ADD else Op.SAME) }
        }
    }
}
