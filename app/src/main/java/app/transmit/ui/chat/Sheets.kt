package app.transmit.ui.chat

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.emoji2.emojipicker.EmojiPickerView
import app.transmit.ai.EffectiveChat
import app.transmit.ai.usd
import app.transmit.data.AppSettings
import app.transmit.data.ContactInfo
import app.transmit.data.Languages
import app.transmit.data.db.TranslationOrigin
import app.transmit.telephony.DeliveryStatus
import app.transmit.telephony.MessageKind
import app.transmit.telephony.PhoneNumbers
import app.transmit.telephony.Reactions
import app.transmit.ui.components.Avatar
import app.transmit.ui.components.LanguagePickerDialog
import app.transmit.ui.components.MarkdownText
import app.transmit.ui.components.Times
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActionsSheet(
    item: MessageUi,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onMoreEmoji: () -> Unit,
    onExplain: () -> Unit,
    onToggle: () -> Unit,
    onRetranslate: () -> Unit,
    onDetails: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val mine = item.reactions.firstOrNull { it.mine }?.emoji
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Reactions.QUICK.forEach { e ->
                    Surface(
                        onClick = { onReact(e) }, shape = CircleShape,
                        color = if (e == mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) { Text(e, fontSize = 24.sp, modifier = Modifier.padding(8.dp)) }
                }
                Surface(onClick = onMoreEmoji, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Icon(Icons.Default.Add, "More reactions", Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            // A compact preview: what the message says in both languages.
            val tr = item.translation
            if (tr != null && tr.origin != TranslationOrigin.SAME && item.message.body.isNotBlank()) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(tr.text, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(item.message.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.translatable) {
                    ActionTile(Icons.Outlined.Lightbulb, "Explain", onExplain)
                    if (tr != null && tr.origin != TranslationOrigin.SAME) {
                        ActionTile(if (item.showOriginal) Icons.Default.Translate else Icons.AutoMirrored.Filled.Undo, if (item.showOriginal) "Translation" else "Original", onToggle)
                    }
                    ActionTile(Icons.Default.Refresh, if (tr == null) "Translate" else "Redo", onRetranslate)
                }
                item.code?.let { code ->
                    ActionTile(Icons.Default.Password, "Copy code") {
                        onCopyCode(code)
                        onDismiss()
                    }
                }
                if (item.message.body.isNotBlank()) {
                    ActionTile(Icons.Default.ContentCopy, "Copy") {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", item.displayText))) }
                        onDismiss()
                    }
                }
                ActionTile(Icons.Default.Info, "Details", onDetails)
                if (item.message.status == DeliveryStatus.FAILED) ActionTile(Icons.Default.Refresh, "Retry", onRetry)
                ActionTile(Icons.Default.Delete, "Delete", onDelete)
            }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AndroidView(
            factory = { ctx -> EmojiPickerView(ctx).apply { setOnEmojiPickedListener { onPick(it.emoji) } } },
            modifier = Modifier.fillMaxWidth().height(420.dp).navigationBarsPadding(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(item: MessageUi, people: Map<String, ContactInfo>, recipients: List<String>, simLabel: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val m = item.message
    fun name(a: String) = people[a]?.let { if (it.isContact) "${it.name} (${PhoneNumbers.format(a)})" else it.displayName } ?: PhoneNumbers.format(a)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Details", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            val rows = buildList {
                add("Type" to when {
                    m.kind == MessageKind.SMS -> "SMS"
                    m.attachments.isNotEmpty() || m.mmsPending -> "MMS"
                    else -> "MMS (text)"
                })
                if (m.outgoing) {
                    add("To" to (m.recipients.ifEmpty { recipients }).joinToString("\n") { name(it) })
                    add("Sent" to Times.full(context, m.date))
                } else {
                    add("From" to (m.sender?.let { name(it) } ?: "Unknown"))
                    if (recipients.size > 1) add("Group" to recipients.joinToString("\n") { name(it) })
                    if (m.dateSent > 0) add("Sent" to Times.full(context, m.dateSent))
                    add("Received" to Times.full(context, m.date))
                }
                add("Status" to m.status.name.lowercase().replaceFirstChar { it.uppercase() } + if (m.errorCode != 0) " (error ${m.errorCode})" else "")
                if (!m.subject.isNullOrBlank()) add("Subject" to m.subject)
                if (m.sizeBytes > 0) add("Size" to Formatter.formatShortFileSize(context, m.sizeBytes))
                m.attachments.forEach { add("Attachment" to "${it.name ?: "part"} · ${it.mimeType}") }
                simLabel?.let { add("SIM" to it) }
                item.translation?.let { tr ->
                    add("Translation" to when (tr.origin) {
                        TranslationOrigin.AI -> listOfNotNull(tr.model, usd(tr.costUsd).takeIf { tr.costUsd > 0 }).joinToString(" · ")
                        TranslationOrigin.SELF -> "Written by you"
                        else -> "Already in ${Languages.get(tr.lang).name}"
                    })
                }
                add("ID" to m.key)
            }
            rows.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(k, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                    Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplainSheet(sheet: Sheet.Explain, onDismiss: () -> Unit, onRefresh: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("${sheet.lang.flag}  ${sheet.rendered}", style = MaterialTheme.typography.titleMedium)
                    if (sheet.original != sheet.rendered) {
                        Spacer(Modifier.height(4.dp))
                        Text(sheet.original, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onRefresh, enabled = sheet.text != null || sheet.error != null) { Icon(Icons.Default.Refresh, "Regenerate") }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Box(Modifier.heightIn(min = 120.dp, max = 520.dp).verticalScroll(rememberScrollState())) {
                when {
                    sheet.error != null -> Text(sheet.error, color = MaterialTheme.colorScheme.error)
                    sheet.text == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                    }
                    else -> MarkdownText(sheet.text)
                }
            }
            sheet.cost?.let { cost ->
                Spacer(Modifier.height(10.dp))
                Text(
                    listOfNotNull(
                        cost.model,
                        when {
                            sheet.cached -> "saved"
                            !cost.priced -> "price unknown"
                            else -> usd(cost.costUsd)
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInfoSheet(
    chat: EffectiveChat,
    settings: AppSettings,
    people: List<ContactInfo>,
    spent: Double,
    onDismiss: () -> Unit,
    onAutoTranslate: (Boolean?) -> Unit,
    onLanguage: (String?) -> Unit,
    onBudget: (Double?) -> Unit,
    onPinned: (Boolean) -> Unit,
    onMuted: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var pickLanguage by remember { mutableStateOf(false) }
    val overrides = chat.overrides
    val defaults = if (chat.isKnown) settings.contactDefaults else settings.unknownDefaults
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            people.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.displayName, p.address, p.photoUri, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, style = MaterialTheme.typography.titleMedium)
                        if (p.isContact) Text(PhoneNumbers.format(p.address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!PhoneNumbers.isShortCodeOrAlpha(p.address)) {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.address}"))) }) { Icon(Icons.Default.Call, "Call") }
                    }
                    if (p.lookupUri != null) {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, p.lookupUri)) }) { Icon(Icons.Outlined.AccountCircle, "Contact") }
                    } else if (!PhoneNumbers.isShortCodeOrAlpha(p.address)) {
                        IconButton(onClick = {
                            context.startActivity(
                                Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                                    .putExtra(ContactsContract.Intents.Insert.PHONE, p.address),
                            )
                        }) { Icon(Icons.Default.PersonAdd, "Add contact") }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // Auto-translate: follow the default for known/unknown senders, or force on/off.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Translate, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Auto-translate", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            val autoChoice = when (overrides?.autoTranslate) { null -> 0; true -> 1; false -> 2 }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Default (${if (defaults.autoTranslate) "on" else "off"})", "On", "Off").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = autoChoice == i,
                        onClick = { onAutoTranslate(when (i) { 0 -> null; 1 -> true; else -> false }) },
                        shape = SegmentedButtonDefaults.itemShape(i, 3),
                    ) { Text(label, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { pickLanguage = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(chat.target.flag, fontSize = 22.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Practice language", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (overrides?.targetLang == null) "${chat.target.nativeName} · default" else chat.target.nativeName,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            BudgetEditor(
                spent = spent, limit = chat.monthlyBudgetUsd, isDefault = overrides?.monthlyBudgetUsd == null,
                defaultLimit = defaults.monthlyBudgetUsd, onBudget = onBudget,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SwitchRow(Icons.Default.PushPin, "Pin", overrides?.pinned == true, onPinned)
            SwitchRow(Icons.Default.NotificationsOff, "Mute", overrides?.muted == true, onMuted)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Delete conversation", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (pickLanguage) {
        LanguagePickerDialog(
            title = "Practice language",
            selected = overrides?.targetLang,
            defaultLabel = "Default (${settings.target.flag} ${settings.target.nativeName})",
            exclude = settings.nativeLang,
            onPick = { onLanguage(it); pickLanguage = false },
            onDismiss = { pickLanguage = false },
        )
    }
}

@Composable
private fun BudgetEditor(spent: Double, limit: Double, isDefault: Boolean, defaultLimit: Double, onBudget: (Double?) -> Unit) {
    // Not keyed on the limit: committing each keystroke must not reformat what the user is typing.
    var text by remember { mutableStateOf(if (isDefault) "" else "%.2f".format(limit)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Savings, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${usd(spent)} of ${usd(limit)} this month", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (limit > 0) (spent / limit).toFloat().coerceIn(0f, 1f) else 1f },
                modifier = Modifier.fillMaxWidth(),
                color = if (spent >= limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v.filter { it.isDigit() || it == '.' }
                if (text.isBlank()) onBudget(null) else text.toDoubleOrNull()?.let { onBudget(it) }
            },
            prefix = { Text("$") },
            placeholder = { Text("%.2f".format(defaultLimit)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(104.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
