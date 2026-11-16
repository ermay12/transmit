package app.transmit.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.transmit.ai.usd
import app.transmit.ui.Permissions
import app.transmit.ui.components.LanguagePickerDialog
import app.transmit.gm.BridgeState
import app.transmit.ui.rememberPermissionsRequest
import app.transmit.ui.settings.KeyDialog
import app.transmit.ui.settings.LevelSelector
import app.transmit.ui.settings.ProviderChips
import app.transmit.ui.settings.SettingsViewModel
import android.Manifest

@Composable
fun OnboardingScreen(vm: SettingsViewModel, onPair: () -> Unit, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    vm.keyVersion.collectAsStateWithLifecycle().value
    var step by remember { mutableIntStateOf(0) }
    val s = settings ?: return
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(3) { i ->
                Box(
                    Modifier.padding(4.dp).size(if (i == step) 24.dp else 8.dp, 8.dp).clip(CircleShape)
                        .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(step, modifier = Modifier.weight(1f), label = "step") { st ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (st) {
                    0 -> LanguagesStep(vm, s.nativeLang, s.targetLang, s.native.flag, s.target.flag, s.native.nativeName, s.target.nativeName, s.level)
                    1 -> PhoneStep(vm, onPair)
                    else -> AiStep(vm, s)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (step == 2 && !vm.hasKey(s.provider)) TextButton(onClick = { vm.setOnboarded(); onDone() }) { Text("Later") }
            Button(onClick = { if (step < 2) step++ else { vm.setOnboarded(); onDone() } }) { Text(if (step < 2) "Next" else "Start") }
        }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    if (sub != null) {
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun LanguagesStep(
    vm: SettingsViewModel, nativeCode: String, targetCode: String, nativeFlag: String, targetFlag: String,
    nativeName: String, targetName: String, level: app.transmit.data.Level,
) {
    var pick by remember { mutableStateOf<String?>(null) }
    Title("Learn while you text", "Messages arrive in the language you're practicing. Your friends get your language.")
    BigLanguage("I speak", nativeFlag, nativeName) { pick = "native" }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        IconButton(onClick = { vm.setNative(targetCode) }) { Icon(Icons.Default.SwapVert, "Swap") }
    }
    BigLanguage("I'm practicing", targetFlag, targetName) { pick = "target" }
    Spacer(Modifier.height(20.dp))
    LevelSelector(level, vm::setLevel)
    when (pick) {
        "native" -> LanguagePickerDialog("I speak", nativeCode, { it?.let(vm::setNative); pick = null }, { pick = null })
        "target" -> LanguagePickerDialog("I'm practicing", targetCode, { it?.let(vm::setTarget); pick = null }, { pick = null })
    }
}

@Composable
private fun BigLanguage(label: String, flag: String, name: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flag, fontSize = 40.sp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun PhoneStep(vm: SettingsViewModel, onPair: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Permissions.missing(context).isEmpty()) }
    val bridge by vm.bridge.collectAsStateWithLifecycle()
    val paired = bridge is BridgeState.Connecting || bridge is BridgeState.Connected
    val requestPerms = rememberPermissionsRequest { granted = Permissions.missing(context).isEmpty() }
    Title(
        "Your messages",
        "Transmit links to Google Messages on your phone, like Messages for Web. Keep Google Messages as your SMS app; RCS chats keep working.",
    )
    StepCard(Icons.Default.Sms, "Pair with Google Messages", paired, action = "Pair", onClick = onPair)
    Spacer(Modifier.height(10.dp))
    StepCard(Icons.Default.Contacts, "Contacts & notifications", granted) { requestPerms(Permissions.runtime) }
    if (!Permissions.has(context, Manifest.permission.POST_NOTIFICATIONS)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            Icon(Icons.Default.Notifications, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("Needed to alert you to new messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepCard(icon: ImageVector, title: String, done: Boolean, action: String = "Allow", onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = !done, shape = RoundedCornerShape(20.dp),
        color = if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (done) Icon(Icons.Default.CheckCircle, "Done", tint = MaterialTheme.colorScheme.primary)
            else FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun AiStep(vm: SettingsViewModel, s: app.transmit.data.AppSettings) {
    val provider = s.provider
    var keyDialog by remember { mutableStateOf(false) }
    val hasKey = vm.hasKey(provider)
    Title("Connect an AI", "Translations, explanations and feedback use your own API key. You set the budget.")
    ProviderChips(provider, vm::setProvider)
    Spacer(Modifier.height(16.dp))
    StepCard(Icons.Default.Key, if (hasKey) "${provider.label} key saved" else "${provider.label} key", hasKey, action = "Add") { keyDialog = true }
    Spacer(Modifier.height(16.dp))
    Text(
        "Budget: ${usd(s.monthlyBudgetUsd)} a month overall, ${usd(s.contactDefaults.monthlyBudgetUsd)} per contact, " +
            "${usd(s.unknownDefaults.monthlyBudgetUsd)} per unknown number. Unknown numbers aren't auto-translated, so codes and spam never reach the AI.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start,
    )
    if (keyDialog) KeyDialog(vm, provider) { keyDialog = false }
}
