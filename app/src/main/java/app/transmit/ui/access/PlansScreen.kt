package app.transmit.ui.access

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.transmit.AppContainer
import app.transmit.R
import app.transmit.string
import app.transmit.access.AiAccess
import app.transmit.access.PlanOffer
import app.transmit.access.PurchaseUi
import app.transmit.access.RefreshTrigger
import app.transmit.access.Tier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The paywall: the two plans at Play's prices, buying, upgrading, switching Plus to Transmit, restoring and managing.
 */
class PlansViewModel(private val c: AppContainer) : ViewModel() {
    val access = c.access.access
    val purchase = c.purchases.state
    /** Installed from Play, so it can buy. */
    val canBuy = c.purchases.available
    /** Null while loading or when Play can't be reached. */
    val offers = MutableStateFlow<Map<Tier, PlanOffer>?>(null)
    val loading = MutableStateFlow(true)
    val restoring = MutableStateFlow(false)
    /**
     * Why the last purchase or plan change didn't work, as a string resource. It stays on screen until the next
     * attempt, so a refusal from Google Play is never silent (a snackbar can come and go while Play's own sheet is
     * still closing).
     */
    val problem = MutableStateFlow<Int?>(null)

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

    /**
     * Opens Play's sheet for [tier]: a purchase, an upgrade, or a switch from Plus to Transmit (see
     * `PurchasePlan.choose`). When the sheet can't open, [problem] says why.
     */
    fun buy(activity: Activity, tier: Tier) {
        problem.value = null
        // A subscriber's purchase is always a change of plan: an upgrade, or a switch to Transmit.
        val planChange = access.value is AiAccess.Subscribed
        viewModelScope.launch {
            problem.value = AccessText.launchProblem(c.purchases.buy(activity, tier), planChange)
        }
    }

    /** Buying or changing the plan didn't work: say so where the plans are. */
    fun failed(p: PurchaseUi.Failed) {
        problem.value = AccessText.purchaseProblem(p)
    }

    /** "Restore purchase", and "Use it here" for a subscription another device has. */
    fun restore(onResult: (String) -> Unit) {
        if (restoring.value) return
        restoring.value = true
        viewModelScope.launch {
            val error = c.access.restore()
            restoring.value = false
            onResult(AccessText.restoreResult(error, c.access.current()).resolve(c.context))
        }
    }

    fun purchaseSeen() = c.purchases.resetState()

    /** Set while Play's subscription page is open, so coming back refreshes at once. */
    private var awayInPlay = false

    /**
     * The screen is showing (opened, or back from elsewhere): bring the plan up to date, while the last known state
     * stays on screen. Back from Play's subscription page, where the plan may have just changed, it isn't debounced.
     */
    fun shown() {
        c.planRefresh.request(if (awayInPlay) RefreshTrigger.PLAY_PAGE_RETURNED else RefreshTrigger.PLANS_OPENED)
        awayInPlay = false
    }

    /** Play's page for this subscription: renewal, payment, resubscribing, cancelling. */
    fun manage(context: Context, tier: Tier?) {
        awayInPlay = true
        openManageSubscription(context, tier)
    }
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
    val problem by vm.problem.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun show(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(purchase) {
        when (val p = purchase) {
            is PurchaseUi.Done -> {
                Toast.makeText(context, context.getString(R.string.purchase_done, context.getString(p.tier.nameRes)), Toast.LENGTH_SHORT).show()
                vm.purchaseSeen()
                onBack()
            }
            // A switch stays here, where the plan now shows what's set.
            is PurchaseUi.SwitchSet -> {
                vm.purchaseSeen()
                p.at?.let { show(context.getString(R.string.plan_switch_done, AccessText.date(context, it))) }
            }
            is PurchaseUi.Failed -> {
                vm.failed(p)
                vm.purchaseSeen()
            }
            else -> Unit
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.shown()
        onPauseOrDispose {}
    }
    var confirmSwitch by rememberSaveable { mutableStateOf(false) }
    val subscribed = access as? AiAccess.Subscribed
    // Which plans, which action, and whether Switch to Transmit is offered (see PlansModel).
    val model = PlansModel.of(access)
    val owned = model.owned
    val switch = model.switch
    val switchAt = model.switchAt

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
            (current as? AiAccess.Ended)?.let {
                Text(stringResource(AccessText.ended(it.reason)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
            }
            if (subscribed?.inUseElsewhere == true) {
                Notice(stringResource(R.string.access_in_use_elsewhere_explained))
                Button(onClick = { vm.restore(::show) }, enabled = !restoring, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.access_use_here)) }
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
                // Not installed from Play: nothing can be bought here. The Play version, then the user's own key.
                Text(stringResource(R.string.play_needed_subscribe), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { openPlayListing(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.play_get), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOwnKey, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.access_own_key)) }
                PrivacyNote(null, Modifier.fillMaxWidth().padding(top = 8.dp))
                return@Column
            }
            if (owned == null) {
                Text(stringResource(R.string.plans_benefits), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))

            val busy = purchase == PurchaseUi.Working
            val shown = model.shown
            var choice by rememberSaveable { mutableStateOf(Tier.STANDARD.name) }
            // A Standard subscriber can only pick Plus.
            val selected = if (owned == Tier.STANDARD) Tier.PLUS else Tier.valueOf(choice)
            for (tier in shown) {
                val mine = tier == owned
                val date = { at: Long -> AccessText.date(context, at) }
                PlanOption(
                    tier = tier,
                    offer = offers?.get(tier),
                    loading = loading,
                    current = mine,
                    detail = when {
                        mine -> model.ownDetail?.string()
                        owned == Tier.PLUS && switch != null -> stringResource(R.string.plan_switch_starts, date(switch.startsAt))
                        else -> null
                    },
                    selected = owned == null && selected == tier,
                    selectable = owned == null && !busy,
                    onSelect = { choice = tier.name },
                    radio = model.action == PlansAction.SUBSCRIBE || model.action == PlansAction.UPGRADE,
                    scheduled = !mine && switch != null,
                    action = switchAt?.takeIf { !mine }?.let {
                        stringResource(R.string.plan_switch) + " · " + stringResource(R.string.plan_switch_takes_effect, date(it))
                    },
                    actionEnabled = !busy,
                    onAction = { confirmSwitch = true },
                )
                Spacer(Modifier.height(12.dp))
            }
            if (!loading && offers == null) {
                Text(
                    stringResource(R.string.plan_price_unavailable), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            val problemText = problem?.let { stringResource(it) }
            when {
                purchase == PurchaseUi.Working -> Notice(stringResource(R.string.purchase_working), busy = true)
                purchase == PurchaseUi.Pending -> Notice(stringResource(R.string.purchase_pending))
                problemText != null -> Notice(problemText, info = true)
                else -> Spacer(Modifier.height(12.dp))
            }
            model.note?.let { note ->
                if (model.endsAt != null) {
                    // Renewal is off: the plan works until then, and Keep my subscription is the way on.
                    Notice(note.string(), info = true)
                } else {
                    Text(
                        note.string(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
            if (model.action == PlansAction.KEEP_SUBSCRIPTION) {
                // Play's page for this plan, where Resubscribe turns renewal back on; coming back refreshes at once.
                Button(onClick = { vm.manage(context, owned) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.plan_keep_subscription), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.plan_keep_subscription_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                if (model.upgradeToo) Spacer(Modifier.height(16.dp))
            }
            if (model.action == PlansAction.SUBSCRIBE || model.action == PlansAction.UPGRADE || model.upgradeToo) {
                val upgrade = owned == Tier.STANDARD
                val buy = {
                    context.findActivity()?.let { vm.buy(it, selected) }
                    Unit
                }
                val label = @Composable { Text(stringResource(if (upgrade) R.string.plan_upgrade else R.string.plan_subscribe), style = MaterialTheme.typography.titleMedium) }
                // Beside Keep my subscription, upgrading is the second choice.
                if (model.upgradeToo) {
                    OutlinedButton(onClick = buy, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { label() }
                } else {
                    Button(onClick = buy, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { label() }
                }
                Spacer(Modifier.height(10.dp))
                // Subscriptions policy: that it renews, and at Play's price for the plan chosen.
                val price = offers?.get(selected)?.price
                Text(
                    if (price != null) stringResource(R.string.plan_auto_renew, price) else stringResource(R.string.plan_auto_renew_no_price),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                if (upgrade) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.plan_upgrade_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                SmallLink(stringResource(if (restoring) R.string.plan_restoring else R.string.plan_restore), enabled = !restoring && !busy) { vm.restore(::show) }
                if (owned != null) {
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallLink(stringResource(R.string.plan_manage)) { vm.manage(context, owned) }
                }
            }
            // Beside Manage subscription, where people go to cancel: a calm alternative, never a hurdle.
            if (switchAt != null) {
                SmallLink(stringResource(R.string.plan_switch_instead), enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { confirmSwitch = true }
            }
            Spacer(Modifier.height(8.dp))
            PrivacyNote(managedAiDisclosure(), Modifier.fillMaxWidth().padding(bottom = 16.dp))
        }
    }

    if (confirmSwitch && switchAt != null) {
        AlertDialog(
            onDismissRequest = { confirmSwitch = false },
            title = { Text(stringResource(R.string.plan_switch_confirm_title)) },
            text = { Text(stringResource(R.string.plan_switch_confirm_body, AccessText.date(context, switchAt))) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitch = false
                    val activity = context.findActivity() ?: return@TextButton
                    vm.buy(activity, Tier.STANDARD)
                }) { Text(stringResource(R.string.plan_switch)) }
            },
            dismissButton = { TextButton(onClick = { confirmSwitch = false }) { Text(stringResource(R.string.action_not_now)) } },
        )
    }
}

@Composable
private fun SmallLink(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** A tonal note above the actions: work in progress ([busy]), or something to know ([info]); never red. */
@Composable
private fun Notice(text: String, busy: Boolean = false, info: Boolean = false) {
    Spacer(Modifier.height(12.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            } else if (info) {
                Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(12.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    Spacer(Modifier.height(16.dp))
}

private val Tier.allowanceRes @StringRes get() = if (this == Tier.PLUS) R.string.plan_plus_allowance else R.string.plan_standard_allowance

/**
 * One plan: its name, one line of what it gives, and Play's price. A choice when nothing is owned yet; the
 * subscriber's own plan is marked, with its renewal (or switch) date in [detail]. With [radio] off there's no choice
 * to make: [action] makes the whole card a button (Plus's "Switch to Transmit"), and [scheduled] marks the plan a
 * switch moves to.
 */
@Composable
private fun PlanOption(
    tier: Tier,
    offer: PlanOffer?,
    loading: Boolean,
    current: Boolean,
    detail: String?,
    selected: Boolean,
    selectable: Boolean,
    onSelect: () -> Unit,
    radio: Boolean = true,
    scheduled: Boolean = false,
    action: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
) {
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
        modifier = Modifier.fillMaxWidth().then(
            when {
                action != null -> Modifier.clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction)
                radio && !current -> Modifier.selectable(selected = selected, enabled = selectable, role = Role.RadioButton, onClick = onSelect)
                else -> Modifier
            },
        ),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 18.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                current -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CheckCircle, null, tint = colors.primary)
                }
                // The whole card is the touch target; the radio only shows the choice.
                radio -> RadioButton(selected = selected, onClick = null, enabled = selectable || selected, modifier = Modifier.size(48.dp))
                else -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (scheduled) Icon(Icons.Outlined.Schedule, null, tint = colors.onSurfaceVariant)
                }
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
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                action?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelLarge, color = if (actionEnabled) colors.primary else colors.onSurfaceVariant)
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
