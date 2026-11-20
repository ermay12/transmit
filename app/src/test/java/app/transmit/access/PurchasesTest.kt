package app.transmit.access

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.transmit.data.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import app.transmit.TestStrings
import app.transmit.ui.access.AccessText

/** Getting each Play purchase to the Worker: what is queued, retried, dropped, and when the AI starts. */
class PurchasesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val vault = object : KeyVault {
        val map = HashMap<String, String>()
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String?) {
            if (value.isNullOrBlank()) map.remove(name) else map[name] = value
        }
    }
    private val backend = DeliveryBackend()
    private val store = UpdatesStore()
    private var retriesScheduled = 0
    private val flowsReturned = java.util.concurrent.atomic.AtomicInteger()
    /** For each sheet that returned: whether its purchase went to the Worker. */
    private val sentFlags = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    private lateinit var access: AccessManager
    private lateinit var queue: PurchaseQueue
    private lateinit var purchases: Purchases

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = java.io.File(tmp.root, "access.preferences_pb")
        access = AccessManager(
            AccessStore(PreferenceDataStoreFactory.create(scope = scope) { file }) { false },
            vault, backend, { AllowanceCheck.Unavailable }, { kotlinx.coroutines.flow.flowOf(0.0) }, scope,
        )
        queue = PurchaseQueue(vault)
        purchases = Purchases(store, access, queue, scope, onFlowReturned = { sent -> sentFlags += sent; flowsReturned.incrementAndGet() }) { retriesScheduled++ }
    }

    @After fun tearDown() { scope.cancel() }

    /** Play's update, once [Purchases] is listening (a SharedFlow drops what nobody hears). */
    private suspend fun emit(u: PurchaseUpdate) {
        withTimeout(5_000) { store.updates.subscriptionCount.first { it > 0 } }
        store.updates.emit(u)
    }

    private fun bought(token: String, product: String = "transmit_monthly", acknowledged: Boolean = false) =
        OwnedPurchase(product, token, purchased = true, acknowledged = acknowledged)

    private fun grant(token: String) = Grant(
        Entitlement.Subscription(Tier.STANDARD, System.currentTimeMillis() + 30 * 86_400_000L), "sk-sub", "sh", 4.5, 4.5, purchaseToken = token,
    )

    @Test
    fun aPurchaseIsSentAtOnceAndTheAiStarts() = runBlocking {
        backend.results["t1"] = ArrayDeque(listOf(AccessResult.Granted(grant("t1"))))
        emit(PurchaseUpdate.Purchases(listOf(bought("t1"))))
        assertEquals(PurchaseUi.Done(Tier.STANDARD), withTimeout(5_000) { purchases.state.first { it is PurchaseUi.Done } })
        assertEquals(listOf("t1"), backend.sent)
        assertTrue(queue.all().isEmpty())
        assertEquals(0, retriesScheduled)
        assertEquals("sk-sub", access.managedKey())
    }

    @Test
    fun aFailureThatCanPassIsKeptAndRetriedLater() = runBlocking {
        val upstream = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE, detail = "upstream_error", retryable = true))
        backend.results["t1"] = ArrayDeque(listOf(upstream, AccessResult.Granted(grant("t1"))))
        emit(PurchaseUpdate.Purchases(listOf(bought("t1"))))
        withTimeout(5_000) { purchases.state.first { it is PurchaseUi.Failed } }
        assertEquals(listOf("t1"), queue.all().map { it.purchaseToken })
        assertEquals(1, retriesScheduled)
        assertTrue(purchases.hasQueued())

        // The background job (or the next launch) sends it again.
        assertNull(purchases.deliver())
        assertFalse(purchases.hasQueued())
        assertEquals(listOf("t1", "t1"), backend.sent)
    }

    @Test
    fun aTokenTheWorkerWillNeverAcceptIsDropped() = runBlocking {
        backend.results["bad"] = ArrayDeque(listOf(AccessResult.Failed(AccessError(AccessErrorCode.NO_PURCHASE, detail = "purchase_invalid/token_unknown"))))
        queue.add(PlayPurchase("transmit_monthly", "bad"))
        assertEquals(AccessErrorCode.NO_PURCHASE, purchases.deliver()?.code)
        assertTrue(queue.all().isEmpty())
        assertEquals(0, retriesScheduled)
    }

    @Test
    fun aPendingPaymentWaitsForPlay() = runBlocking {
        emit(PurchaseUpdate.Purchases(listOf(OwnedPurchase("transmit_monthly", "p", purchased = false, acknowledged = false))))
        withTimeout(5_000) { purchases.state.first { it == PurchaseUi.Pending } }
        assertTrue(queue.all().isEmpty())
        assertTrue(backend.sent.isEmpty())
    }

    @Test
    fun eachLaunchQueuesPurchasesTheWorkerHasntAcknowledged() = runBlocking {
        store.owned = listOf(
            bought("new"),
            bought("done", acknowledged = true),
            OwnedPurchase("transmit_plus", "pending", purchased = false, acknowledged = false),
            OwnedPurchase("some_other_product", "other", purchased = true, acknowledged = false),
        )
        val offline = AccessResult.Failed(AccessError(AccessErrorCode.NETWORK, retryable = true))
        backend.results["new"] = ArrayDeque(listOf(offline, offline))
        assertEquals(AccessErrorCode.NETWORK, purchases.sync()?.code)
        assertEquals(listOf("new"), backend.sent)
        assertEquals(listOf("new"), queue.all().map { it.purchaseToken })
        // Queued once, however often it is found.
        purchases.sync()
        assertEquals(1, queue.all().size)
    }

    /**
     * Every way Play's sheet can come back is reported once what it returned has been handled. Only a purchase that
     * went to the Worker needs no refresh after it: the Worker's answer is the plan.
     */
    @Test
    fun everyPurchaseFlowAsksForARefreshUnlessItsPurchaseWentToTheWorker() = runBlocking {
        suspend fun returned(n: Int) = withTimeout(5_000) { while (flowsReturned.get() < n) kotlinx.coroutines.delay(10) }

        // Bought: reported once the Worker has answered, as sent.
        backend.results["t1"] = ArrayDeque(listOf(AccessResult.Granted(grant("t1"))))
        emit(PurchaseUpdate.Purchases(listOf(bought("t1"))))
        returned(1)
        assertEquals(PurchaseUi.Done(Tier.STANDARD), purchases.state.value)
        assertEquals(listOf(true), sentFlags.toList())

        emit(PurchaseUpdate.Canceled)
        returned(2)
        // Refused by Play (the device test's Keep Plus): the plan may still have changed.
        emit(PurchaseUpdate.Failed(5, "Requested replacement mode is not supported for this request"))
        returned(3)
        assertTrue(purchases.state.value is PurchaseUi.Failed)
        emit(PurchaseUpdate.AlreadyOwned)
        returned(4)
        emit(PurchaseUpdate.Purchases(listOf(OwnedPurchase("transmit_monthly", "p", purchased = false, acknowledged = false))))
        returned(5)

        // The sheet didn't open: nothing to buy may mean the app's view is stale.
        store.launchResult = LaunchResult.UNAVAILABLE
        assertEquals(LaunchResult.UNAVAILABLE, purchases.buy(Activity(), Tier.PLUS))
        assertEquals(6, flowsReturned.get())
        assertEquals(listOf(true, false, false, false, false, false), sentFlags.toList())
    }

    /** Play still lists a purchase as unacknowledged for a while after the Worker acknowledged it: it isn't sent again. */
    @Test
    fun aPurchaseTheWorkerAnsweredForIsntSentAgainWhilePlayCatchesUp() = runBlocking {
        backend.results["t1"] = ArrayDeque(listOf(AccessResult.Granted(grant("t1"))))
        store.owned = listOf(bought("t1"))
        emit(PurchaseUpdate.Purchases(listOf(bought("t1"))))
        withTimeout(5_000) { purchases.state.first { it is PurchaseUi.Done } }
        assertNull(purchases.sync())
        assertNull(purchases.syncNew())
        assertEquals(listOf("t1"), backend.sent)
    }

    /** A declined card on a first purchase says nothing was charged; an upgrade or a switch, that the change didn't happen. */
    @Test
    fun aRefusalFromPlaySaysWhetherAPurchaseOrAChangeDidntHappen() = runBlocking {
        suspend fun refused(tier: Tier): PurchaseUi.Failed {
            assertEquals(LaunchResult.LAUNCHED, purchases.buy(Activity(), tier))
            emit(PurchaseUpdate.Failed(BILLING_ERROR, "Declined by always denied test instrument"))
            val failed = withTimeout(5_000) { purchases.state.first { it is PurchaseUi.Failed } } as PurchaseUi.Failed
            purchases.resetState()
            return failed
        }
        val declined = "Your purchase didn't go through. You haven't been charged."
        val change = "Google Play couldn't make that change. Please try again later."

        // A first subscription (the device test's ENDED → Subscribe with a declining card).
        val first = refused(Tier.STANDARD)
        assertEquals(PurchaseUi.Failed(null, planChange = false), first)
        assertEquals(declined, TestStrings.text(AccessText.purchaseProblem(first)))
        assertEquals(declined, TestStrings.text(AccessText.launchProblem(LaunchResult.FAILED)!!))

        // Transmit → Plus.
        access.applyGrant(grant("std-1"), activate = true)
        store.owned = listOf(bought("std-1", acknowledged = true))
        val upgrade = refused(Tier.PLUS)
        assertEquals(PurchaseUi.Failed(null, planChange = true), upgrade)
        assertEquals(change, TestStrings.text(AccessText.purchaseProblem(upgrade)))
        assertEquals(change, TestStrings.text(AccessText.launchProblem(LaunchResult.FAILED, planChange = true)!!))
        // The Worker's own failure on a new purchase keeps its wording (it may yet be charged, then refunded).
        assertEquals("The purchase didn't go through.", TestStrings.text(AccessText.purchaseProblem(PurchaseUi.Failed(AccessError(AccessErrorCode.SERVICE)))))
    }

    /** A refresh sends what Play has that isn't queued yet, and leaves a token that just failed to its retry job. */
    @Test
    fun aRefreshSendsOnlyPurchasesNotAlreadyWaiting() = runBlocking {
        val offline = AccessResult.Failed(AccessError(AccessErrorCode.NETWORK, retryable = true))
        backend.results["waiting"] = ArrayDeque(listOf(offline))
        queue.add(PlayPurchase("transmit_monthly", "waiting"))
        purchases.deliver()
        assertEquals(listOf("waiting"), backend.sent)

        store.owned = listOf(bought("waiting"), bought("outside", product = "transmit_plus"), bought("done", acknowledged = true))
        backend.results["outside"] = ArrayDeque(listOf(AccessResult.Granted(grant("outside"))))
        assertNull(purchases.syncNew())
        assertEquals(listOf("waiting", "outside"), backend.sent)
        assertEquals(listOf("waiting"), queue.all().map { it.purchaseToken })
        // The Worker acknowledged it; nothing new now, so no Worker call at all.
        store.owned = listOf(bought("waiting"), bought("outside", product = "transmit_plus", acknowledged = true))
        assertNull(purchases.syncNew())
        assertEquals(2, backend.sent.size)
    }

    @Test
    fun theQueueSurvivesARestart() {
        queue.add(PlayPurchase("transmit_plus", "a"))
        queue.add(PlayPurchase("transmit_monthly", "b"))
        assertEquals(listOf(PlayPurchase("transmit_plus", "a"), PlayPurchase("transmit_monthly", "b")), PurchaseQueue(vault).all())
        PurchaseQueue(vault).remove("a")
        assertEquals(listOf("b"), queue.all().map { it.purchaseToken })
    }

    @Test
    fun theDay6ReminderIs24HoursBeforeTheEnd() {
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        assertEquals(6 * day, AccessWork.reminderDelay(now + 7 * day, now))
        assertNull(AccessWork.reminderDelay(now + 12 * 3_600_000L, now))
        assertTrue(AccessWork.reminderDue(AiAccess.Trial(now + day), now))
        assertFalse(AccessWork.reminderDue(AiAccess.Trial(now + 3 * day), now))
        assertFalse(AccessWork.reminderDue(AiAccess.Subscribed(Tier.STANDARD, null), now))
        assertFalse(AccessWork.reminderDue(AiAccess.Trial(now - 1), now))
    }

    private companion object {
        /** BillingResponseCode.ERROR, which a declined test card returns. */
        const val BILLING_ERROR = 6
    }

    private class DeliveryBackend : AccessBackend {
        val results = HashMap<String, ArrayDeque<AccessResult>>()
        val sent = mutableListOf<String>()
        override suspend fun startTrial() = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        override suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?): AccessResult {
            sent += purchase.purchaseToken
            return results[purchase.purchaseToken]?.removeFirstOrNull() ?: AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        }
        override suspend fun restore(currentKeyHash: String?) = AccessResult.Failed(AccessError(AccessErrorCode.NO_PURCHASE))
        override suspend fun refresh(purchaseToken: String, keyHash: String?) = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
    }

    private class UpdatesStore : Storefront {
        override val available = true
        override val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 4)
        var owned: List<OwnedPurchase> = emptyList()
        var launchResult = LaunchResult.LAUNCHED
        override suspend fun offers(): Map<Tier, PlanOffer>? = null
        override suspend fun purchases() = owned
        override suspend fun launch(activity: Activity, tier: Tier, plan: PurchasePlan) = launchResult
    }
}
