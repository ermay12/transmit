package app.transmit.ui.access

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.transmit.AppContainer
import app.transmit.R
import app.transmit.access.AiAccess
import app.transmit.access.LaunchResult
import app.transmit.access.PlanOffer
import app.transmit.access.PurchaseUi
import app.transmit.access.Tier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The paywall: the two plans at Play's prices, buying, upgrading, restoring and managing. */
class PlansViewModel(private val c: AppContainer) : ViewModel() {
    val access = c.access.access
    val purchase = c.purchases.state
    /** Installed from Play, so it can buy. */
    val canBuy = c.purchases.available
    /** Null while loading or when Play can't be reached. */
    val offers = MutableStateFlow<Map<Tier, PlanOffer>?>(null)
    val loading = MutableStateFlow(true)
    val restoring = MutableStateFlow(false)

    init {
        // The screen is showing; a notification that led here has done its job.
        c.notifier.cancelPlan()
        c.trialCheck.warm()
        if (canBuy) viewModelScope.launch {
            offers.value = c.purchases.offers()
            loading.value = false
        } else {
            loading.value = false
        }
    }

    /** [onProblem] gets a string resource when Play's sheet couldn't open. */
    fun buy(activity: Activity, tier: Tier, onProblem: (Int) -> Unit) {
        viewModelScope.launch {
            when (c.purchases.buy(activity, tier)) {
                LaunchResult.LAUNCHED -> Unit
                LaunchResult.ALREADY_OWNED -> onProblem(R.string.purchase_already_owned)
                LaunchResult.UNAVAILABLE -> onProblem(R.string.purchase_play_unavailable)
                LaunchResult.FAILED -> onProblem(R.string.purchase_failed)
            }
        }
    }

    /** "Restore purchase", and "Use it here" for a subscription another device has. */
    fun restore(onResult: (String) -> Unit) {
        if (restoring.value) return
        restoring.value = true
        viewModelScope.launch {
            val error = c.access.restore()
            restoring.value = false
            onResult(AccessText.restoreResult(error, c.access.current()))
        }
    }

    fun purchaseSeen() = c.purchases.resetState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(vm: PlansViewModel, onBack: () -> Unit, onOwnKey: () -> Unit) {
    val context = LocalContext.current
    val access by vm.access.collectAsStateWithLifecycle()
    val purchase by vm.purchase.collectAsStateWithLifecycle()
    val offers by vm.offers.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val restoring by vm.restoring.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun show(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(purchase) {
        when (val p = purchase) {
            is PurchaseUi.Done -> {
                Toast.makeText(context, context.getString(R.string.purchase_done, p.tier.label), Toast.LENGTH_SHORT).show()
                vm.purchaseSeen()
                onBack()
            }
            is PurchaseUi.Failed -> {
                vm.purchaseSeen()
                show(context.getString(if (p.error?.retryable == true) R.string.purchase_retrying else R.string.purchase_failed))
            }
            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plans_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
            val current = access
            (current as? AiAccess.Ended)?.let {
                Text(AccessText.ended(it.reason), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            if (current is AiAccess.Subscribed && current.inUseElsewhere) {
                Notice("${AccessText.IN_USE_ELSEWHERE}. ${AccessText.USE_HERE_EXPLAINED}")
                Button(onClick = { vm.restore(::show) }, enabled = !restoring, modifier = Modifier.fillMaxWidth()) { Text(AccessText.USE_HERE) }
                Spacer(Modifier.height(20.dp))
            }
            Text(stringResource(R.string.plans_headline), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            if (!vm.canBuy) {
                Text(stringResource(R.string.plans_not_from_play), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOwnKey, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_own_key)) }
                return@Column
            }
            Text(stringResource(R.string.plans_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (purchase) {
                PurchaseUi.Working -> Notice(stringResource(R.string.purchase_working), busy = true)
                PurchaseUi.Pending -> Notice(stringResource(R.string.purchase_pending))
                else -> Spacer(Modifier.height(20.dp))
            }
            val busy = purchase == PurchaseUi.Working
            val owned = (current as? AiAccess.Subscribed)?.tier
            for (tier in Tier.entries) {
                PlanCard(
                    tier = tier,
                    offer = offers?.get(tier),
                    loading = loading,
                    owned = owned,
                    enabled = !busy,
                    onChoose = {
                        val activity = context.findActivity() ?: return@PlanCard
                        vm.buy(activity, tier) { show(context.getString(it)) }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
            if (owned != null) {
                OutlinedButton(onClick = { openManageSubscription(context, owned) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.plan_manage))
                }
            }
            TextButton(onClick = { vm.restore(::show) }, enabled = !restoring && !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(if (restoring) R.string.plan_restoring else R.string.plan_restore))
            }
        }
    }
}

@Composable
private fun Notice(text: String, busy: Boolean = false) {
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    Spacer(Modifier.height(16.dp))
}

private val Tier.nameRes @StringRes get() = if (this == Tier.PLUS) R.string.plan_plus_name else R.string.plan_standard_name
private val Tier.descriptionRes @StringRes get() = if (this == Tier.PLUS) R.string.plan_plus_description else R.string.plan_standard_description
private val Tier.allowanceRes @StringRes get() = if (this == Tier.PLUS) R.string.plan_plus_allowance else R.string.plan_standard_allowance

@Composable
private fun PlanCard(tier: Tier, offer: PlanOffer?, loading: Boolean, owned: Tier?, enabled: Boolean, onChoose: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(tier.nameRes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    when {
                        offer != null -> stringResource(R.string.plan_price_month, offer.price)
                        loading -> stringResource(R.string.plan_price_loading)
                        else -> stringResource(R.string.plan_price_unavailable)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(tier.descriptionRes), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(tier.allowanceRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            when {
                owned == tier -> OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.plan_current)) }
                owned == Tier.PLUS -> OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.plan_included)) }
                owned == Tier.STANDARD && tier == Tier.PLUS -> {
                    Button(onClick = onChoose, enabled = enabled) { Text(stringResource(R.string.plan_upgrade)) }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.plan_upgrade_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Button(onClick = onChoose, enabled = enabled) { Text(stringResource(R.string.plan_subscribe)) }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
