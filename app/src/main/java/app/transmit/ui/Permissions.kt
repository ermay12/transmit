package app.transmit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import app.transmit.notify.Notifier

object Permissions {
    /** Notifications only became a runtime permission in Android 13; before that they're on unless switched off. */
    val runtime = listOfNotNull(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
    ).toTypedArray()

    fun missing(context: Context) = runtime.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun has(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Whether a new message can alert the user: notifications allowed, and the Messages channel not switched off. */
    fun messageAlertsOn(context: Context): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return false
        return nm.getNotificationChannelCompat(Notifier.CHANNEL_MESSAGES)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
    }
}

@Composable
fun rememberPermissionsRequest(onResult: (Map<String, Boolean>) -> Unit): (Array<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), onResult)
    return { perms -> launcher.launch(perms) }
}

/**
 * Turns message alerts back on: Android's permission prompt while it will still show one, otherwise
 * the settings page with the switch that's off. [onDone] runs after the prompt answers.
 */
@Composable
fun rememberTurnOnMessageAlerts(onDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val done by rememberUpdatedState(onDone)
    // Outlives the recompositions while the prompt is up, so the answer is read against the right start.
    val rationaleBefore = remember { booleanArrayOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // No rationale before or after a refusal: Android refused without asking, so only settings can turn it on.
        val rationaleAfter = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
        if (!granted && !rationaleBefore[0] && !rationaleAfter) openNotificationSettings(context)
        done()
    }
    return {
        val nm = NotificationManagerCompat.from(context)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.has(context, Manifest.permission.POST_NOTIFICATIONS) -> {
                rationaleBefore[0] = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !nm.areNotificationsEnabled() -> openNotificationSettings(context)
            else -> openNotificationSettings(context, Notifier.CHANNEL_MESSAGES)
        }
    }
}

/** The app's notification settings, or one channel's when [channel] is given. */
private fun openNotificationSettings(context: Context, channel: String? = null) {
    val intent = if (channel != null) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    }
    runCatching { context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
}
