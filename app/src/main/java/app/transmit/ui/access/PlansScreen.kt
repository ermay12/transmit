package app.transmit.ui.access

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            val current = access
            val subscribed = current as? AiAccess.Subscribed
            val owned = subscribed?.tier
            (current as? AiAccess.Ended)?.let {
                Text(AccessText.ended(it.reason), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
            }
            if (subscribed?.inUseElsewhere == true) {
                Notice("${AccessText.IN_USE_ELSEWHERE}. ${AccessText.USE_HERE_EXPLAINED}")
                Button(onClick = { vm.restore(::show) }, enabled = !restoring, modifier = Modifier.fillMaxWidth()) { Text(AccessText.USE_HERE) }
                Spacer(Modifier.height(24.dp))
            }
            Text(
                stringResource(
                    when (current) {
                        is AiAccess.Subscribed -> R.string.plans_headline_subscribed
                        is AiAccess.Trial -> R.string.plans_headline_trial
                        is AiAccess.Ended -> R.string.plans_headline_ended
                        else -> R.string.plans_headline_none
                    },
                ),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (!vm.canBuy) {
                Text(stringResource(R.string.plans_not_from_play), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onOwnKey, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.plan_own_key)) }
                return@Column
            }
            if (owned == null) {
                Text(stringResource(R.string.plans_benefits), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))

            // Plus has nothing to move up to, so a Plus subscriber sees just their plan.
            val shown = if (owned == Tier.PLUS) listOf(Tier.PLUS) else Tier.entries
            var choice by rememberSaveable { mutableStateOf(Tier.STANDARD.name) }
            // A Standard subscriber can only pick Plus.
            val selected = if (owned == Tier.STANDARD) Tier.PLUS else Tier.valueOf(choice)
            val busy = purchase == PurchaseUi.Working
            for (tier in shown) {
                PlanOption(
                    tier = tier,
                    offer = offers?.get(tier),
                    loading = loading,
                    current = tier == owned,
                    renewsAt = subscribed?.renewsAt.takeIf { tier == owned },
                    selected = owned != tier && selected == tier,
                    selectable = owned == null && !busy,
                    onSelect = { choice = tier.name },
                )
                Spacer(Modifier.height(12.dp))
            }
            if (!loading && offers == null) {
                Text(
                    stringResource(R.string.plan_price_unavailable), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            when (purchase) {
                PurchaseUi.Working -> Notice(stringResource(R.string.purchase_working), busy = true)
                PurchaseUi.Pending -> Notice(stringResource(R.string.purchase_pending))
                else -> Spacer(Modifier.height(12.dp))
            }
            if (owned != Tier.PLUS) {
                val upgrade = owned == Tier.STANDARD
                Button(
                    onClick = {
                        val activity = context.findActivity() ?: return@Button
                        vm.buy(activity, selected) { show(context.getString(it)) }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(if (upgrade) R.string.plan_upgrade else R.string.plan_subscribe), style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(if (upgrade) R.string.plan_upgrade_note else R.string.plans_cancel_anytime),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                SmallLink(stringResource(if (restoring) R.string.plan_restoring else R.string.plan_restore), enabled = !restoring && !busy) { vm.restore(::show) }
                if (owned != null) {
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallLink(stringResource(R.string.plan_manage)) { openManageSubscription(context, owned) }
                }
            }
        }
    }
}

@Composable
private fun SmallLink(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
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
private val Tier.allowanceRes @StringRes get() = if (this == Tier.PLUS) R.string.plan_plus_allowance else R.string.plan_standard_allowance

/**
 * One plan: its name, one line of what it gives, and Play's price. A choice when nothing is owned yet; the
 * subscriber's own plan is marked, with its renewal date.
 */
@Composable
private fun PlanOption(
    tier: Tier,
    offer: PlanOffer?,
    loading: Boolean,
    current: Boolean,
    renewsAt: Long?,
    selected: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // The plan you have is filled in; the one you'd pick is outlined.
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = when {
            current -> colors.secondaryContainer
            selected -> colors.primaryContainer.copy(alpha = 0.35f)
            else -> colors.surface
        },
        border = when {
            current -> null
            selected -> BorderStroke(2.dp, colors.primary)
            else -> BorderStroke(1.dp, colors.outlineVariant)
        },
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = selectable, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 18.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CheckCircle, null, tint = colors.primary)
                }
            } else {
                // The whole card is the touch target; the radio only shows the choice.
                RadioButton(selected = selected, onClick = null, enabled = selectable || selected, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(tier.nameRes), style = MaterialTheme.typography.titleMedium)
                    if (current) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = colors.primary) {
                            Text(
                                stringResource(R.string.plan_current), style = MaterialTheme.typography.labelSmall, color = colors.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(stringResource(tier.allowanceRes), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                renewsAt?.let {
                    Text(stringResource(R.string.plan_renews, AccessText.date(context, it)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                offer != null -> Column(horizontalAlignment = Alignment.End) {
                    Text(offer.price, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.plan_per_month), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
