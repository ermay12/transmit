package app.transmit.ui.onboarding

import androidx.compose.ui.res.stringResource
import app.transmit.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.transmit.data.AppSettings
import app.transmit.gm.BridgeState
import app.transmit.ui.Permissions
import app.transmit.ui.access.GetOnPlay
import app.transmit.ui.access.TrialFailure
import app.transmit.ui.access.TrialUi
import app.transmit.ui.components.LanguagePickerDialog
import app.transmit.ui.rememberPermissionsRequest
import app.transmit.ui.settings.LevelSelector
import app.transmit.ui.settings.OwnKeyDialog
import app.transmit.ui.settings.SettingsViewModel

private const val STEPS = 3

/**
 * Three steps: what you're practicing, connecting Google Messages, and turning on translation. The step is
 * saveable, so it survives the trip to the pairing screen and rotation.
 */
@Composable
fun OnboardingScreen(vm: SettingsViewModel, onPair: () -> Unit, onPlans: () -> Unit, onDone: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    // Set when the user leaves for the pairing screen, so coming back paired moves on.
    var pairingStarted by rememberSaveable { mutableStateOf(false) }
    val s = settings ?: return
    fun finish() {
        vm.setOnboarded()
        onDone()
    }
    BackHandler(enabled = step > 0) { step-- }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            if (step > 0) {
                IconButton(onClick = { step-- }, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.Center) {
                repeat(STEPS) { i ->
                    Box(
                        Modifier.padding(4.dp).size(if (i == step) 24.dp else 8.dp, 8.dp).clip(CircleShape)
                            .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(step, modifier = Modifier.weight(1f), label = "step") { st ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (st) {
                    0 -> PracticeStep(vm, s)
                    1 -> MessagesStep(
                        vm, pairingStarted,
                        onPair = { pairingStarted = true; onPair() },
                        onNext = { pairingStarted = false; step = 2 },
                    )
                    else -> TranslationStep(vm, onPlans, onFinish = ::finish)
                }
            }
        }
        if (step == 0) {
            Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onboarding_continue)) }
        }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    if (sub != null) {
        Spacer(Modifier.height(8.dp))
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PracticeStep(vm: SettingsViewModel, s: AppSettings) {
    var pick by remember { mutableStateOf<String?>(null) }
    Title(stringResource(R.string.onboarding_practice_title), stringResource(R.string.onboarding_practice_body))
    LanguageCard(stringResource(R.string.onboarding_practicing), s.target.flag, s.target.nativeName, prominent = true) { pick = "target" }
    Spacer(Modifier.height(12.dp))
    LanguageCard(stringResource(R.string.onboarding_speak), s.native.flag, s.native.nativeName, prominent = false) { pick = "native" }
    Spacer(Modifier.height(28.dp))
    Text(stringResource(R.string.onboarding_level), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    LevelSelector(s.level, vm::setLevel)
    when (pick) {
        "native" -> LanguagePickerDialog(stringResource(R.string.onboarding_speak), s.nativeLang, { it?.let(vm::setNative); pick = null }, { pick = null })
        "target" -> LanguagePickerDialog(stringResource(R.string.onboarding_practicing), s.targetLang, { it?.let(vm::setTarget); pick = null }, { pick = null })
    }
}

@Composable
private fun LanguageCard(label: String, flag: String, name: String, prominent: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
        color = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(if (prominent) 22.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flag, fontSize = if (prominent) 48.sp else 28.sp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, style = if (prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.onboarding_change), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ColumnScope.MessagesStep(vm: SettingsViewModel, pairingStarted: Boolean, onPair: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    val bridge by vm.bridge.collectAsStateWithLifecycle()
    val paired = bridge is BridgeState.Connecting || bridge is BridgeState.Connected
    // Whatever the answer, the next step comes once Android's prompt is closed.
    val requestPerms = rememberPermissionsRequest { onNext() }
    fun askThenNext() {
        if (Permissions.missing(context).isEmpty()) onNext() else requestPerms(Permissions.runtime)
    }
    // Back from pairing: ask for contacts and notifications, then move on.
    LaunchedEffect(paired, pairingStarted) {
        if (paired && pairingStarted) askThenNext()
    }
    Title(stringResource(R.string.onboarding_connect_title), stringResource(R.string.onboarding_connect_body))
    if (paired) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.onboarding_connected), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_permissions),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = ::askThenNext, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.onboarding_continue)) }
    } else {
        Button(onClick = onPair, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp)) {
            Icon(Icons.Default.Sms, null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.onboarding_connect), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNext, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.onboarding_later)) }
    }
}

@Composable
private fun ColumnScope.TranslationStep(vm: SettingsViewModel, onPlans: () -> Unit, onFinish: () -> Unit) {
    val trial by vm.trial.state.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    var ownKey by remember { mutableStateOf(false) }
    // The free week started, or a plan was bought on the plans screen.
    LaunchedEffect(access) { if (access?.isManaged == true) onFinish() }
    // Play Integrity takes a moment to get ready; start now so the free week starts quickly.
    LaunchedEffect(Unit) { if (vm.canBuy) vm.warmTrial() }
    Title(stringResource(R.string.turn_on_title), stringResource(R.string.onboarding_translation_body))
    Benefit(Icons.Default.Translate, stringResource(R.string.onboarding_benefit_translate))
    Benefit(Icons.Default.School, stringResource(R.string.onboarding_benefit_tutor))
    Benefit(Icons.Default.Lightbulb, stringResource(R.string.onboarding_benefit_explain))
    Spacer(Modifier.height(28.dp))
    val failed = trial as? TrialUi.Failed
    if (!vm.canBuy) {
        // Not installed from Play (a GitHub APK): no free week here. The Play version, then the user's own key.
        GetOnPlay(subscribe = false, onOwnKey = { ownKey = true }, buttonHeight = 56.dp)
    } else if (failed != null) {
        TrialFailure(failed.error, onSubscribe = onPlans, onOwnKey = { ownKey = true }, onRetry = { vm.trial.start() })
    } else {
        Button(
            onClick = { vm.trial.start() }, enabled = trial != TrialUi.Starting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (trial == TrialUi.Starting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text(stringResource(R.string.access_start_trial), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.access_trial_subtext), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { ownKey = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.access_own_key)) }
    }
    TextButton(
        onClick = { vm.turnOffAi(); onFinish() }, enabled = trial != TrialUi.Starting,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) { Text(stringResource(R.string.action_not_now)) }
    if (ownKey) OwnKeyDialog(vm, onDismiss = { ownKey = false }, onDone = { ownKey = false; onFinish() })
}

@Composable
private fun Benefit(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
