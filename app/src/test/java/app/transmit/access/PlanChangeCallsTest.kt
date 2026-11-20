package app.transmit.access

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.transmit.data.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * How many `POST /v1/subscription` calls a purchase, an upgrade or a switch makes, with the app around it as on a
 * device: the Plans screen resumes and the app comes to the foreground while the new token is on its way, Play keeps
 * listing the new purchase as unacknowledged, and Done goes back to Settings. The beta5 device test counted 4–5 per
 * change (a `replaced` 402 and a `busy` 409 among them); one is all the flow needs.
 */
class PlanChangeCallsTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var server: MockWebServer
    private val vault = object : KeyVault {
        val map = HashMap<String, String>()
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String?) {
            if (value.isNullOrBlank()) map.remove(name) else map[name] = value
        }
    }
    private val play = Play()
    private lateinit var access: AccessManager
    private lateinit var purchases: Purchases
    private lateinit var refresher: PlanRefresher
    private var now = 1_000_000L
    private var retriesScheduled = 0

    /** Each Worker call: its token, and whether it may take the key (`rotate`). */
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
    /** Tokens Play has replaced with a newer one: the Worker answers `replaced` for them. */
    private val replaced = ConcurrentHashMap.newKeySet<String>()
    /** What the Worker answers for each token, in turn (the last answer repeats). */
    private val answers = ConcurrentHashMap<String, MutableList<MockResponse>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val ends = "2026-12-18T19:30:00Z"
    private val endsAt = Instant.parse(ends).toEpochMilli()

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val token = body["purchaseToken"]!!.jsonPrimitive.content
                calls += token to (body["rotate"]?.jsonPrimitive?.content != "false")
                // As the Worker does: one call per purchase at a time.
                if (!inFlight.add(token)) return json(409, """{"error":"busy"}""")
                try {
                    // The Worker calls Google and OpenRouter in turn: long enough for the app to do other things.
                    Thread.sleep(300)
                    if (token in replaced) return json(402, """{"error":"purchase_not_active","state":"replaced"}""")
                    val list = answers[token] ?: return json(400, """{"error":"purchase_invalid","reason":"token_unknown"}""")
                    return synchronized(list) { if (list.size > 1) list.removeAt(0) else list.first() }
                } finally {
                    inFlight.remove(token)
                }
            }
        }
        server.start()
        val backend = PlayAccessBackend(WorkerApi(OkHttpClient(), server.url("/").toString()), play, NoAttestation, { "9774d56d682e549c" }, pause = { delay(50) })
        val file = java.io.File(tmp.root, "access.preferences_pb")
        access = AccessManager(
            AccessStore(PreferenceDataStoreFactory.create(scope = scope) { file }) { false },
            vault, backend, { AllowanceCheck.Reading(4.0, System.currentTimeMillis()) }, { flowOf(0.0) }, scope,
        )
        // Wired as in TransmitApp.
        purchases = Purchases(
            play, access, PurchaseQueue(vault), scope,
            onFlowReturned = { sent -> if (sent) refresher.upToDate() else refresher.request(RefreshTrigger.PURCHASE_FLOW) },
        ) { retriesScheduled++ }
        refresher = PlanRefresher.forPlan(scope, purchases, access) { now }
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    private fun answer(token: String, vararg responses: MockResponse) {
        answers[token] = Collections.synchronizedList(responses.toMutableList())
    }

    private fun plan(product: String, allowance: Int, extra: String = "") =
        json(200, """{"kind":"subscription","productId":"$product","basePlanId":"monthly","state":"active","keyHash":"sh","newKey":false,"allowanceUsd":$allowance,"remainingUsd":$allowance,"allowanceRenewed":true,"periodEndsAt":"$ends","autoRenewing":true$extra}""")

    private fun owned(product: String, token: String, time: Long, acknowledged: Boolean = true) =
        OwnedPurchase(product, token, purchased = true, acknowledged = acknowledged, purchaseTime = time)

    private suspend fun holding(tier: Tier, token: String) {
        play.owned = listOf(owned(tier.productId, token, 1))
        access.applyGrant(Grant(Entitlement.Subscription(tier, endsAt), "sk-or-v1-held", "sh", 4.5, 3.0, purchaseToken = token), activate = true)
    }

    /**
     * Back from Play's sheet with [token], which Play goes on listing as unacknowledged (its cache lags the Worker's
     * acknowledgement). Meanwhile the Plans screen resumes, and the app's foreground check runs (`checkAccess`).
     */
    private suspend fun sheetReturns(product: String, token: String) {
        play.owned = listOf(owned(product, token, 2, acknowledged = false))
        withTimeout(5_000) { play.updates.subscriptionCount.first { it > 0 } }
        play.updates.emit(PurchaseUpdate.Purchases(listOf(owned(product, token, 2, acknowledged = false))))
        appComesBack()
    }

    private fun appComesBack() {
        refresher.request(RefreshTrigger.PLANS_OPENED)
        scope.launch {
            access.reconcile()
            purchases.sync()
        }
    }

    /** Lets anything the app started finish. */
    private suspend fun settled() = delay(1_500)

    private fun tokens() = synchronized(calls) { calls.map { it.first } }

    @Test
    fun anUpgradeIsOneWorkerCall() = runBlocking {
        holding(Tier.STANDARD, "std-1")
        // The Plans screen was opened a while ago; its refresh is long done.
        now += 60_000
        answer("plus-2", plan("transmit_plus", 9))

        assertEquals(LaunchResult.LAUNCHED, purchases.buy(Activity(), Tier.PLUS))
        replaced += "std-1"
        sheetReturns("transmit_plus", "plus-2")
        assertEquals(PurchaseUi.Done(Tier.PLUS), withTimeout(10_000) { purchases.state.first { it is PurchaseUi.Done } })
        // Done goes back to Settings, which asks for a refresh as it shows.
        refresher.request(RefreshTrigger.SETTINGS_OPENED)
        appComesBack()
        settled()

        assertEquals(listOf("plus-2" to true), calls.toList())
        val a = access.current() as AiAccess.Subscribed
        assertEquals(Tier.PLUS, a.tier)
        assertEquals("sk-or-v1-held", access.managedKey())
        assertEquals("plus-2", access.purchaseToken())
    }

    @Test
    fun aSwitchToTransmitIsOneWorkerCall() = runBlocking {
        holding(Tier.PLUS, "plus-1")
        now += 60_000
        answer("switch-2", plan("transmit_plus", 9, ""","pendingProductId":"transmit_monthly","pendingAllowanceUsd":4.5,"pendingStartsAt":"$ends""""))

        assertEquals(LaunchResult.LAUNCHED, purchases.buy(Activity(), Tier.STANDARD))
        replaced += "plus-1"
        sheetReturns("transmit_plus", "switch-2")
        assertEquals(PurchaseUi.SwitchSet(endsAt), withTimeout(10_000) { purchases.state.first { it is PurchaseUi.SwitchSet } })
        appComesBack()
        settled()

        assertEquals(listOf("switch-2" to true), calls.toList())
        val a = access.current() as AiAccess.Subscribed
        assertEquals(Tier.PLUS, a.tier)
        assertEquals(PlanSwitch(Tier.STANDARD, endsAt), a.pendingSwitch)
        assertEquals("switch-2", access.purchaseToken())
    }

    @Test
    fun aNewSubscriptionAfterOneEndedIsOneWorkerCall() = runBlocking {
        holding(Tier.STANDARD, "old-0")
        access.markEnded(EndReason.SUBSCRIPTION_ENDED)
        play.owned = emptyList()
        now += 60_000
        answer("new-3", json(200, """{"kind":"subscription","productId":"transmit_monthly","basePlanId":"monthly","state":"active","key":"sk-or-v1-new","keyHash":"nh","newKey":true,"allowanceUsd":4.5,"remainingUsd":4.5,"allowanceRenewed":true,"periodEndsAt":"$ends","autoRenewing":true}"""))

        assertEquals(LaunchResult.LAUNCHED, purchases.buy(Activity(), Tier.STANDARD))
        sheetReturns("transmit_monthly", "new-3")
        assertEquals(PurchaseUi.Done(Tier.STANDARD), withTimeout(10_000) { purchases.state.first { it is PurchaseUi.Done } })
        refresher.request(RefreshTrigger.SETTINGS_OPENED)
        appComesBack()
        settled()

        assertEquals(listOf("new-3"), tokens())
        assertEquals("sk-or-v1-new", access.managedKey())
        assertTrue(access.current() is AiAccess.Subscribed)
    }

    /** One call is still not "send once": a new token that fails is kept and sent again until the Worker takes it. */
    @Test
    fun aNewTokenThatFailsIsStillRetriedUntilTheWorkerTakesIt() = runBlocking {
        holding(Tier.STANDARD, "std-1")
        now += 60_000
        answer("plus-2", json(502, """{"error":"upstream_error"}"""), plan("transmit_plus", 9))

        assertEquals(LaunchResult.LAUNCHED, purchases.buy(Activity(), Tier.PLUS))
        replaced += "std-1"
        play.owned = listOf(owned("transmit_plus", "plus-2", 2, acknowledged = false))
        withTimeout(5_000) { play.updates.subscriptionCount.first { it > 0 } }
        play.updates.emit(PurchaseUpdate.Purchases(listOf(owned("transmit_plus", "plus-2", 2, acknowledged = false))))
        val failed = withTimeout(10_000) { purchases.state.first { it is PurchaseUi.Failed } } as PurchaseUi.Failed
        assertTrue(failed.error!!.retryable)
        settled()
        // Kept for the background job, which is scheduled; nothing else asked the Worker meanwhile.
        assertEquals(listOf("plus-2"), tokens())
        assertTrue(purchases.hasQueued())
        assertEquals(1, retriesScheduled)

        // The background job (or the next launch) sends it again, and it goes through.
        assertNull(purchases.deliver())
        assertEquals(listOf("plus-2", "plus-2"), tokens())
        assertEquals(Tier.PLUS, (access.current() as AiAccess.Subscribed).tier)
        assertEquals("plus-2", access.purchaseToken())
    }

    private object NoAttestation : TrialAttestation {
        override suspend fun token(requestHash: String) = error("no trial here")
    }

    private class Play : Storefront {
        override val available = true
        override val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 4)
        @Volatile var owned: List<OwnedPurchase>? = emptyList()
        override suspend fun offers(): Map<Tier, PlanOffer>? = null
        override suspend fun purchases() = owned
        override suspend fun launch(activity: Activity, tier: Tier, plan: PurchasePlan): LaunchResult =
            if (plan == PurchasePlan.NotOffered || plan == PurchasePlan.AlreadyOwned) LaunchResult.UNAVAILABLE else LaunchResult.LAUNCHED
    }
}
