package app.transmit.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

object Permissions {
    val runtime = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
    )

    fun missing(context: Context) = runtime.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun has(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** Returns a function that shows the system "make Transmit your SMS app" dialog. */
@Composable
fun rememberDefaultSmsRequest(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onResult(context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
    }
    return {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else {
            onResult(rm.isRoleHeld(RoleManager.ROLE_SMS))
        }
    }
}

@Composable
fun rememberPermissionsRequest(onResult: (Map<String, Boolean>) -> Unit): (Array<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), onResult)
    return { perms -> launcher.launch(perms) }
}
