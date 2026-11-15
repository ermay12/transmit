package app.transmit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Renders the small Markdown subset tutors produce: headings, bullets, numbered lists, bold, italic, code. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, accent: Color = MaterialTheme.colorScheme.primary) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Heading -> Text(
                    inline(b.text, accent),
                    style = (if (b.level <= 2) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker, style = style, color = accent, modifier = Modifier.width(if (b.marker.length > 2) 22.dp else 14.dp))
                    Text(inline(b.text, accent), style = style)
                }
                is Block.Paragraph -> Text(inline(b.text, accent), style = style)
            }
        }
    }
}

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val indent: Int, val marker: String, val text: String) : Block
    data class Paragraph(val text: String) : Block
}

private val bulletRe = Regex("^(\\s*)([-*•+])\\s+(.*)$")
private val numberRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val headingRe = Regex("^(#{1,6})\\s+(.*)$")

private fun parseBlocks(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
        para.clear()
    }
    md.replace("\r", "").lines().forEach { raw ->
        val line = raw.trimEnd()
        headingRe.matchEntire(line.trim())?.let { flush(); out += Block.Heading(it.groupValues[1].length, it.groupValues[2]); return@forEach }
        bulletRe.matchEntire(line)?.let { flush(); out += Block.Bullet(it.groupValues[1].length / 2, "•", it.groupValues[3]); return@forEach }
        numberRe.matchEntire(line)?.let { flush(); out += Block.Bullet(it.groupValues[1].length / 2, "${it.groupValues[2]}.", it.groupValues[3]); return@forEach }
        if (line.isBlank()) flush() else {
            if (para.isNotEmpty()) para.append(' ')
            para.append(line.trim())
        }
    }
    flush()
    return out
}

private val inlineRe = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`(.+?)`|\\*(?!\\s)(.+?)\\*|(?<![\\w])_(?!\\s)(.+?)_(?![\\w])")

fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRe.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accent)) { append(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[3]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4].ifEmpty { g[5] }) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
