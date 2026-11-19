package app.transmit.access

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Play Billing 9 with suspend wrappers (the -ktx artifact needs a newer Kotlin). Connects on first use and
 * reconnects when Play drops the connection.
 */
class PlayBilling(context: Context, override val available: Boolean) : Storefront, PurchasesUpdatedListener {
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()
    private val connecting = Mutex()
    private val _updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 16)
    override val updates: SharedFlow<PurchaseUpdate> = _updates

    /** The product and base-plan offer to launch for each tier, from the last [offers]. */
    @Volatile private var details: Map<Tier, Pair<ProductDetails, String>> = emptyMap()

    /** A copy not installed from Play can't buy, so it doesn't talk to Billing at all. */
    private suspend fun connected(): Boolean = connecting.withLock {
        if (!available) return false
        if (client.isReady) return true
        repeat(3) { attempt ->
            val result = suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (done.compareAndSet(false, true)) cont.resume(result)
                    }

                    override fun onBillingServiceDisconnected() {
                        // Automatic reconnection handles later calls; a first attempt that drops is retried below.
                        if (done.compareAndSet(false, true)) cont.resume(BillingResult.newBuilder().setResponseCode(BillingResponseCode.SERVICE_DISCONNECTED).build())
                    }
                })
            }
            when (result.responseCode) {
                BillingResponseCode.OK -> return true
                BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.FEATURE_NOT_SUPPORTED -> {
                    Log.w(TAG, "Billing unavailable: ${result.debugMessage}")
                    return false
                }
                else -> delay(500L shl attempt)
            }
        }
        false
    }

    override suspend fun offers(): Map<Tier, PlanOffer>? {
        if (!connected()) return null
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            Tier.entries.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it.productId).setProductType(ProductType.SUBS).build()
            },
        ).build()
        val (result, found) = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { r, d -> cont.resume(r to d.productDetailsList) }
        }
        if (result.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Product details failed: ${result.responseCode} ${result.debugMessage}")
            return null
        }
        val picked = found.mapNotNull { pd ->
            val tier = Tier.fromProductId(pd.productId) ?: return@mapNotNull null
            // The base plan itself (no offer id); Transmit has no trial or intro offers on Play.
            val offer = pd.subscriptionOfferDetails.orEmpty().firstOrNull { it.basePlanId == BASE_PLAN && it.offerId == null }
                ?: return@mapNotNull null
            val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return@mapNotNull null
            tier to Triple(pd, offer.offerToken, price)
        }.toMap()
        details = picked.mapValues { (_, v) -> v.first to v.second }
        return picked.mapValues { (tier, v) -> PlanOffer(tier, v.third) }
    }

    override suspend fun purchases(): List<OwnedPurchase>? {
        if (!connected()) return null
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).includeSuspendedSubscriptions(true).build()
        val (result, list) = suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { r, p -> cont.resume(r to p) }
        }
        if (result.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Purchases query failed: ${result.responseCode} ${result.debugMessage}")
            return null
        }
        return list.flatMap { it.owned() }
    }

    override suspend fun launch(activity: Activity, tier: Tier, plan: PurchasePlan): LaunchResult {
        if (plan == PurchasePlan.AlreadyOwned) return LaunchResult.ALREADY_OWNED
        if (plan == PurchasePlan.NotOffered || !connected()) return LaunchResult.UNAVAILABLE
        val (product, offerToken) = details[tier] ?: run { offers(); details[tier] } ?: return LaunchResult.UNAVAILABLE
        val item = ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offerToken)
        val flow = BillingFlowParams.newBuilder()
        if (plan is PurchasePlan.Upgrade) {
            item.setSubscriptionProductReplacementParams(
                SubscriptionProductReplacementParams.newBuilder()
                    .setOldProductId(plan.oldProductId)
                    .setReplacementMode(plan.replacementMode)
                    .build(),
            )
            flow.setSubscriptionUpdateParams(SubscriptionUpdateParams.newBuilder().setOldPurchaseToken(plan.oldPurchaseToken).build())
        }
        flow.setProductDetailsParamsList(listOf(item.build()))
        val result = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, flow.build()) }
        return when (result.responseCode) {
            BillingResponseCode.OK -> LaunchResult.LAUNCHED
            BillingResponseCode.ITEM_ALREADY_OWNED -> LaunchResult.ALREADY_OWNED
            BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.ITEM_UNAVAILABLE -> LaunchResult.UNAVAILABLE
            else -> {
                Log.w(TAG, "Launch failed: ${result.responseCode} ${result.debugMessage}")
                LaunchResult.FAILED
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val update = when (result.responseCode) {
            BillingResponseCode.OK -> PurchaseUpdate.Purchases(purchases.orEmpty().flatMap { it.owned() })
            BillingResponseCode.USER_CANCELED -> PurchaseUpdate.Canceled
            BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseUpdate.AlreadyOwned
            else -> PurchaseUpdate.Failed(result.responseCode, result.debugMessage)
        }
        _updates.tryEmit(update)
    }

    private fun Purchase.owned(): List<OwnedPurchase> = products.filter { Tier.fromProductId(it) != null }.map { id ->
        OwnedPurchase(
            productId = id,
            purchaseToken = purchaseToken,
            purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
            acknowledged = isAcknowledged,
            suspended = isSuspended,
            autoRenewing = isAutoRenewing,
            purchaseTime = purchaseTime,
        )
    }

    companion object {
        private const val TAG = "Billing"
        /** Both subscriptions have one auto-renewing monthly base plan with this id. */
        const val BASE_PLAN = "monthly"
    }
}
