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

    fun tokens(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val spaced = s.count { it == ' ' } >= s.length / 12
        return if (spaced) Regex("\\s+|[^\\s]+").findAll(s).map { it.value }.toList() else s.map { it.toString() }
    }

    fun diff(before: String, after: String): List<Piece> {
        val a = tokens(before)
        val b = tokens(after)
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
