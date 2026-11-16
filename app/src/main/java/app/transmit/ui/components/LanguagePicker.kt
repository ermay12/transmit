package app.transmit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.transmit.data.Language
import app.transmit.data.Languages
import app.transmit.app

/**
 * Language list dialog. Recently picked languages come first, so switching between a few is quick;
 * every pick counts. When [defaultLabel] is set, a first "default" row maps to a null code.
 */
@Composable
fun LanguagePickerDialog(
    title: String,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    defaultLabel: String? = null,
    exclude: String? = null,
) {
    val container = LocalContext.current.app.container
    val recent by container.recentLanguages.collectAsStateWithLifecycle()
    val (top, rest) = remember(recent, exclude) { Languages.sections(recent, exclude) }
    val pick: (String?) -> Unit = { code ->
        // The app's scope: the dialog closes with this pick and would cancel its own.
        code?.let { container.scope.launch { container.settings.noteLanguageUsed(it) } }
        onPick(code)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (defaultLabel != null) {
                    item { LanguageRow(null, defaultLabel, selected == null) { pick(null) } }
                }
                if (top.isNotEmpty()) {
                    item("recent-header") { SectionLabel("Recent") }
                    items(top, key = { it.code }) { lang ->
                        LanguageRow(lang, null, lang.code == selected) { pick(lang.code) }
                    }
                    item("all-header") { SectionLabel("All languages") }
                }
                items(rest, key = { it.code }) { lang ->
                    LanguageRow(lang, null, lang.code == selected) { pick(lang.code) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun LanguageRow(lang: Language?, label: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(lang?.flag ?: "⚙️", fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        if (lang != null) {
            Text(lang.nativeName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (lang.nativeName != lang.name) Text(lang.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(label.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
        else Spacer(Modifier.width(24.dp))
    }
}
