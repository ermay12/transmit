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

    /**
     * Finds this Google account's subscription on Play and takes its key to this device ("Restore purchase").
     * [currentKeyHash] is the subscription key held now, if any: it is kept when it still belongs to the purchase.
     */
    suspend fun restore(currentKeyHash: String?): AccessResult

    /**
     * Re-checks a subscription the app already holds: daily, when the allowance nears its end, and when it's used
     * up. Picks up renewals, upgrades, lapses and refunds. The key is kept when [keyHash] still matches; when it
     * doesn't (the key moved to another device), this never takes it back: it fails with
     * [AccessErrorCode.KEY_IN_USE_ELSEWHERE] and changes nothing. Only [restore] takes the key.
     */
    suspend fun refresh(purchaseToken: String, keyHash: String?): AccessResult
}

/**
 * Reconciles the app's own spending tally with what OpenRouter has booked for the managed key
 * (`GET /api/v1/key`, `data.limit_remaining`). OpenRouter books spending about 3 minutes late, so the app counts
 * each response's `usage.cost` itself and only uses this to correct drift.
 */
fun interface AllowanceSource {
    /** What OpenRouter says about the managed key now. */
    suspend fun read(managedKey: String): AllowanceCheck
}

/** OpenRouter's view of the managed key. */
sealed interface AllowanceCheck {
    /**
     * [remainingUsd] as OpenRouter reported it, covering spending up to [at]. Because of OpenRouter's lag, [at] is a
     * few minutes before the reading was taken; calls the app logged after [at] are added on top. The key's limit in it
     * counts as seen at [at] too: OpenRouter can answer `/key` with a limit from before a change made a minute earlier
     * (seen on a device after a renewal), so a reading never overrules a newer answer from the Worker.
     */
    data class Reading(val remainingUsd: Double, val at: Long) : AllowanceCheck

    /** 403 "Key limit exceeded": this period's allowance is spent. */
    data object UsedUp : AllowanceCheck

    /**
     * 401: the key no longer works. For a trial, it has expired; for a subscription, it was rotated on another
     * device, or the Worker turned it off (lapse, refund). Told apart by the mode, never by OpenRouter's wording.
     */
    data object KeyRejected : AllowanceCheck

    /** No answer, or one that says nothing about the allowance (a service problem, never a paywall). */
    data object Unavailable : AllowanceCheck
}

/** A Play purchase the app learned about from `onPurchasesUpdated` or `queryPurchasesAsync`. */
data class PlayPurchase(val productId: String, val purchaseToken: String)

sealed interface Entitlement {
    data class Trial(val endsAt: Long) : Entitlement

    /**
     * [periodEndsAt] is when the paid period ends; with [autoRenewing], it renews (and the allowance resets) then.
     * [pendingSwitch]: a plan change set for that renewal (Plus → Transmit). Until then [tier] and the allowance are
     * still the current plan's.
     */
    data class Subscription(
        val tier: Tier,
        val periodEndsAt: Long?,
        val autoRenewing: Boolean = true,
        val pendingSwitch: PlanSwitch? = null,
    ) : Entitlement
}

/**
 * A plan change Play has set for the next renewal (`pendingProductId` and `pendingStartsAt` in `backend/API.md`):
 * from [startsAt] the plan is [tier]. Only a switch from Plus to Transmit makes one.
 */
data class PlanSwitch(val tier: Tier, val startsAt: Long)

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
    /** The Worker's `allowanceRenewed`: this call credited a new Play order (a purchase, an upgrade, a renewal) and raised the key's limit. */
    val allowanceRenewed: Boolean = false,
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
    /** The same call may succeed later (busy, Google or OpenRouter down, no connection): a purchase is sent again. */
    val retryable: Boolean = false,
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
    /** Too many calls from this network (`rate_limited`); try again after [AccessError.retryAfterMs]. */
    RATE_LIMITED,
    /** "Restore purchase" found no Transmit subscription on this Google account. */
    NO_PURCHASE,
    /** The purchase's payment hasn't gone through yet. */
    PAYMENT_PENDING,
    /** A renewal payment failed (on hold, paused): fix it in Google Play. */
    PAYMENT_PROBLEM,
    /** Another call for this device or purchase is running; retry in a few seconds. */
    BUSY,
    /** A background refresh found the subscription's key on another device (`key_mismatch`); nothing was changed. */
    KEY_IN_USE_ELSEWHERE,
    /** The Worker says the purchase token was `replaced`, and Play lists no newer one to send instead. */
    TOKEN_REPLACED,
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
            val retryable = error in RETRYABLE
            fun fail(code: AccessErrorCode) =
                AccessResult.Failed(AccessError(code, retry, listOfNotNull(error, reason, state).joinToString("/"), retryable))
            return when (error) {
                "trial_already_used" -> fail(TRIAL_ALREADY_USED)
                "trial_capacity_reached" -> fail(TRIALS_FULL)
                "integrity_failed" -> when (reason) {
                    "device_integrity" -> fail(DEVICE_NOT_SUPPORTED)
                    "app_not_recognized", "unlicensed" -> fail(NOT_FROM_PLAY)
                    else -> fail(SERVICE)
                }
                // Only an unknown token means "no purchase"; a product mix-up is the app's bug, not the user's.
                "purchase_invalid" -> if (reason == "token_unknown" || reason == null) fail(NO_PURCHASE) else fail(SERVICE)
                "purchase_not_active" -> when (state) {
                    "pending" -> fail(PAYMENT_PENDING)
                    "on_hold", "paused" -> fail(PAYMENT_PROBLEM)
                    "expired", "canceled_pending", "refunded" -> AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
                    // "replaced": the caller retries with the newer token; anything else is unexpected.
                    else -> fail(SERVICE)
                }
                "busy" -> fail(BUSY)
                "key_mismatch" -> fail(KEY_IN_USE_ELSEWHERE)
                "rate_limited" -> fail(RATE_LIMITED)
                else -> fail(SERVICE)
            }
        }

        /**
         * Worker errors worth sending the same request again for (`backend/API.md`). `rate_limited` only after its
         * `retryAfterSeconds`, never in a loop: the purchase job's backoff starts at 30 s and doubles.
         */
        val RETRYABLE = setOf("busy", "upstream_error", "internal_error", "rate_limited")
    }
}

/** Things to do outside the UI when access changes, such as posting a notification. */
interface AccessListener {
    /** Managed access ended while no screen of the app was showing: link to the plans screen. */
    fun onEndedInBackground(reason: EndReason) {}

    /** Another device took the subscription's key while no screen of the app was showing. */
    fun onInUseElsewhereInBackground() {}

    /** A trial key was stored (a new trial, or one re-issued after a reinstall): time the day-6 reminder. */
    fun onTrialGranted(endsAt: Long) {}

    /** A subscription key was stored: no trial reminder any more. */
    fun onSubscriptionGranted() {}
}

/** What each build type provides for AI access: see `AccessBuild` in the debug and release source sets. */
data class AccessServices(
    val backend: AccessBackend,
    val allowance: AllowanceSource,
    val store: Storefront,
    val attestation: TrialAttestation,
)
