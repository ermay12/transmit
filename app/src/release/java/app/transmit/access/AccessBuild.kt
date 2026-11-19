package app.transmit.access

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.transmit.data.KeyVault
import kotlinx.coroutines.CoroutineScope

/**
 * Release builds: the Worker, Play Integrity and Play Billing. A copy not installed from Play (a GitHub APK) can't
 * start a trial or buy, and works in BYOK.
 */
object AccessBuild {
    @Suppress("UNUSED_PARAMETER")
    fun services(context: Context, vault: KeyVault, scope: CoroutineScope): AccessServices {
        val store = PlayBilling(context, available = PlayAccessBackend.installedFromPlay(context))
        val integrity = PlayIntegrity(context, scope)
        val backend = PlayAccessBackend(WorkerApi(), store, integrity, { PlayAccessBackend.androidId(context) })
        return AccessServices(backend, OpenRouterAllowance(), store, integrity)
    }

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun DebugPanel(access: AccessManager, modifier: Modifier = Modifier) = Unit
}
