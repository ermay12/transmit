package app.transmit.ui.access

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.annotation.StringRes
import app.transmit.R
import app.transmit.UiText
import app.transmit.string
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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

/** Wording that depends on the outcome; the strings themselves are resources. */
object AccessText {
    /** Why the free week couldn't start. Plain language: never about integrity verdicts, keys or servers. */
    @StringRes
    fun trialFailure(code: AccessErrorCode): Int = when (code) {
        AccessErrorCode.TRIAL_ALREADY_USED -> R.string.trial_failed_already_used
        AccessErrorCode.DEVICE_NOT_SUPPORTED -> R.string.trial_failed_device
        AccessErrorCode.NOT_FROM_PLAY -> R.string.play_needed_free_week
        AccessErrorCode.TRIALS_FULL -> R.string.trial_failed_full
        AccessErrorCode.RATE_LIMITED -> R.string.trial_failed_rate_limited
        AccessErrorCode.NETWORK -> R.string.ai_no_connection
        AccessErrorCode.BUSY -> R.string.trial_failed_busy
        else -> R.string.trial_failed_other
    }

    /** A copy not installed from Play can't buy either, so it isn't offered Subscribe. */
    fun canSubscribeAfter(code: AccessErrorCode) = code != AccessErrorCode.NOT_FROM_PLAY

    fun canRetry(code: AccessErrorCode) = code == AccessErrorCode.NETWORK || code == AccessErrorCode.BUSY || code == AccessErrorCode.SERVICE

    fun restoreResult(error: AccessError?, access: AiAccess): UiText = when (error?.code) {
        null -> when (access) {
            is AiAccess.Subscribed -> UiText.res(R.string.restore_done, UiText.res(access.tier.nameRes))
            is AiAccess.Ended -> UiText.res(R.string.ai_subscription_ended)
            else -> UiText.res(R.string.restore_none)
        }
        AccessErrorCode.NO_PURCHASE -> UiText.res(R.string.restore_none)
        AccessErrorCode.NOT_FROM_PLAY -> UiText.res(R.string.restore_not_from_play)
        AccessErrorCode.PAYMENT_PENDING -> UiText.res(R.string.restore_pending)
        AccessErrorCode.PAYMENT_PROBLEM -> UiText.res(R.string.restore_payment_problem)
        AccessErrorCode.NETWORK -> AiMessages.NO_CONNECTION
        else -> UiText.res(R.string.restore_failed)
    }

    /** One name for the end of the free week, whether its days or its AI ran out. */
    @StringRes
    fun ended(reason: EndReason): Int = if (reason.wasTrial) R.string.ended_free_week else R.string.ended_subscription

    fun date(context: Context, at: Long): String = UiText.Date(at).resolve(context)

    fun daysLeft(endsAt: Long, now: Long = System.currentTimeMillis()): Int =
        ((endsAt - now + DateUtils.DAY_IN_MILLIS - 1) / DateUtils.DAY_IN_MILLIS).toInt().coerceAtLeast(0)
}

/** The plan's name: "Transmit" or "Transmit Plus". */
val Tier.nameRes: Int @StringRes get() = if (this == Tier.PLUS) R.string.plan_plus_name else R.string.plan_standard_name

/** Google Play's page for this app's subscription. */
fun openManageSubscription(context: Context, tier: Tier?) {
    val uri = Uri.parse("https://play.google.com/store/account/subscriptions").buildUpon()
        .apply { tier?.let { appendQueryParameter("sku", it.productId) } }
        .appendQueryParameter("package", context.packageName)
        .build()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Transmit's listing on Google Play: in the Play Store app, or on the web where there's no Play Store. */
fun openPlayListing(context: Context) {
    fun view(uri: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    runCatching { view("market://details?id=$PLAY_LISTING_ID") }
        .recoverCatching { view("https://play.google.com/store/apps/details?id=$PLAY_LISTING_ID") }
}

/** The Play listing's id: the release's applicationId, whatever this build is called. */
private const val PLAY_LISTING_ID = "com.ermay12.transmit"

/**
 * "42% of your free week's AI used", a bar, and when it resets, on the free week's plan card. Never dollars.
 * Subscribers see the percent in Settings' Subscription row instead.
 */
@Composable
fun AllowanceBar(view: AllowanceView?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fraction = view?.fraction ?: 0.0
    Column(modifier.fillMaxWidth()) {
        Text(
            if (view == null) stringResource(R.string.allowance_trial) else stringResource(R.string.allowance_trial_used, view.percent),
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
            Text(stringResource(R.string.allowance_resets, AccessText.date(context, it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Why the free week couldn't start, and the ways forward. It's information, not an error (nothing is wrong with the
 * user's phone), so it's tonal, never red.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrialFailure(
    error: AccessError,
    onSubscribe: () -> Unit,
    onOwnKey: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(AccessText.trialFailure(error.code)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (error.code == AccessErrorCode.NOT_FROM_PLAY) Button(onClick = { openPlayListing(context) }) { Text(stringResource(R.string.play_get)) }
                if (AccessText.canSubscribeAfter(error.code)) Button(onClick = onSubscribe) { Text(stringResource(R.string.plan_subscribe)) }
                if (AccessText.canRetry(error.code)) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
                TextButton(onClick = onOwnKey) { Text(stringResource(R.string.access_own_key)) }
            }
        }
    }
}

/**
 * What a blocked AI call shows. BYOK budgets keep "Allow once"; managed modes never get it, and their wording never
 * mentions dollars, keys or settings. With no AI set up, the free week can start right here. Once the free week or
 * subscription has ended, the only way on is Subscribe; a copy not installed from Play offers the Play version first.
 */
@Composable
fun AiBlockDialog(
    block: AiBlock,
    trial: TrialUi,
    onStartTrial: () -> Unit,
    onPlans: () -> Unit,
    onOwnKey: () -> Unit,
    onAllowOnce: () -> Unit,
    onUseHere: () -> Unit,
    onDismiss: () -> Unit,
    /** False when not installed from Play: no free week or Subscribe, only the user's own key. */
    canBuy: Boolean = true,
) {
    data class Content(val title: UiText, val text: UiText, @StringRes val confirm: Int?, val onConfirm: () -> Unit, @StringRes val dismiss: Int)
    val context = LocalContext.current
    val getPlay = { openPlayListing(context) }
    // Translation has stopped, or never started, and only a plan would turn it on.
    val needsPlan = block == AiBlock.NotSetUp || block is AiBlock.Ended || (block is AiBlock.AllowanceUsedUp && block.access !is AiAccess.Subscribed)
    val content = if (!canBuy && needsPlan) {
        // Not installed from Play: the way on is the Play version. The user's own key is the second choice.
        val text = UiText.res(if (block == AiBlock.NotSetUp) R.string.play_needed_free_week else R.string.play_needed_subscribe)
        Content(blockTitle(block), text, R.string.play_get, getPlay, R.string.action_not_now)
    } else when (block) {
        is AiBlock.Budget -> Content(UiText.res(R.string.block_budget_title), block.message, R.string.block_allow_once, onAllowOnce, R.string.action_cancel)
        is AiBlock.AllowanceUsedUp -> when (val a = block.access) {
            is AiAccess.Subscribed -> {
                val upgrade = a.tier == Tier.STANDARD && canBuy
                val text = a.renewsAt?.let { UiText.res(if (upgrade) R.string.block_resets_upgrade else R.string.block_resets, UiText.Date(it)) }
                    ?: UiText.res(if (upgrade) R.string.block_resets_at_renewal_upgrade else R.string.block_resets_at_renewal)
                if (upgrade) Content(UiText.res(R.string.access_month_used), text, R.string.plan_upgrade, onPlans, R.string.action_ok)
                else Content(UiText.res(R.string.access_month_used), text, null, onDismiss, R.string.action_ok)
            }
            else -> Content(UiText.res(R.string.ended_free_week), UiText.res(R.string.access_keep_going), R.string.plan_subscribe, onPlans, R.string.action_not_now)
        }
        is AiBlock.Ended -> Content(UiText.res(AccessText.ended(block.reason)), UiText.res(R.string.access_keep_going), R.string.plan_subscribe, onPlans, R.string.action_not_now)
        AiBlock.InUseElsewhere -> Content(
            UiText.res(R.string.block_in_use_title), UiText.res(R.string.access_use_here_explained), R.string.access_use_here, onUseHere, R.string.action_not_now,
        )
        AiBlock.NotSetUp -> when (trial) {
            is TrialUi.Failed -> {
                val code = trial.error.code
                val title = UiText.res(R.string.trial_failed_title)
                val text = UiText.res(AccessText.trialFailure(code))
                when {
                    code == AccessErrorCode.NOT_FROM_PLAY -> Content(title, text, R.string.play_get, getPlay, R.string.action_not_now)
                    AccessText.canSubscribeAfter(code) -> Content(title, text, R.string.plan_subscribe, onPlans, R.string.action_not_now)
                    else -> Content(title, text, null, onDismiss, R.string.action_not_now)
                }
            }
            else -> Content(UiText.res(R.string.translation_off), UiText.res(R.string.block_off_body), R.string.access_start_trial, onStartTrial, R.string.action_not_now)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (block is AiBlock.Budget) Icons.Outlined.Savings else Icons.Default.AutoAwesome, null) },
        title = { Text(content.title.string()) },
        text = {
            Column {
                Text(content.text.string())
                if (block == AiBlock.NotSetUp && trial == TrialUi.Starting) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            if (content.confirm != null) {
                TextButton(onClick = content.onConfirm, enabled = trial != TrialUi.Starting) { Text(stringResource(content.confirm)) }
            }
        },
        dismissButton = {
            Row {
                // The user's own key, second to the free week or the Play version. An ended plan offers only Subscribe.
                val ownKey = if (canBuy) block == AiBlock.NotSetUp && trial is TrialUi.Failed else needsPlan
                if (ownKey) TextButton(onClick = onOwnKey) { Text(stringResource(R.string.access_own_key)) }
                TextButton(onClick = onDismiss) { Text(stringResource(content.dismiss)) }
            }
        },
    )
}

/** A blocked call's title when not installed from Play: what stopped, without the full stop. */
private fun blockTitle(block: AiBlock): UiText = UiText.res(
    when (block) {
        is AiBlock.AllowanceUsedUp -> if (block.access is AiAccess.Trial) R.string.ended_free_week else R.string.access_month_used
        is AiBlock.Ended -> AccessText.ended(block.reason)
        else -> R.string.translation_off
    },
)

/**
 * What a chat's translate icons and actions open while translation is off: a small sheet to turn it on. With no AI
 * set up it offers the free week; once the free week or a subscription has ended, only Subscribe. A copy not installed
 * from Play can buy neither, so it offers the Play version, then the user's own key. A free week that can't start says
 * why, in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurnOnTranslationSheet(
    access: AiAccess,
    canBuy: Boolean,
    trial: TrialUi,
    onStartTrial: () -> Unit,
    onPlans: () -> Unit,
    onOwnKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ended = access as? AiAccess.Ended
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Icon(Icons.Default.Translate, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            ended?.let {
                Text(stringResource(AccessText.ended(it.reason)), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
            }
            Text(stringResource(R.string.turn_on_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (ended == null) R.string.plans_benefits else R.string.access_keep_going),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            val failed = trial as? TrialUi.Failed
            when {
                !canBuy -> GetOnPlay(subscribe = ended != null, onOwnKey = onOwnKey)
                ended != null -> Button(onClick = onPlans, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.plan_subscribe)) }
                failed != null -> TrialFailure(failed.error, onSubscribe = onPlans, onOwnKey = onOwnKey, onRetry = onStartTrial)
                else -> {
                    Button(onClick = onStartTrial, enabled = trial != TrialUi.Starting, modifier = Modifier.fillMaxWidth()) {
                        if (trial == TrialUi.Starting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text(stringResource(R.string.access_start_trial))
                    }
                    Text(
                        stringResource(R.string.access_trial_subtext), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            TextButton(onClick = onDismiss, enabled = trial != TrialUi.Starting, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.action_not_now))
            }
        }
    }
}

/**
 * For a copy not installed from Play, which can't start the free week or subscribe: Get Transmit on Google Play, why,
 * and the user's own key as the second choice. [subscribe] says the free week is over, so the reason is about
 * subscribing.
 */
@Composable
fun ColumnScope.GetOnPlay(subscribe: Boolean, onOwnKey: () -> Unit, buttonHeight: Dp? = null) {
    val context = LocalContext.current
    Button(onClick = { openPlayListing(context) }, modifier = Modifier.fillMaxWidth().let { m -> buttonHeight?.let { m.height(it) } ?: m }) {
        Text(stringResource(R.string.play_get), style = if (buttonHeight != null) MaterialTheme.typography.titleMedium else LocalTextStyle.current)
    }
    Text(
        stringResource(if (subscribe) R.string.play_needed_subscribe else R.string.play_needed_free_week),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    TextButton(onClick = onOwnKey, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.access_own_key)) }
}

/**
 * The slim strip chats show with no AI set up: "Translation is off · Start your free week", or "Get Transmit on Google
 * Play" for a copy not installed from Play. Tapping the text does it; the cross hides the strip in every chat.
 */
@Composable
fun TranslationOffStrip(canBuy: Boolean, starting: Boolean, onAction: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val onColor = MaterialTheme.colorScheme.onSecondaryContainer
    val action = stringResource(
        when {
            !canBuy -> R.string.play_get
            starting -> R.string.strip_starting
            else -> R.string.access_start_trial
        },
    )
    val off = stringResource(R.string.translation_off)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 40.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Translate, null, Modifier.size(16.dp), tint = onColor)
            Spacer(Modifier.width(10.dp))
            Text(
                buildAnnotatedString {
                    append(off)
                    append(" · ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) { append(action) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = onColor,
                modifier = Modifier.weight(1f).clickable(enabled = !starting, onClick = onAction).padding(vertical = 10.dp),
            )
            if (starting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp), tint = onColor)
            }
        }
    }
}

/** What a Home banner's button does. */
enum class BannerAction { PLANS, USE_HERE, START_TRIAL, GET_PLAY }

/** Which dismissal a Home banner's close button records; each is remembered across restarts. */
enum class BannerDismiss {
    /** "Translation is off": hidden for good. */
    TRANSLATION_OFF,
    /** The allowance is nearly used: hidden until the period resets or the allowance runs out. */
    NEARLY_USED_UP,
}

/**
 * The Home banner: no AI set up yet, the managed AI nearly or entirely used, or the trial or subscription over.
 * [action] is the button's label, and [does] what it does. [dismiss] is null for a banner that can't be closed.
 * [ownKeyLink] adds "Use my own AI key" as a second, quieter choice.
 */
data class AllowanceBannerContent(
    val title: UiText,
    val body: UiText?,
    @StringRes val action: Int?,
    val dismiss: BannerDismiss?,
    val does: BannerAction = BannerAction.PLANS,
    val ownKeyLink: Boolean = false,
)

/**
 * [canBuy] is false when not installed from Play: then nothing offers Subscribe or Upgrade, and where translation is
 * off the banner offers the Play version, then the user's own key.
 */
fun allowanceBanner(access: AiAccess?, allowance: AllowanceView?, canBuy: Boolean = true): AllowanceBannerContent? = when (access) {
    // Dismissible for good: someone who only wants messaging can put it away. The Settings plan card stays.
    AiAccess.None ->
        if (canBuy) {
            AllowanceBannerContent(
                UiText.res(R.string.translation_off), UiText.res(R.string.access_trial_subtext), R.string.access_start_trial,
                BannerDismiss.TRANSLATION_OFF, BannerAction.START_TRIAL,
            )
        } else {
            AllowanceBannerContent(
                UiText.res(R.string.translation_off), UiText.res(R.string.play_needed_free_week), R.string.play_get,
                BannerDismiss.TRANSLATION_OFF, BannerAction.GET_PLAY, ownKeyLink = true,
            )
        }
    is AiAccess.Ended -> stopped(AccessText.ended(access.reason), canBuy)
    is AiAccess.Trial -> when {
        allowance == null || !allowance.nearlyUsedUp -> null
        allowance.usedUp -> stopped(R.string.ended_free_week, canBuy)
        else -> AllowanceBannerContent(
            UiText.res(R.string.banner_trial_nearly, allowance.percent),
            UiText.res(R.string.banner_trial_nearly_body).takeIf { canBuy }, R.string.plan_subscribe.takeIf { canBuy },
            BannerDismiss.NEARLY_USED_UP,
        )
    }
    is AiAccess.Subscribed -> {
        // Upgrade to Plus is for Standard, and only where there's something to buy.
        val upgrade = access.tier == Tier.STANDARD && canBuy
        val resets = allowance?.resetsAt
        when {
            access.inUseElsewhere -> AllowanceBannerContent(
                UiText.res(R.string.access_in_use_elsewhere), UiText.res(R.string.access_use_here_explained), R.string.access_use_here,
                null, BannerAction.USE_HERE,
            )
            allowance == null || !allowance.nearlyUsedUp -> null
            allowance.usedUp -> AllowanceBannerContent(
                UiText.res(R.string.access_month_used),
                when {
                    resets != null -> UiText.res(if (upgrade) R.string.banner_resets_upgrade else R.string.banner_resets, UiText.Date(resets))
                    upgrade -> UiText.res(R.string.banner_plus_twice)
                    else -> null
                },
                R.string.banner_upgrade.takeIf { upgrade }, null,
            )
            else -> AllowanceBannerContent(
                UiText.res(R.string.banner_month_nearly, allowance.percent), resets?.let { UiText.res(R.string.banner_resets, UiText.Date(it)) },
                R.string.banner_upgrade.takeIf { upgrade }, BannerDismiss.NEARLY_USED_UP,
            )
        }
    }
    else -> null
}

/**
 * The managed AI has stopped: only Subscribe. Where nothing can be bought, the Play version, then the user's own key,
 * so the banner is never a dead end. It can't be dismissed.
 */
private fun stopped(@StringRes title: Int, canBuy: Boolean) =
    if (canBuy) AllowanceBannerContent(UiText.res(title), UiText.res(R.string.banner_subscribe_to_keep), R.string.plan_subscribe, null)
    else AllowanceBannerContent(UiText.res(title), UiText.res(R.string.play_needed_subscribe), R.string.play_get, null, BannerAction.GET_PLAY, ownKeyLink = true)
