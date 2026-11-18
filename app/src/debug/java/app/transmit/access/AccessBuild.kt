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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds: a fake backend that can be switched between modes and outcomes, from Settings (at the bottom) or adb:
 *
 *     adb shell am broadcast -n com.ermay12.transmit/app.transmit.access.DebugAccessReceiver \
 *         --es mode TRIAL --ei allowance 85 --es trial TRIALS_FULL
 *
 * `mode` is a [FakeMode], `allowance` a percent used, `trial` the next [FakeTrialOutcome].
 */
object AccessBuild {
    private var fake: FakeAccessBackend? = null

    fun backend(context: Context, vault: KeyVault): Pair<AccessBackend, AllowanceSource> {
        val f = FakeAccessBackend(context, vault).also { fake = it }
        return f to f
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
            AiBlockDialog(block, TrialUi.Idle, onStartTrial = close, onPlans = close, onOwnKey = close, onAllowOnce = close, onDismiss = close)
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
            Text("Next free-week start", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FakeTrialOutcome.entries.forEach { o ->
                    FilterChip(selected = outcome == o, onClick = { outcome = o; f.trialOutcome = o }, label = { Text(o.label) })
                }
            }
            // The dialog a chat shows when AI is blocked in this mode, without needing a chat.
            val block = when (val a = current) {
                is AiAccess.Trial, is AiAccess.Subscribed -> AiBlock.AllowanceUsedUp(a)
                AiAccess.None -> AiBlock.NotSetUp
                is AiAccess.Ended -> AiBlock.Ended(a.reason)
                AiAccess.Byok, null -> null
            }
            if (block != null) FilterChip(selected = false, onClick = { preview = block }, label = { Text("Show blocked dialog") })
        }
    }
}

enum class FakeMode(val label: String) {
    NONE("None"),
    TRIAL("Trial"),
    STANDARD("Transmit"),
    PLUS("Plus"),
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

    fun subscriptionGrant(tier: Tier, newKey: Boolean = true): Grant {
        val usd = if (tier == Tier.PLUS) PLUS_USD else STANDARD_USD
        return Grant(
            Entitlement.Subscription(tier, System.currentTimeMillis() + 18 * DAY, autoRenewing = true),
            if (newKey) key() else null, "debug-sub", usd, remaining(usd), purchaseToken = "debug-token-${tier.productId}",
        )
    }

    override suspend fun startTrial(): AccessResult {
        delay(700)
        val outcome = trialOutcome
        outcome.code?.let { return AccessResult.Failed(AccessError(it, retryAfterMs = if (it == AccessErrorCode.TRIALS_FULL) 6 * HOUR else null)) }
        return AccessResult.Granted(trialGrant())
    }

    override suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?): AccessResult {
        delay(500)
        val tier = Tier.fromProductId(purchase.productId) ?: return AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        purchased = tier
        return AccessResult.Granted(subscriptionGrant(tier, newKey = currentKeyHash == null))
    }

    override suspend fun restore(): AccessResult {
        delay(500)
        val tier = purchased ?: return AccessResult.Failed(AccessError(AccessErrorCode.NO_PURCHASE))
        return AccessResult.Granted(subscriptionGrant(tier))
    }

    override suspend fun refresh(purchaseToken: String, keyHash: String?): AccessResult {
        val tier = purchased ?: return AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
        return AccessResult.Granted(subscriptionGrant(tier, newKey = false))
    }

    /** Nothing to reconcile with: the configured percent is the truth. */
    override suspend fun read(managedKey: String): AllowanceReading? = null

    suspend fun apply(access: AccessManager, mode: FakeMode) {
        Log.i("DebugAccess", "Mode $mode")
        when (mode) {
            FakeMode.NONE -> {
                purchased = null
                access.debugEdit { StoredAccess(lastKind = it.lastKind) }
            }
            FakeMode.TRIAL -> access.applyGrant(trialGrant(daysLeft = 4.5), activate = true)
            FakeMode.STANDARD, FakeMode.PLUS -> {
                val tier = if (mode == FakeMode.PLUS) Tier.PLUS else Tier.STANDARD
                purchased = tier
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
                access.applyGrant(subscriptionGrant(Tier.STANDARD), activate = true)
                access.markEnded(EndReason.SUBSCRIPTION_ENDED)
            }
            FakeMode.BYOK -> access.useOwnKey()
        }
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
                intent.getStringExtra("trial")?.let { v -> FakeTrialOutcome.entries.firstOrNull { it.name == v } }?.let { fake.trialOutcome = it }
                intent.getStringExtra("mode")?.let { v -> FakeMode.entries.firstOrNull { it.name == v } }?.let { fake.apply(container.access, it) }
                if (intent.hasExtra("allowance")) fake.setAllowance(container.access, intent.getIntExtra("allowance", 10))
            } finally {
                pending.finish()
            }
        }
    }
}
