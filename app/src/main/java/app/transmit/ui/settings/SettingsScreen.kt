package app.transmit.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.transmit.BuildConfig
import app.transmit.ai.ProviderType
import app.transmit.ai.usd
import app.transmit.data.AppSettings
import app.transmit.data.ChatDefaults
import app.transmit.data.Level
import app.transmit.data.ModelSlot
import app.transmit.data.NotificationContent
import app.transmit.ui.components.LanguagePickerDialog
import app.transmit.gm.BridgeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit, onModels: (ModelSlot) -> Unit, onUsage: () -> Unit, onPair: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val spent by vm.spent.collectAsStateWithLifecycle()
    vm.keyVersion.collectAsStateWithLifecycle().value // recompose when keys change
    val bridge by vm.bridge.collectAsStateWithLifecycle()
    var confirmUnpair by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<String?>(null) }
    var keyDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Section("Languages")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LanguageCard("I speak", s.native.flag, s.native.nativeName, Modifier.weight(1f)) { pick = "native" }
                LanguageCard("Practicing", s.target.flag, s.target.nativeName, Modifier.weight(1f)) { pick = "target" }
            }
            Spacer(Modifier.height(10.dp))
            LevelSelector(s.level, vm::setLevel, Modifier.padding(horizontal = 16.dp))

            Section("AI provider")
            ProviderChips(s.provider, vm::setProvider, Modifier.padding(horizontal = 16.dp))
            if (s.provider == ProviderType.CUSTOM) {
                var url by remember { mutableStateOf(s.customBaseUrl) }
                OutlinedTextField(
                    value = url, onValueChange = { url = it; vm.setCustomBaseUrl(it) },
                    label = { Text("Base URL") }, placeholder = { Text("https://host/v1") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            val masked = vm.maskedKey(s.provider)
            Row0(
                Icons.Default.Key, "API key", masked ?: "Not set",
                trailing = {
                    if (masked != null) Icon(Icons.Default.CheckCircle, "Saved", tint = MaterialTheme.colorScheme.primary)
                    else Icon(Icons.Default.ErrorOutline, "Missing", tint = MaterialTheme.colorScheme.error)
                },
            ) { keyDialog = true }
            ModelRow(Icons.Default.Translate, "Translation model", s, ModelSlot.TRANSLATE) { onModels(ModelSlot.TRANSLATE) }
            ModelRow(Icons.Default.Psychology, "Tutor model", s, ModelSlot.TUTOR) { onModels(ModelSlot.TUTOR) }

            Section("Budget")
            MonthlyBudget(spent, s.monthlyBudgetUsd, vm::setMonthlyBudget, onUsage)
            DefaultsRow(Icons.Default.Contacts, "Contacts", s.contactDefaults, vm::setContactDefaults)
            DefaultsRow(Icons.Outlined.HelpOutline, "Unknown numbers", s.unknownDefaults, vm::setUnknownDefaults)

            Section("Translation")
            SwitchRow0(
                Icons.Default.Contacts, "Auto-translate contacts", s.contactDefaults.autoTranslate,
                { vm.setContactDefaults(s.contactDefaults.copy(autoTranslate = it)) },
                subtitle = "Chats with people in your contacts",
            )
            SwitchRow0(
                Icons.Outlined.HelpOutline, "Auto-translate unknown numbers", s.unknownDefaults.autoTranslate,
                { vm.setUnknownDefaults(s.unknownDefaults.copy(autoTranslate = it)) },
                subtitle = "Chats with numbers not in your contacts",
            )
            StepperRow(Icons.Default.History, "Context messages", "Earlier messages sent with each request", s.contextMessages, 0..12, vm::setContextMessages)
            StepperRow(Icons.Default.DoneAll, "Translate on open", "Older messages translated when a chat opens", s.backfillCount, 0..50, vm::setBackfill, step = 4)
            SwitchRow0(Icons.Default.Send, "Translate my sent messages", s.translateOutgoing, vm::setTranslateOutgoing)

            Section("Messages")
            val paired = bridge !is BridgeState.Unpaired && bridge !is BridgeState.LoggedOut
            val status = when (val b = bridge) {
                BridgeState.Unpaired -> "Not paired"
                is BridgeState.LoggedOut -> "Disconnected · pair again"
                is BridgeState.Pairing -> "Pairing…"
                BridgeState.Connecting -> "Connecting…"
                is BridgeState.Connected -> when {
                    b.inactive -> "Paired · Messages for Web is open elsewhere"
                    !b.phoneResponding -> "Paired · phone not responding"
                    b.trouble != null -> "Paired · ${b.trouble}"
                    else -> "Connected"
                }
            }
            Row0(Icons.Default.Sms, "Google Messages", status, trailing = {
                if (paired) TextButton(onClick = { confirmUnpair = true }) { Text("Unpair") }
                else TextButton(onClick = onPair) { Text("Pair") }
            }) { if (!paired) onPair() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text("Notifications show", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp)) {
                NotificationContent.entries.forEachIndexed { i, v ->
                    SegmentedButton(
                        selected = s.notificationContent == v, onClick = { vm.setNotificationContent(v) },
                        shape = SegmentedButtonDefaults.itemShape(i, NotificationContent.entries.size),
                    ) { Text(v.label, maxLines = 1) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Transmit ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        when (pick) {
            "native" -> LanguagePickerDialog("I speak", s.nativeLang, { it?.let(vm::setNative); pick = null }, { pick = null })
            "target" -> LanguagePickerDialog("Practicing", s.targetLang, { it?.let(vm::setTarget); pick = null }, { pick = null })
        }
        if (keyDialog) KeyDialog(vm, s.provider) { keyDialog = false }
        if (confirmUnpair) {
            AlertDialog(
                onDismissRequest = { confirmUnpair = false },
                title = { Text("Unpair from Google Messages?") },
                text = { Text("Transmit stops sending and receiving until you pair again. Your messages stay on your phone.") },
                confirmButton = { TextButton(onClick = { confirmUnpair = false; vm.unpair() }) { Text("Unpair") } },
                dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun LanguageCard(label: String, flag: String, name: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flag, fontSize = 28.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
    }
}

@Composable
fun LevelSelector(level: Level, onLevel: (Level) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.School, "Level", tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            Level.entries.forEachIndexed { i, l ->
                SegmentedButton(selected = level == l, onClick = { onLevel(l) }, shape = SegmentedButtonDefaults.itemShape(i, Level.entries.size)) {
                    Text(l.label, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderChips(selected: ProviderType, onSelect: (ProviderType) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderType.entries.forEach { p ->
            FilterChip(selected = p == selected, onClick = { onSelect(p) }, label = { Text(p.label) })
        }
    }
}

@Composable
fun Row0(icon: ImageVector, title: String, subtitle: String?, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow0(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row0(icon, title, subtitle, trailing = { Switch(checked, onChange) }) { onChange(!checked) }
}

@Composable
private fun ModelRow(icon: ImageVector, title: String, s: AppSettings, slot: ModelSlot, onClick: () -> Unit) {
    val model = s.model(slot)
    val effort = s.effort(slot)
    Row0(icon, title, model ?: "Choose a model", trailing = {
        androidx.compose.material3.AssistChip(onClick = onClick, label = { Text(effort.label) })
    }, onClick = onClick)
}

@Composable
private fun StepperRow(icon: ImageVector, title: String, subtitle: String, value: Int, range: IntRange, onChange: (Int) -> Unit, step: Int = 1) {
    Row0(icon, title, subtitle, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Default.Remove, "Less") }
            Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Default.Add, "More") }
        }
    })
}

@Composable
private fun MonthlyBudget(spent: Double, limit: Double, onLimit: (Double) -> Unit, onUsage: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onUsage).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Savings, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("${usd(spent)} of ${usd(limit)} this month", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (limit > 0) (spent / limit).toFloat().coerceIn(0f, 1f) else 1f },
                modifier = Modifier.fillMaxWidth(),
                color = if (spent >= limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(12.dp))
        MoneyField(limit, onLimit)
    }
}

@Composable
fun MoneyField(value: Double, onValue: (Double) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("%.2f".format(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() || it == '.' }
            text.toDoubleOrNull()?.let(onValue)
        },
        prefix = { Text("$") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.width(100.dp),
    )
}

@Composable
private fun DefaultsRow(icon: ImageVector, title: String, d: ChatDefaults, onChange: (ChatDefaults) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text("Per chat, monthly", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MoneyField(d.monthlyBudgetUsd, { onChange(d.copy(monthlyBudgetUsd = it)) })
    }
}

@Composable
fun KeyDialog(vm: SettingsViewModel, provider: ProviderType, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val check by vm.keyCheck.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text("${provider.label} key") },
        text = {
            Column {
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, singleLine = true,
                    placeholder = { Text(provider.keyHint) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when (val c = check) {
                    KeyCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Checking…", style = MaterialTheme.typography.bodySmall)
                    }
                    is KeyCheck.Ok -> Text("✓ ${c.models} models available", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    is KeyCheck.Failed -> Text(c.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    KeyCheck.Idle -> Text("Stored encrypted on this device only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (provider.keyUrl.isNotEmpty()) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.keyUrl))) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp)); Text("Get a key")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveKey(provider, key) { onDismiss() } }, enabled = key.isNotBlank() && check != KeyCheck.Checking) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (vm.hasKey(provider)) TextButton(onClick = { vm.removeKey(provider); onDismiss() }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
