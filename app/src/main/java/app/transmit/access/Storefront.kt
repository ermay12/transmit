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
     * Standard → Plus: replaces [oldPurchaseToken] and charges the full price now, so the whole larger allowance
     * applies at once (CHARGE_FULL_PRICE, set per item with [SubscriptionProductReplacementParams]).
     */
    data class Upgrade(
        val oldPurchaseToken: String,
        val oldProductId: String,
        val replacementMode: Int = SubscriptionProductReplacementParams.ReplacementMode.CHARGE_FULL_PRICE,
    ) : PurchasePlan

    /** The account already has this plan (renewing, canceled or on hold): Manage subscription is the way. */
    data object AlreadyOwned : PurchasePlan

    /** Plus → Standard isn't offered in 1.0. */
    data object NotOffered : PurchasePlan

    companion object {
        fun choose(target: Tier, owned: List<OwnedPurchase>): PurchasePlan {
            val current = owned.filter { it.purchased && it.tier != null }
            if (current.any { it.tier == target }) return AlreadyOwned
            val other = current.maxByOrNull { it.purchaseTime } ?: return New
            return when {
                other.tier == Tier.STANDARD && target == Tier.PLUS -> Upgrade(other.purchaseToken, other.productId)
                else -> NotOffered
            }
        }
    }
}
