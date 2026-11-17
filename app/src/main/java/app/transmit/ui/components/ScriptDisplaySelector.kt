package app.transmit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.transmit.data.Language
import app.transmit.data.ScriptDisplay

/**
 * Chooses how [lang] is shown: its own script, with the romanization under it, or only the romanization.
 * The setting is per language, so in a chat sheet [everyChat] says it reaches the language's other chats too.
 */
@Composable
fun ScriptDisplaySelector(
    lang: Language,
    value: ScriptDisplay,
    onChange: (ScriptDisplay) -> Unit,
    modifier: Modifier = Modifier,
    everyChat: Boolean = false,
) {
    val r = lang.romanization ?: return
    val name = lang.name.substringBefore(" (")
    Column(modifier) {
        Text("Show $name as", style = MaterialTheme.typography.bodyLarge)
        val summary = when (value) {
            ScriptDisplay.CHARACTERS -> "Tap a message to see its ${r.label.lowercase()}"
            ScriptDisplay.BOTH -> "${r.label} under the characters"
            ScriptDisplay.ROMANIZED -> "${r.label} only. You can write in ${r.label.lowercase()} too"
        }
        Text(
            if (everyChat) "$summary · all $name chats" else summary,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ScriptDisplay.entries.forEachIndexed { i, d ->
                SegmentedButton(
                    selected = value == d, onClick = { onChange(d) },
                    shape = SegmentedButtonDefaults.itemShape(i, ScriptDisplay.entries.size),
                ) {
                    Text(
                        when (d) {
                            ScriptDisplay.CHARACTERS -> lang.scriptName
                            ScriptDisplay.BOTH -> "Both"
                            ScriptDisplay.ROMANIZED -> r.label
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
