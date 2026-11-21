package app.transmit.ui

import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.SystemClock
import android.provider.Settings
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.transmit.R

/**
 * Google Messages alerts to every text too, so with Transmit each one arrives twice. Its "Incoming messages"
 * notifications can be turned off on their own, which leaves its other notifications on.
 */
object GoogleMessagesAlerts {
    const val PACKAGE = "com.google.android.apps.messaging"
    /** Google Messages' "Incoming messages" channel. */
    const val CHANNEL = "bugle_default_channel"
    /** Settings closes at once on a channel the app doesn't have; a return this soon means it did. */
    const val BOUNCE_MS = 1_500L

    fun installed(context: Context) =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Home offers the tip once: after Transmit first alerted to a text, while its alerts are on, outside the demo. */
    fun offerTip(messageAlerted: Boolean, tipSeen: Boolean, alertsOn: Boolean, paired: Boolean, demo: Boolean, installed: Boolean) =
        messageAlerted && !tipSeen && alertsOn && paired && !demo && installed

    /** The "Incoming messages" switch. */
    fun openChannel(context: Context) = open(
        context,
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, PACKAGE)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL),
    )

    /** All of Google Messages' notification settings: where the channel can be picked if its ID ever changes. */
    fun openApp(context: Context) = open(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, PACKAGE),
    )

    private fun open(context: Context, intent: Intent): Boolean = runCatching { context.startActivity(intent) }.isSuccess
}

/**
 * Opens the "Incoming messages" switch. If Settings comes straight back (Google Messages renamed the channel), opens
 * Google Messages' notification settings instead. Call it from the screen, not from [DoubleAlertsSheet], which is gone
 * by the time Settings returns.
 */
@Composable
fun rememberOpenGoogleMessagesAlerts(): () -> Unit {
    val context = LocalContext.current
    val openedAt = remember { longArrayOf(0L) }
    LifecycleResumeEffect(Unit) {
        val at = openedAt[0]
        openedAt[0] = 0L
        if (at != 0L && SystemClock.elapsedRealtime() - at < GoogleMessagesAlerts.BOUNCE_MS) GoogleMessagesAlerts.openApp(context)
        onPauseOrDispose {}
    }
    return {
        if (GoogleMessagesAlerts.openChannel(context)) openedAt[0] = SystemClock.elapsedRealtime()
        else GoogleMessagesAlerts.openApp(context)
    }
}

/** How to stop the double notifications: a looping clip of the switch, and [onOpen] to go to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubleAlertsSheet(onOpen: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.double_alerts_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.double_alerts_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            val clipDescription = stringResource(R.string.double_alerts_clip)
            LoopingClip(
                R.raw.gm_notifications_off,
                Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(540f / 600f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics { contentDescription = clipDescription },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.double_alerts_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onOpen(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.double_alerts_open))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_not_now)) }
            }
        }
    }
}

/** An animated WebP from res/raw, looping while shown. */
@Composable
private fun LoopingClip(res: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember(res) {
        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, res)) }.getOrNull()
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = modifier,
        update = { view ->
            view.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.let { it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; it.start() }
        },
        onRelease = { (drawable as? AnimatedImageDrawable)?.stop() },
    )
}
