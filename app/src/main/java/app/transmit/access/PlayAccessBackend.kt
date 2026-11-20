package app.transmit.access

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The real backend: the Worker (`backend/API.md`), with Play Integrity for the trial and Play Billing for purchases.
 * [checkInstall] is off only in debug builds that talk to the live Worker, so its answers can be seen.
 */
class PlayAccessBackend(
    private val api: WorkerApi,
    private val store: Storefront,
    private val attestation: TrialAttestation,
    private val androidId: () -> String?,
    private val checkInstall: Boolean = true,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : AccessBackend {

    /** One `/v1/trial` at a time: a second call for the same device is `busy`, and its retry would replace the key. */
    private val trialLock = Mutex()

    override suspend fun startTrial(): AccessResult = trialLock.withLock {
        if (checkInstall && !store.available) return fail(AccessErrorCode.NOT_FROM_PLAY, "Not installed from Play")
        val id = androidId()?.takeIf { it.isNotBlank() } ?: return fail(AccessErrorCode.SERVICE, "No ANDROID_ID")
        val hash = TrialAttestation.requestHash(id)
        var staleRetries = 1
        var busyRetries = BUSY_RETRIES
        var reply: WorkerReply
        do {
            // Every attempt gets a fresh token: the Worker rejects tokens over 10 minutes old.
            val token = try {
                attestation.token(hash)
            } catch (e: AttestationException) {
                return AccessResult.Failed(e.error)
            }
            reply = api.trial(id, token)
            val e = reply as? WorkerReply.Error
            val again = when {
                // A token that went stale on the way gets one fresh try; twice means the phone's clock is off.
                e?.error == "integrity_failed" && e.reason == "token_expired" -> staleRetries-- > 0
                // Another call for this device is running (API.md "Concurrent calls"): wait, then ask with a new token.
                e?.error == "busy" -> (busyRetries-- > 0).also { if (it) pause(BUSY_WAIT_MS) }
                else -> false
            }
        } while (again)
        reply.toResult(WorkerApi::trialGrant)
    }

    override suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?): AccessResult =
        subscription(purchase.purchaseToken, purchase.productId, currentKeyHash, androidId())

    // A call the user didn't ask for never takes the key from another device (API.md `rotate`).
    override suspend fun refresh(purchaseToken: String, keyHash: String?): AccessResult =
        subscription(purchaseToken, null, keyHash, null, rotate = false)

    override suspend fun restore(currentKeyHash: String?): AccessResult {
        if (checkInstall && !store.available) return fail(AccessErrorCode.NOT_FROM_PLAY, "Not installed from Play")
        val owned = store.purchases() ?: return fail(AccessErrorCode.SERVICE, "Play purchases unavailable", retryable = true)
        val purchase = restorable(owned) ?: return fail(AccessErrorCode.NO_PURCHASE, "No Transmit purchase on Play")
        return subscription(purchase.purchaseToken, purchase.productId, currentKeyHash, null)
    }

    /**
     * `POST /v1/subscription`. After an upgrade the old token answers `replaced`: Play's newer token for the
     * subscription is sent instead (once), and kept from then on.
     */
    private suspend fun subscription(
        token: String,
        productId: String?,
        keyHash: String?,
        androidId: String?,
        rotate: Boolean = true,
    ): AccessResult {
        var t = token
        var product = productId
        repeat(2) {
            val reply = retryingBusy { api.subscription(t, product, keyHash, androidId, rotate) }
            if (reply.delivery != Delivery.USE_NEWER_TOKEN) return reply.toResult { WorkerApi.subscriptionGrant(it, t) }
            val owned = store.purchases() ?: return fail(AccessErrorCode.SERVICE, "Replaced, and Play can't be asked", retryable = true)
            val newer = newerThan(t, owned) ?: return fail(AccessErrorCode.TOKEN_REPLACED, "Replaced, and Play has no newer purchase")
            Log.i(TAG, "Purchase was replaced; sending the newer one")
            t = newer.purchaseToken
            product = newer.productId
        }
        return fail(AccessErrorCode.SERVICE, "Replaced twice")
    }

    /** `busy`: another call for this device or purchase is running; API.md says to try again in 2–5 s. */
    private suspend fun retryingBusy(call: suspend () -> WorkerReply): WorkerReply {
        var reply = call()
        repeat(BUSY_RETRIES) {
            if (!(reply is WorkerReply.Error && (reply as WorkerReply.Error).error == "busy")) return reply
            pause(BUSY_WAIT_MS)
            reply = call()
        }
        return reply
    }

    private fun fail(code: AccessErrorCode, detail: String, retryable: Boolean = false) =
        AccessResult.Failed(AccessError(code, detail = detail, retryable = retryable))

    companion object {
        private const val TAG = "PlayAccess"
        private const val BUSY_RETRIES = 2
        private const val BUSY_WAIT_MS = 3_000L

        /** The purchase to restore: paid up before on hold, Plus before Standard, then the newest. Pending comes last. */
        fun restorable(owned: List<OwnedPurchase>): OwnedPurchase? = owned.filter { it.tier != null }.maxWithOrNull(
            compareBy<OwnedPurchase>({ it.purchased }, { !it.suspended }, { it.tier == Tier.PLUS }, { it.purchaseTime }),
        )

        /** Play's purchase that superseded [token]: another Transmit subscription token, the newest first. */
        fun newerThan(token: String, owned: List<OwnedPurchase>): OwnedPurchase? =
            owned.filter { it.tier != null && it.purchaseToken != token && it.purchased }.maxByOrNull { it.purchaseTime }

        /** ANDROID_ID, as the Worker binds the trial to it. */
        @SuppressLint("HardwareIds")
        fun androidId(context: Context): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        /** Installed by the Play Store: only then can this copy start a trial or buy. */
        fun installedFromPlay(context: Context): Boolean = runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName == "com.android.vending"
        }.getOrDefault(false)
    }
}
