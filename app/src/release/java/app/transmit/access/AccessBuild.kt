package app.transmit.access

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.transmit.data.KeyVault

/**
 * Release builds: the backend and Play. Until the Worker client and Play Billing are built, nothing can start a
 * trial or buy, so release builds work in BYOK only.
 */
object AccessBuild {
    fun backend(context: Context, vault: KeyVault): Pair<AccessBackend, AllowanceSource> =
        UnavailableBackend to AllowanceSource { null }

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun DebugPanel(access: AccessManager, modifier: Modifier = Modifier) = Unit
}

private object UnavailableBackend : AccessBackend {
    private val unavailable = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE, detail = "Not available in this build"))
    override suspend fun startTrial() = unavailable
    override suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?) = unavailable
    override suspend fun restore() = unavailable
    override suspend fun refresh(purchaseToken: String, keyHash: String?) = unavailable
}
