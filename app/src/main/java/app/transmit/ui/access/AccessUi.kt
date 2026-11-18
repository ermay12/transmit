package app.transmit.ui.access

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.transmit.access.AccessError
import app.transmit.access.AccessErrorCode
import app.transmit.access.AccessManager
import app.transmit.access.AiAccess
import app.transmit.access.AllowanceView
import app.transmit.access.EndReason
import app.transmit.access.Tier
import app.transmit.ai.AiBlock
import app.transmit.ai.AiMessages
import app.transmit.ui.theme.LocalTransmitColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Starting the free trial from any screen: idle, starting, or failed with a reason to show. */
sealed interface TrialUi {
    data object Idle : TrialUi
    data object Starting : TrialUi
    data class Failed(val error: AccessError) : TrialUi
}

class TrialStarter(private val scope: CoroutineScope, private val access: AccessManager) {
    private val _state = MutableStateFlow<TrialUi>(TrialUi.Idle)
    val state: StateFlow<TrialUi> = _state

    fun start(onStarted: () -> Unit = {}) {
        if (_state.value == TrialUi.Starting) return
        _state.value = TrialUi.Starting
        scope.launch {
            val error = access.startTrial()
            _state.value = if (error == null) TrialUi.Idle else TrialUi.Failed(error)
            if (error == null) onStarted()
        }
    }

    fun reset() {
        if (_state.value != TrialUi.Starting) _state.value = TrialUi.Idle
    }
}

object AccessText {
    const val START_TRIAL = "Start your free week"
    const val TRIAL_SUBTEXT = "7 days free. No card needed."
    const val OWN_KEY = "Use my own AI key"
    const val SUBSCRIBE = "Subscribe"
    const val UPGRADE = "Upgrade to Plus"
    const val RESTORE = "Restore purchase"
    const val KEEP_GOING = "Subscribe to keep translating and getting help with your replies."

    /** Plain language: never about integrity verdicts, keys or servers. */
    fun trialFailure(code: AccessErrorCode): String = when (code) {
        AccessErrorCode.TRIAL_ALREADY_USED -> "This device has already used its free trial."
        AccessErrorCode.DEVICE_NOT_SUPPORTED -> "This device isn't supported for free trials."
        AccessErrorCode.NOT_FROM_PLAY -> "Free trials need Transmit installed from Google Play."
        AccessErrorCode.TRIALS_FULL -> "Free trials are full today. Try again tomorrow."
        AccessErrorCode.NETWORK -> AiMessages.NO_CONNECTION
        AccessErrorCode.BUSY -> "Still working on it. Try again in a moment."
        else -> "Your free week couldn't start right now. Try again later."
    }

    /** A copy not installed from Play can't buy either, so it isn't offered Subscribe. */
    fun canSubscribeAfter(code: AccessErrorCode) = code != AccessErrorCode.NOT_FROM_PLAY

    fun canRetry(code: AccessErrorCode) = code == AccessErrorCode.NETWORK || code == AccessErrorCode.BUSY || code == AccessErrorCode.SERVICE

    fun restoreResult(error: AccessError?, access: AiAccess): String = when (error?.code) {
        null -> when (access) {
            is AiAccess.Subscribed -> "${access.tier.label} is restored."
            is AiAccess.Ended -> "Your subscription has ended."
            else -> "No subscription found on this Google account."
        }
        AccessErrorCode.NO_PURCHASE -> "No subscription found on this Google account."
        AccessErrorCode.NOT_FROM_PLAY -> "Restoring needs Transmit installed from Google Play."
        AccessErrorCode.PAYMENT_PENDING -> "Your payment is still being processed."
        AccessErrorCode.PAYMENT_PROBLEM -> "There's a problem with your payment. Fix it in Google Play."
        AccessErrorCode.NETWORK -> AiMessages.NO_CONNECTION
        else -> "Couldn't restore right now. Try again later."
    }

    fun ended(reason: EndReason) = when (reason) {
        EndReason.TRIAL_USED_UP -> "Your free trial's AI is used up"
        EndReason.TRIAL_EXPIRED -> "Your free trial has ended"
        EndReason.SUBSCRIPTION_ENDED -> "Your subscription has ended"
    }

    fun date(context: Context, at: Long): String =
        DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    fun daysLeft(endsAt: Long, now: Long = System.currentTimeMillis()): Int =
        ((endsAt - now + DateUtils.DAY_IN_MILLIS - 1) / DateUtils.DAY_IN_MILLIS).toInt().coerceAtLeast(0)

    fun trialTitle(endsAt: Long): String = when (val d = daysLeft(endsAt)) {
        0, 1 -> "Free trial · 1 day left"
        else -> "Free trial · $d days left"
    }
}

/** Google Play's page for this app's subscription. */
fun openManageSubscription(context: Context, tier: Tier?) {
    val uri = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon()
        .apply { tier?.let { appendQueryParameter("sku", it.productId) } }
        .appendQueryParameter("package", context.packageName)
        .build()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** "42% of this month's AI used", a bar, and when it resets. Never dollars. */
@Composable
fun AllowanceBar(view: AllowanceView?, trial: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fraction = view?.fraction ?: 0.0
    Column(modifier.fillMaxWidth()) {
        Text(
            when {
                view == null -> if (trial) "Your trial's AI" else "This month's AI"
                trial -> "${view.percent}% of your trial's AI used"
                else -> "${view.percent}% of this month's AI used"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = when {
                fraction >= 1.0 -> MaterialTheme.colorScheme.error
                fraction >= AllowanceView.WARN_AT -> LocalTransmitColors.current.warn
                else -> MaterialTheme.colorScheme.primary
            },
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        view?.resetsAt?.let {
            Spacer(Modifier.height(4.dp))
            Text("Resets ${AccessText.date(context, it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Why the free week couldn't start, and the ways forward. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrialFailure(
    error: AccessError,
    onSubscribe: () -> Unit,
    onOwnKey: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text(AccessText.trialFailure(error.code), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (AccessText.canSubscribeAfter(error.code)) Button(onClick = onSubscribe) { Text(AccessText.SUBSCRIBE) }
                if (AccessText.canRetry(error.code)) TextButton(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = onOwnKey) { Text(AccessText.OWN_KEY) }
            }
        }
    }
}

/**
 * What a blocked AI call shows. BYOK budgets keep "Allow once"; managed modes never get it, and their wording never
 * mentions dollars, keys or settings. With no AI set up, the free week can start right here.
 */
@Composable
fun AiBlockDialog(
    block: AiBlock,
    trial: TrialUi,
    onStartTrial: () -> Unit,
    onPlans: () -> Unit,
    onOwnKey: () -> Unit,
    onAllowOnce: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    data class Content(val title: String, val text: String, val confirm: String?, val onConfirm: () -> Unit, val dismiss: String)
    val content = when (block) {
        is AiBlock.Budget -> Content("Budget reached", block.message, "Allow once", onAllowOnce, "Cancel")
        is AiBlock.AllowanceUsedUp -> when (val a = block.access) {
            is AiAccess.Subscribed -> {
                val reset = a.renewsAt?.let { "It resets ${AccessText.date(context, it)}." } ?: "It resets when your subscription renews."
                if (a.tier == Tier.STANDARD) Content("You've used this month's AI", "$reset Transmit Plus has twice the AI.", AccessText.UPGRADE, onPlans, "OK")
                else Content("You've used this month's AI", reset, null, onDismiss, "OK")
            }
            else -> Content(AccessText.ended(EndReason.TRIAL_USED_UP), AccessText.KEEP_GOING, AccessText.SUBSCRIBE, onPlans, "Not now")
        }
        is AiBlock.Ended -> Content(AccessText.ended(block.reason), AccessText.KEEP_GOING, AccessText.SUBSCRIBE, onPlans, "Not now")
        AiBlock.NotSetUp -> when (trial) {
            is TrialUi.Failed -> Content(
                "Couldn't start your free week", AccessText.trialFailure(trial.error.code),
                AccessText.SUBSCRIBE.takeIf { AccessText.canSubscribeAfter(trial.error.code) }, onPlans, "Not now",
            )
            else -> Content(
                "Translation is off", "Start your free week to translate messages and get help with your replies. ${AccessText.TRIAL_SUBTEXT}",
                AccessText.START_TRIAL, onStartTrial, "Not now",
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (block is AiBlock.Budget) Icons.Outlined.Savings else Icons.Default.AutoAwesome, null) },
        title = { Text(content.title) },
        text = {
            Column {
                Text(content.text)
                if (block == AiBlock.NotSetUp && trial == TrialUi.Starting) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            if (content.confirm != null) {
                TextButton(onClick = content.onConfirm, enabled = trial != TrialUi.Starting) { Text(content.confirm) }
            }
        },
        dismissButton = {
            Row {
                if (block == AiBlock.NotSetUp && trial is TrialUi.Failed) TextButton(onClick = onOwnKey) { Text(AccessText.OWN_KEY) }
                TextButton(onClick = onDismiss) { Text(content.dismiss) }
            }
        },
    )
}

/** The Home banner when the managed AI is nearly or entirely used, or the trial or subscription is over. */
data class AllowanceBannerContent(val title: String, val body: String?, val action: String?, val dismissible: Boolean)

fun allowanceBanner(context: Context, access: AiAccess?, allowance: AllowanceView?): AllowanceBannerContent? {
    fun resets(at: Long?) = at?.let { "Resets ${AccessText.date(context, it)}." }
    return when (access) {
        is AiAccess.Ended -> AllowanceBannerContent(AccessText.ended(access.reason), "Subscribe to keep translating.", AccessText.SUBSCRIBE, false)
        is AiAccess.Trial -> when {
            allowance == null || !allowance.nearlyUsedUp -> null
            allowance.usedUp -> AllowanceBannerContent(AccessText.ended(EndReason.TRIAL_USED_UP), "Subscribe to keep translating.", AccessText.SUBSCRIBE, false)
            else -> AllowanceBannerContent("You've used ${allowance.percent}% of your free trial's AI", "Subscribe to keep translating after it runs out.", AccessText.SUBSCRIBE, true)
        }
        is AiAccess.Subscribed -> {
            val upgrade = if (access.tier == Tier.STANDARD) "Upgrade" else null
            when {
                allowance == null || !allowance.nearlyUsedUp -> null
                allowance.usedUp -> AllowanceBannerContent(
                    "You've used this month's AI",
                    listOfNotNull(resets(allowance.resetsAt), "Transmit Plus has twice the AI.".takeIf { upgrade != null }).joinToString(" "),
                    upgrade, false,
                )
                else -> AllowanceBannerContent("You've used ${allowance.percent}% of this month's AI", resets(allowance.resetsAt), upgrade, true)
            }
        }
        else -> null
    }
}
