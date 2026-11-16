package app.transmit.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

object Permissions {
    val runtime = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun missing(context: Context) = runtime.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun has(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun rememberPermissionsRequest(onResult: (Map<String, Boolean>) -> Unit): (Array<String>) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), onResult)
    return { perms -> launcher.launch(perms) }
}
