package app.transmit.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** Word-level diff (character-level for scripts without spaces) used to highlight corrections. */
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

    fun tokens(s: String, charLevel: Boolean = charLevel(s)): List<String> = when {
        s.isEmpty() -> emptyList()
        charLevel -> s.codePoints().toArray().map { String(Character.toChars(it)) }
        else -> Regex("\\s+|[^\\s]+").findAll(s).map { it.value }.toList()
    }

    fun diff(before: String, after: String): List<Piece> {
        // Both sides must be split the same way, or a one-character fix shows as the whole text replaced.
        val charLevel = charLevel(before) || charLevel(after)
        val a = tokens(before, charLevel)
        val b = tokens(after, charLevel)
        val n = a.size
        val m = b.size
        if (n * m > 250_000) return listOf(Piece(after, Op.SAME))
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<Piece>()
        var i = 0
        var j = 0
        fun push(t: String, op: Op) {
            val prev = out.lastOrNull()
            if (prev != null && prev.op == op) out[out.lastIndex] = Piece(prev.text + t, op) else out += Piece(t, op)
        }
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { push(a[i], Op.SAME); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { push(a[i], Op.DEL); i++ }
                else -> { push(b[j], Op.ADD); j++ }
            }
        }
        while (i < n) push(a[i++], Op.DEL)
        while (j < m) push(b[j++], Op.ADD)
        return out
    }

    /** Inline rendering: removed text struck through, added text bold in [add]. */
    fun annotated(before: String, after: String, add: Color, del: Color): AnnotatedString = buildAnnotatedString {
        diff(before, after).forEach { p ->
            when (p.op) {
                Op.SAME -> append(p.text)
                Op.ADD -> withStyle(SpanStyle(color = add, fontWeight = FontWeight.SemiBold)) { append(p.text) }
                Op.DEL -> if (p.text.isNotBlank()) withStyle(SpanStyle(color = del, textDecoration = TextDecoration.LineThrough)) { append(p.text) }
            }
        }
    }
}
