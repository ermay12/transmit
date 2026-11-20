package app.transmit.access

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import app.transmit.ai.AiBlock
import app.transmit.ai.ProviderType
import app.transmit.app
import app.transmit.ui.access.AiBlockDialog
import app.transmit.ui.access.TrialUi
import app.transmit.data.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds: a fake backend that can be switched between modes and outcomes, from Settings (at the bottom) or adb:
 *
 *     adb shell am broadcast -n com.ermay12.transmit/app.transmit.access.DebugAccessReceiver \
 *         --es mode TRIAL --ei allowance 85 --es trial TRIALS_FULL
 *
 * `mode` is a [FakeMode], `allowance` a percent used, `trial` the next [FakeTrialOutcome]. `--es mode PLUS_SWITCHING`
 * is Plus with a switch to Transmit set for the renewal, and `--ez renew true` renews now (so a set switch takes
 * effect). `--es mode PLUS_CANCELED` (or `STANDARD_CANCELED`) is a plan with renewal turned off in Play; add
 * `--ei endsInSeconds 90` to have it end that soon (to watch it lapse with the app open). `--ez canceled false` (or
 * `true`) turns renewal on (off) on the fake Play's side only, as Resubscribe on Play's page would: the app sees it at
 * its next refresh (opening Plans or Settings). `--ez renewOnPlay true` renews on that side only, likewise.
 * On the Plans screen, Switch to Transmit acts as Play would. `--ei playError 5` makes Play's next sheet
 * fail with that response code (5 is DEVELOPER_ERROR, which a refused replacement mode gets). `--ez fromPlay false`
 * makes the fake act like a copy not installed from Play (no free week or subscriptions). `--es backend WORKER`
 * (or `FAKE`) picks the backend for the next start: WORKER is the live Worker with real Play Integrity and Billing,
 * without the installed-from-Play check, so the Worker's own answers show (an emulator gets `integrity_failed`).
 */
object AccessBuild {
    private var fake: FakeAccessBackend? = null

    fun services(context: Context, vault: KeyVault, scope: CoroutineScope): AccessServices {
        val f = FakeAccessBackend(context, vault).also { fake = it }
        if (f.backendChoice == DebugBackend.WORKER) {
            Log.i("DebugAccess", "Using the live Worker")
            val store = PlayBilling(context, available = true)
            val integrity = PlayIntegrity(context, scope)
            val backend = PlayAccessBackend(WorkerApi(), store, integrity, { PlayAccessBackend.androidId(context) }, checkInstall = false)
            return AccessServices(backend, OpenRouterAllowance(), store, integrity)
        }
        return AccessServices(f, f, FakeStorefront(f), object : TrialAttestation {
            override suspend fun token(requestHash: String) = "debug-token"
        })
    }

    fun fake(context: Context): FakeAccessBackend = fake ?: FakeAccessBackend(context, context.app.container.secrets)

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun DebugPanel(access: AccessManager, modifier: Modifier = Modifier) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val f = remember { fake(context) }
        val scope = rememberCoroutineScope()
        var outcome by remember { mutableStateOf(f.trialOutcome) }
        var percent by remember { mutableIntStateOf(f.allowancePercent) }
        val current by access.access.collectAsState()
        var preview by remember { mutableStateOf<AiBlock?>(null) }
        preview?.let { block ->
            val close = { preview = null }
            AiBlockDialog(block, TrialUi.Idle, onStartTrial = close, onPlans = close, onOwnKey = close, onAllowOnce = close, onUseHere = close, onDismiss = close)
        }
        Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Debug · AI access", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text("Mode", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FakeMode.entries.forEach { m ->
                    FilterChip(selected = false, onClick = { scope.launch { f.apply(access, m) } }, label = { Text(m.label) })
                }
            }
            Text("Allowance used", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 50, 85, 100).forEach { p ->
                    FilterChip(selected = percent == p, onClick = {
                        percent = p
                        scope.launch { f.setAllowance(access, p) }
                    }, label = { Text("$p%") })
                }
            }
            var fromPlay by remember { mutableStateOf(f.fromPlay) }
            Text("Installed from Play", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true to "Yes", false to "No (GitHub APK)").forEach { (v, label) ->
                    FilterChip(selected = fromPlay == v, onClick = { fromPlay = v; f.fromPlay = v }, label = { Text(label) })
                }
            }
            Text("Next free-week start", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FakeTrialOutcome.entries.forEach { o ->
                    FilterChip(selected = outcome == o, onClick = { outcome = o; f.trialOutcome = o }, label = { Text(o.label) })
                }
            }
            // The dialog a chat shows when AI is blocked in this mode, without needing a chat.
            val block = when (val a = current) {
                is AiAccess.Subscribed -> if (a.inUseElsewhere) AiBlock.InUseElsewhere else AiBlock.AllowanceUsedUp(a)
                is AiAccess.Trial -> AiBlock.AllowanceUsedUp(a)
                AiAccess.None -> AiBlock.NotSetUp
                is AiAccess.Ended -> AiBlock.Ended(a.reason)
                AiAccess.Byok, null -> null
            }
            if (block != null) FilterChip(selected = false, onClick = { preview = block }, label = { Text("Show blocked dialog") })
            if (current is AiAccess.Subscribed) FilterChip(selected = false, onClick = { scope.launch { f.renew(access) } }, label = { Text("Renew now") })
            var choice by remember { mutableStateOf(f.backendChoice) }
            Text("Backend (applies after a restart)", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DebugBackend.entries.forEach { b ->
                    FilterChip(selected = choice == b, onClick = { choice = b; f.backendChoice = b }, label = { Text(b.label) })
                }
            }
        }
    }
}

enum class DebugBackend(val label: String) {
    FAKE("Fake"),
    WORKER("Live Worker"),
}

/** Play for the fake backend: fixed prices, and a purchase sheet that buys at once. */
@OptIn(DelicateCoroutinesApi::class)
class FakeStorefront(private val fake: FakeAccessBackend) : Storefront {
    override val available get() = fake.fromPlay
    private val _updates = kotlinx.coroutines.flow.MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 4)
    override val updates: kotlinx.coroutines.flow.Flow<PurchaseUpdate> = _updates

    override suspend fun offers(): Map<Tier, PlanOffer> {
        delay(300)
        return mapOf(Tier.STANDARD to PlanOffer(Tier.STANDARD, "$5.99"), Tier.PLUS to PlanOffer(Tier.PLUS, "$11.99"))
    }

    override suspend fun purchases(): List<OwnedPurchase> = listOfNotNull(
        fake.purchased?.let { OwnedPurchase(it.productId, "debug-token-${it.productId}", purchased = true, acknowledged = true, autoRenewing = !fake.canceled) },
    )

    override suspend fun launch(activity: android.app.Activity, tier: Tier, plan: PurchasePlan): LaunchResult {
        if (plan == PurchasePlan.AlreadyOwned) return LaunchResult.ALREADY_OWNED
        if (plan == PurchasePlan.NotOffered) return LaunchResult.UNAVAILABLE
        fake.takePlayError()?.let { code ->
            Log.i("DebugAccess", "Fake Play error $code for $tier as $plan")
            kotlinx.coroutines.GlobalScope.launch {
                delay(800)
                _updates.emit(PurchaseUpdate.Failed(code, "Requested replacement mode is not supported for this request"))
            }
            return LaunchResult.LAUNCHED
        }
        Log.i("DebugAccess", "Fake purchase of $tier as $plan")
        // A switch to Transmit is set for the renewal; any other purchase clears it. Either way it renews.
        fake.switching = plan is PurchasePlan.Downgrade
        fake.canceled = false
        // Like Play, a switch's new token is listed as the plan it leaves (Plus) until the renewal.
        val listed = if (plan is PurchasePlan.Downgrade) Tier.PLUS else tier
        kotlinx.coroutines.GlobalScope.launch {
            delay(800)
            _updates.emit(PurchaseUpdate.Purchases(listOf(OwnedPurchase(listed.productId, "debug-token-${listed.productId}-${System.currentTimeMillis()}", purchased = true, acknowledged = false))))
        }
        return LaunchResult.LAUNCHED
    }
}

enum class FakeMode(val label: String) {
    NONE("None"),
    TRIAL("Trial"),
    STANDARD("Transmit"),
    PLUS("Plus"),
    PLUS_SWITCHING("Plus → Transmit"),
    STANDARD_CANCELED("Transmit, renewal off"),
    PLUS_CANCELED("Plus, renewal off"),
    ENDED_TRIAL("Trial expired"),
    ENDED_USED_UP("Trial used up"),
    ENDED_SUB("Sub ended"),
    BYOK("Own key"),
}

enum class FakeTrialOutcome(val label: String, val code: AccessErrorCode?) {
    SUCCESS("Success", null),
    TRIAL_ALREADY_USED("Already used", AccessErrorCode.TRIAL_ALREADY_USED),
    DEVICE_NOT_SUPPORTED("Device unsupported", AccessErrorCode.DEVICE_NOT_SUPPORTED),
    NOT_FROM_PLAY("Not from Play", AccessErrorCode.NOT_FROM_PLAY),
    TRIALS_FULL("Trials full", AccessErrorCode.TRIALS_FULL),
    NETWORK("No network", AccessErrorCode.NETWORK),
    SERVICE("Service problem", AccessErrorCode.SERVICE),
}

/**
 * Pretends to be the Worker and Play. The managed key is the user's own OpenRouter key when one is saved, so managed
 * calls can run for real on a debug build; otherwise a dummy that OpenRouter rejects.
 */
class FakeAccessBackend(context: Context, private val vault: KeyVault) : AccessBackend, AllowanceSource {
    private val prefs = context.getSharedPreferences("debug_access", Context.MODE_PRIVATE)

    var trialOutcome: FakeTrialOutcome
        get() = prefs.getString("trial", null)?.let { v -> FakeTrialOutcome.entries.firstOrNull { it.name == v } } ?: FakeTrialOutcome.SUCCESS
        set(v) = prefs.edit().putString("trial", v.name).apply()

    var allowancePercent: Int
        get() = prefs.getInt("allowance", 10)
        set(v) = prefs.edit().putInt("allowance", v).apply()

    /** False to act like a copy not installed from Play: no free week, subscriptions or restore. */
    var fromPlay: Boolean
        get() = prefs.getBoolean("from_play", true)
        set(v) = prefs.edit().putBoolean("from_play", v).apply()

    var backendChoice: DebugBackend
        get() = prefs.getString("backend", null)?.let { v -> DebugBackend.entries.firstOrNull { it.name == v } } ?: DebugBackend.FAKE
        set(v) = prefs.edit().putString("backend", v.name).apply()

    /** Plus with a switch to Transmit set for the renewal, as the Worker reports it with `pending…` fields. */
    var switching: Boolean
        get() = prefs.getBoolean("switching", false)
        set(v) = prefs.edit().putBoolean("switching", v).apply()

    /** Renewal turned off in Play: the Worker reports `state: "canceled"` and the plan ends at its period's end. */
    var canceled: Boolean
        get() = prefs.getBoolean("canceled", false)
        set(v) = prefs.edit().putBoolean("canceled", v).apply()

    /** When the paid period ends, fixed by `endsInSeconds`; 0 for 18 days from each answer. */
    private var periodEnd: Long
        get() = prefs.getLong("period_end", 0)
        set(v) = prefs.edit().putLong("period_end", v).apply()

    fun endIn(seconds: Int) {
        periodEnd = if (seconds > 0) System.currentTimeMillis() + seconds * 1000L else 0
    }

    /** The response code Play's next purchase sheet fails with, once; 0 for none. */
    var playError: Int
        get() = prefs.getInt("play_error", 0)
        set(v) = prefs.edit().putInt("play_error", v).apply()

    fun takePlayError(): Int? = playError.takeIf { it != 0 }?.also { playError = 0 }

    /** The subscription "on Play", for restore and refresh. */
    var purchased: Tier?
        get() = prefs.getString("purchased", null)?.let { v -> Tier.entries.firstOrNull { it.name == v } }
        set(v) = prefs.edit().putString("purchased", v?.name).apply()

    private fun key() = vault.get(ProviderType.OPENROUTER.secretName) ?: "sk-or-v1-debug-fake"

    private fun remaining(allowance: Double) = allowance * (1 - allowancePercent / 100.0)

    fun trialGrant(daysLeft: Double = ManagedAi.TRIAL_DAYS.toDouble()): Grant = Grant(
        Entitlement.Trial(System.currentTimeMillis() + (daysLeft * DAY).toLong()),
        key(), "debug-trial", TRIAL_USD, remaining(TRIAL_USD),
    )

    /** [renewed]: like the Worker's `allowanceRenewed`, this call credited a new order (a purchase, a renewal). */
    fun subscriptionGrant(tier: Tier, newKey: Boolean = true, renewed: Boolean = false): Grant {
        val usd = if (tier == Tier.PLUS) PLUS_USD else STANDARD_USD
        val ends = periodEnd.takeIf { it > 0 } ?: (System.currentTimeMillis() + 18 * DAY)
        // Like the Worker: no `pending…` fields once renewal is off.
        val switch = PlanSwitch(Tier.STANDARD, ends).takeIf { switching && tier == Tier.PLUS && !canceled }
        return Grant(
            Entitlement.Subscription(tier, ends, autoRenewing = !canceled, pendingSwitch = switch),
            if (newKey) key() else null, "debug-sub", usd, remaining(usd), purchaseToken = "debug-token-${tier.productId}",
            allowanceRenewed = renewed,
        )
    }

    override suspend fun startTrial(): AccessResult {
        delay(700)
        if (!fromPlay) return AccessResult.Failed(AccessError(AccessErrorCode.NOT_FROM_PLAY))
        val outcome = trialOutcome
        outcome.code?.let { return AccessResult.Failed(AccessError(it, retryAfterMs = if (it == AccessErrorCode.TRIALS_FULL) 6 * HOUR else null)) }
        return AccessResult.Granted(trialGrant())
    }

    override suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?): AccessResult {
        delay(500)
        val tier = Tier.fromProductId(purchase.productId) ?: return AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        purchased = tier
        return AccessResult.Granted(subscriptionGrant(tier, newKey = currentKeyHash == null, renewed = true))
    }

    override suspend fun restore(currentKeyHash: String?): AccessResult {
        delay(500)
        if (!fromPlay) return AccessResult.Failed(AccessError(AccessErrorCode.NOT_FROM_PLAY))
        val tier = purchased ?: return AccessResult.Failed(AccessError(AccessErrorCode.NO_PURCHASE))
        return AccessResult.Granted(subscriptionGrant(tier))
    }

    override suspend fun refresh(purchaseToken: String, keyHash: String?): AccessResult {
        delay(400)
        // A plan with renewal off, past its end, has lapsed.
        if (canceled && periodEnd in 1..System.currentTimeMillis()) lapse()
        val tier = purchased ?: return AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
        return AccessResult.Granted(subscriptionGrant(tier, newKey = false))
    }

    /** Nothing to reconcile with: the configured percent is the truth. */
    override suspend fun read(managedKey: String): AllowanceCheck = AllowanceCheck.Unavailable

    suspend fun apply(access: AccessManager, mode: FakeMode) {
        Log.i("DebugAccess", "Mode $mode")
        when (mode) {
            FakeMode.NONE -> {
                purchased = null
                switching = false
                canceled = false
                access.debugEdit { StoredAccess(lastKind = it.lastKind) }
            }
            FakeMode.TRIAL -> access.applyGrant(trialGrant(daysLeft = 4.5), activate = true)
            FakeMode.STANDARD, FakeMode.PLUS, FakeMode.PLUS_SWITCHING, FakeMode.STANDARD_CANCELED, FakeMode.PLUS_CANCELED -> {
                val tier = if (mode == FakeMode.STANDARD || mode == FakeMode.STANDARD_CANCELED) Tier.STANDARD else Tier.PLUS
                purchased = tier
                switching = mode == FakeMode.PLUS_SWITCHING
                canceled = mode == FakeMode.STANDARD_CANCELED || mode == FakeMode.PLUS_CANCELED
                access.applyGrant(subscriptionGrant(tier), activate = true)
            }
            FakeMode.ENDED_TRIAL -> {
                access.applyGrant(trialGrant(), activate = true)
                access.debugEdit { it.copy(entitlement = Entitlement.Trial(System.currentTimeMillis() - HOUR)) }
            }
            FakeMode.ENDED_USED_UP -> {
                access.applyGrant(trialGrant(), activate = true)
                access.markEnded(EndReason.TRIAL_USED_UP)
            }
            FakeMode.ENDED_SUB -> {
                purchased = null
                switching = false
                canceled = false
                access.applyGrant(subscriptionGrant(Tier.STANDARD), activate = true)
                access.markEnded(EndReason.SUBSCRIPTION_ENDED)
            }
            FakeMode.BYOK -> access.useOwnKey()
        }
    }

    /**
     * The subscription renews now, as the Worker would report it: a set switch takes effect (Transmit, its
     * allowance, no switch), and the allowance starts over.
     */
    suspend fun renew(access: AccessManager) {
        val tier = renewOnPlay()
        if (tier == null) {
            access.refresh(force = true)
            return
        }
        access.applyGrant(subscriptionGrant(tier, newKey = false, renewed = true), activate = false)
    }

    /**
     * The renewal on the fake Play's side only (the app hears at its next refresh): a set switch takes effect, the
     * allowance starts over; with renewal off, the plan lapses instead. The plan it renews as, if any.
     */
    fun renewOnPlay(): Tier? {
        if (canceled) {
            Log.i("DebugAccess", "Lapse")
            lapse()
            return null
        }
        val tier = (if (switching) Tier.STANDARD else purchased) ?: return null
        Log.i("DebugAccess", "Renewal as $tier")
        purchased = tier
        switching = false
        allowancePercent = 0
        periodEnd = 0
        return tier
    }

    private fun lapse() {
        purchased = null
        switching = false
        canceled = false
        periodEnd = 0
    }

    /** Sets the allowance used, as if the backend had just reported it, then runs the after-call check. */
    suspend fun setAllowance(access: AccessManager, percent: Int) {
        allowancePercent = percent
        access.setBaseline { b ->
            val usd = b.allowanceUsd.takeIf { it > 0 } ?: TRIAL_USD
            AllowanceBaseline(usd, usd * (1 - percent / 100.0), System.currentTimeMillis())
        }
        access.afterManagedCall()
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        const val TRIAL_USD = 0.5
        const val STANDARD_USD = 4.5
        const val PLUS_USD = 9.0
    }
}

/** adb hook for [AccessBuild]'s fake; see there. Needs the DUMP permission, which only the shell holds. */
class DebugAccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.app.container
        val fake = AccessBuild.fake(context)
        val pending = goAsync()
        container.scope.launch {
            try {
                intent.getStringExtra("backend")?.let { v -> DebugBackend.entries.firstOrNull { it.name == v } }?.let { fake.backendChoice = it }
                if (intent.hasExtra("fromPlay")) fake.fromPlay = intent.getBooleanExtra("fromPlay", true)
                intent.getStringExtra("trial")?.let { v -> FakeTrialOutcome.entries.firstOrNull { it.name == v } }?.let { fake.trialOutcome = it }
                // A mode starts a period of 18 days unless told otherwise.
                if (intent.hasExtra("endsInSeconds") || intent.hasExtra("mode")) fake.endIn(intent.getIntExtra("endsInSeconds", 0))
                intent.getStringExtra("mode")?.let { v -> FakeMode.entries.firstOrNull { it.name == v } }?.let { fake.apply(container.access, it) }
                if (intent.hasExtra("canceled")) fake.canceled = intent.getBooleanExtra("canceled", false)
                if (intent.getBooleanExtra("renewOnPlay", false)) fake.renewOnPlay()
                if (intent.hasExtra("allowance")) fake.setAllowance(container.access, intent.getIntExtra("allowance", 10))
                if (intent.hasExtra("playError")) fake.playError = intent.getIntExtra("playError", 0)
                if (intent.getBooleanExtra("renew", false)) fake.renew(container.access)
            } finally {
                pending.finish()
            }
        }
    }
}
