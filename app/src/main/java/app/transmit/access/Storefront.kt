package app.transmit.access

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import kotlinx.coroutines.flow.Flow

/**
 * Google Play as the app sells through it: the two plans, the purchase sheet, and what this Google account owns.
 * Real builds use [PlayBilling]; debug builds can use a fake. Purchases are never acknowledged here: the Worker does
 * that once it has issued the key.
 */
interface Storefront {
    /** This copy was installed from Google Play, so it can buy (and start a trial). */
    val available: Boolean

    /** Results of purchase sheets, as Play reports them in `onPurchasesUpdated`. */
    val updates: Flow<PurchaseUpdate>

    /** The plans with their localized prices, or null when Play can't be reached. */
    suspend fun offers(): Map<Tier, PlanOffer>?

    /** Transmit subscriptions on this Google account, including ones on hold; null when Play can't be asked. */
    suspend fun purchases(): List<OwnedPurchase>?

    /** Opens Play's purchase sheet. The outcome arrives in [updates]. */
    suspend fun launch(activity: Activity, tier: Tier, plan: PurchasePlan): LaunchResult
}

/** A plan as Play sells it: [price] is formatted for the user's country, e.g. "$5.99". */
data class PlanOffer(val tier: Tier, val price: String)

data class OwnedPurchase(
    val productId: String,
    val purchaseToken: String,
    /** PURCHASED rather than PENDING. */
    val purchased: Boolean,
    val acknowledged: Boolean,
    /** On hold or paused: no AI until the payment is fixed in Play. */
    val suspended: Boolean = false,
    val autoRenewing: Boolean = true,
    val purchaseTime: Long = 0,
) {
    val tier: Tier? get() = Tier.fromProductId(productId)

    /** Paid up, so it gives AI. */
    val active get() = purchased && !suspended
}

sealed interface PurchaseUpdate {
    data class Purchases(val purchases: List<OwnedPurchase>) : PurchaseUpdate
    data object Canceled : PurchaseUpdate
    data object AlreadyOwned : PurchaseUpdate
    data class Failed(val responseCode: Int, val message: String?) : PurchaseUpdate
}

enum class LaunchResult { LAUNCHED, UNAVAILABLE, ALREADY_OWNED, FAILED }

/** What buying a plan means, given what the account owns. */
sealed interface PurchasePlan {
    /** A first subscription (or a new one after the last ended). */
    data object New : PurchasePlan

    /**
     * Replaces the subscription item [oldProductId] on [oldPurchaseToken], with the item-level
     * [SubscriptionProductReplacementParams] (the purchase-level replacement mode is deprecated in Billing 8.1+).
     * Play issues a new purchase token for it at once.
     */
    sealed interface Change : PurchasePlan {
        val oldPurchaseToken: String
        val oldProductId: String
        val replacementMode: Int
    }

    /**
     * Standard → Plus: replaces [oldPurchaseToken] and charges the full price now, so the whole larger allowance
     * applies at once (CHARGE_FULL_PRICE).
     */
    data class Upgrade(
        override val oldPurchaseToken: String,
        override val oldProductId: String,
        override val replacementMode: Int = SubscriptionProductReplacementParams.ReplacementMode.CHARGE_FULL_PRICE,
    ) : Change

    /**
     * Plus → Transmit ("Switch to Transmit"): DEFERRED, so the user keeps Plus and its allowance until the next
     * renewal, then renews as Transmit. Nothing is charged now.
     */
    data class Downgrade(
        override val oldPurchaseToken: String,
        override val oldProductId: String,
        override val replacementMode: Int = SubscriptionProductReplacementParams.ReplacementMode.DEFERRED,
    ) : Change

    /**
     * "Keep Plus": undoes a switch to Transmit that hasn't happened yet. Google names no cancel call, only "revert to
     * the original plan" with another plan change, so this replaces the switch's token with Plus again. DEFERRED, as
     * `backend/API.md` says to try first, since it charges nothing now (CHARGE_FULL_PRICE would bill a new month).
     * [oldProductId] is the product Play lists on that token, which stays Plus until the switch takes effect.
     */
    data class KeepPlus(
        override val oldPurchaseToken: String,
        override val oldProductId: String,
        override val replacementMode: Int = SubscriptionProductReplacementParams.ReplacementMode.DEFERRED,
    ) : Change

    /** The account already has this plan (renewing, canceled or on hold): Manage subscription is the way. */
    data object AlreadyOwned : PurchasePlan

    /** Nothing to buy for this choice: a switch to Transmit that's already set, or Play can't say what's owned. */
    data object NotOffered : PurchasePlan

    companion object {
        /**
         * [switchingTo] is the plan the Worker says is set for the next renewal, and [heldToken] the purchase token
         * the app holds the key for. Play alone can't tell a switch is set: the switch's token still lists Plus.
         */
        fun choose(target: Tier, owned: List<OwnedPurchase>, switchingTo: Tier? = null, heldToken: String? = null): PurchasePlan {
            val current = owned.filter { it.purchased && it.tier != null }
            // The subscription the app holds: Play's purchase for its token (as Plus, if Play lists both), else the newest.
            val held = current.filter { it.purchaseToken == heldToken }.maxByOrNull { it.tier == Tier.PLUS }
                ?: current.maxByOrNull { it.purchaseTime }
            if (switchingTo != null) {
                held ?: return NotOffered
                return if (target == Tier.PLUS && switchingTo == Tier.STANDARD) KeepPlus(held.purchaseToken, held.productId) else NotOffered
            }
            if (current.any { it.tier == target }) return AlreadyOwned
            held ?: return New
            return when {
                held.tier == Tier.STANDARD && target == Tier.PLUS -> Upgrade(held.purchaseToken, held.productId)
                held.tier == Tier.PLUS && target == Tier.STANDARD -> Downgrade(held.purchaseToken, held.productId)
                else -> NotOffered
            }
        }
    }
}
