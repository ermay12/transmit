package app.transmit.ui.access

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.transmit.BuildConfig
import app.transmit.access.AiAccess
import app.transmit.access.Tier
import app.transmit.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Placeholder for the paywall (Play prices, the purchase flow, Standard → Plus with CHARGE_FULL_PRICE). It is the
 * PLANS destination that the plan card, budget prompts, onboarding and the end of a trial already lead to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val access by vm.access.collectAsStateWithLifecycle()
    val restoring by vm.restoring.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun show(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Plans") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
            (access as? AiAccess.Ended)?.let {
                Text(AccessText.ended(it.reason), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            Text("Keep translating", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Checkout isn't in this build yet. Prices will come from Google Play.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            PlanOption(Tier.STANDARD, "Translations as you chat, and a tutor for your replies", current = access) { tier ->
                vm.placeholderPurchase(tier) { error -> if (error != null) show(error) else onBack() }
            }
            Spacer(Modifier.height(12.dp))
            PlanOption(Tier.PLUS, "Twice the AI of Transmit", current = access) { tier ->
                vm.placeholderPurchase(tier) { error -> if (error != null) show(error) else onBack() }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { vm.restore(::show) }, enabled = !restoring, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (restoring) "Restoring…" else AccessText.RESTORE)
            }
        }
    }
}

@Composable
private fun PlanOption(tier: Tier, description: String, current: AiAccess?, onChoose: (Tier) -> Unit) {
    val owned = current is AiAccess.Subscribed && current.tier == tier
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(tier.label, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            // Only the debug build's fake backend can complete this stand-in purchase.
            Button(onClick = { onChoose(tier) }, enabled = !owned && BuildConfig.DEBUG) {
                Text(if (owned) "Your plan" else if (BuildConfig.DEBUG) "Choose (debug)" else "Coming soon")
            }
        }
    }
}
