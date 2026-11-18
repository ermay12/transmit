package app.transmit.access

/**
 * Transmit's backend and Google Play, as the app needs them: everything that hands out, renews or takes back a
 * managed key. The release implementation talks to the Worker in `backend/API.md` (Play Integrity for the trial,
 * Play Billing for purchases); debug builds can use a fake. Implementations don't touch app state: [AccessManager]
 * stores what they return.
 */
interface AccessBackend {
    /** Starts this device's free trial, or re-issues its key after a reinstall during the trial (`POST /v1/trial`). */
    suspend fun startTrial(): AccessResult

    /**
     * Verifies a new Play purchase (or an upgrade) and returns its key (`POST /v1/subscription`).
     * [currentKeyHash] is the subscription key held now, so an upgrade keeps it.
     */
    suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?): AccessResult

    /** Finds this Google account's subscription on Play and takes its key to this device ("Restore purchase"). */
    suspend fun restore(): AccessResult

    /**
     * Re-checks a subscription the app already holds: daily, when the allowance nears its end, and when it's used
     * up. Picks up renewals, upgrades, lapses and refunds. The key is kept when [keyHash] still matches.
     */
    suspend fun refresh(purchaseToken: String, keyHash: String?): AccessResult
}

/**
 * Reconciles the app's own spending tally with what OpenRouter has booked for the managed key
 * (`GET /api/v1/key`, `data.limit_remaining`). OpenRouter books spending about 3 minutes late, so the app counts
 * each response's `usage.cost` itself and only uses this to correct drift.
 */
fun interface AllowanceSource {
    /** What's left of the managed key's allowance as OpenRouter sees it, or null when it can't be read now. */
    suspend fun read(managedKey: String): AllowanceReading?
}

/**
 * [remainingUsd] as OpenRouter reported it, covering spending up to [at]. Because of OpenRouter's lag, set [at] a
 * few minutes before the reading was taken; calls the app logged after [at] are added on top.
 */
data class AllowanceReading(val remainingUsd: Double, val at: Long)

/** A Play purchase the app learned about from `onPurchasesUpdated` or `queryPurchasesAsync`. */
data class PlayPurchase(val productId: String, val purchaseToken: String)

sealed interface Entitlement {
    data class Trial(val endsAt: Long) : Entitlement

    /** [periodEndsAt] is when the paid period ends; with [autoRenewing], it renews (and the allowance resets) then. */
    data class Subscription(val tier: Tier, val periodEndsAt: Long?, val autoRenewing: Boolean = true) : Entitlement
}

/** A managed key and what it allows. */
data class Grant(
    val entitlement: Entitlement,
    /** A new OpenRouter key, or null when the backend kept the one the app already holds. */
    val key: String?,
    val keyHash: String?,
    /** AI allowance for the trial, or per billing period. Never shown in dollars. */
    val allowanceUsd: Double,
    /** What's left of it now. */
    val remainingUsd: Double,
    /** The subscription's Play purchase token, kept for [AccessBackend.refresh]. */
    val purchaseToken: String? = null,
)

sealed interface AccessResult {
    data class Granted(val grant: Grant) : AccessResult

    /** The backend says this subscription gives no AI any more (expired, refunded, canceled before paying). */
    data class Ended(val reason: EndReason) : AccessResult

    data class Failed(val error: AccessError) : AccessResult
}

data class AccessError(
    val code: AccessErrorCode,
    /** For [AccessErrorCode.TRIALS_FULL] and [AccessErrorCode.BUSY]: when to try again. */
    val retryAfterMs: Long? = null,
    /** For logs only, never shown. */
    val detail: String? = null,
)

/** What went wrong, in terms the app shows to the user. */
enum class AccessErrorCode {
    /** This device already had its trial (expired, used up, or it subscribed). */
    TRIAL_ALREADY_USED,
    /** Play Integrity says the device isn't certified (emulator, rooted, unlocked bootloader). */
    DEVICE_NOT_SUPPORTED,
    /** This copy wasn't installed from Google Play (GitHub APK, debug build), so it can't start a trial or buy. */
    NOT_FROM_PLAY,
    /** Today's free trials are taken; try tomorrow. */
    TRIALS_FULL,
    /** "Restore purchase" found no Transmit subscription on this Google account. */
    NO_PURCHASE,
    /** The purchase's payment hasn't gone through yet. */
    PAYMENT_PENDING,
    /** A renewal payment failed (on hold, paused): fix it in Google Play. */
    PAYMENT_PROBLEM,
    /** Another call for this device or purchase is running; retry in a few seconds. */
    BUSY,
    /** No connection to the backend. */
    NETWORK,
    /** The backend, Google or OpenRouter failed, or something unexpected came back. Try again later. */
    SERVICE,
    ;

    companion object {
        /**
         * Maps the Worker's machine-readable error (`error`, plus `reason` or `state` where it has one, see
         * `backend/API.md`) to what the app does. Unknown codes count as a service problem.
         */
        fun resultFor(error: String?, reason: String? = null, state: String? = null, retryAfterSeconds: Long? = null): AccessResult {
            val retry = retryAfterSeconds?.let { it * 1000 }
            fun fail(code: AccessErrorCode) = AccessResult.Failed(AccessError(code, retry, listOfNotNull(error, reason, state).joinToString("/")))
            return when (error) {
                "trial_already_used" -> fail(TRIAL_ALREADY_USED)
                "trial_capacity_reached" -> fail(TRIALS_FULL)
                "integrity_failed" -> when (reason) {
                    "device_integrity" -> fail(DEVICE_NOT_SUPPORTED)
                    "app_not_recognized", "unlicensed" -> fail(NOT_FROM_PLAY)
                    else -> fail(SERVICE)
                }
                "purchase_invalid" -> fail(NO_PURCHASE)
                "purchase_not_active" -> when (state) {
                    "pending" -> fail(PAYMENT_PENDING)
                    "on_hold", "paused" -> fail(PAYMENT_PROBLEM)
                    "expired", "canceled_pending", "refunded" -> AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
                    // "replaced": the caller retries with the newer token; anything else is unexpected.
                    else -> fail(SERVICE)
                }
                "busy" -> fail(BUSY)
                else -> fail(SERVICE)
            }
        }
    }
}

/** Things to do outside the UI when access changes, such as posting a notification. */
interface AccessListener {
    /** Managed access ended while no screen of the app was showing: link to the plans screen. */
    fun onEndedInBackground(reason: EndReason) {}

    /** OpenRouter refused the subscription key (rotated on another device, or turned off). */
    fun onSubscriptionKeyRejected() {}
}
