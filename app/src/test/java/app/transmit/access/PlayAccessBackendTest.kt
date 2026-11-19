package app.transmit.access

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Play-backed backend: what it asks Play and the Worker, and how it handles busy, stale and replaced answers. */
class PlayAccessBackendTest {
    private lateinit var server: MockWebServer
    private val store = FakeStore()
    private val attestation = FakeAttestation()
    private val pauses = mutableListOf<Long>()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun backend(checkInstall: Boolean = true) = PlayAccessBackend(
        WorkerApi(OkHttpClient(), server.url("/").toString()), store, attestation, { "9774d56d682e549c" }, checkInstall,
        pause = { pauses += it },
    )

    private fun reply(status: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun sent(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private val trialOk = """{"kind":"trial","key":"sk-or-v1-t","keyHash":"th","allowanceUsd":0.5,"remainingUsd":0.5,"expiresAt":"2026-11-25T19:30:00Z","restored":false}"""
    private fun subOk(product: String = "transmit_monthly", key: String? = null) =
        """{"kind":"subscription","productId":"$product","state":"active",${key?.let { "\"key\":\"$it\"," } ?: ""}"keyHash":"sh","newKey":${key != null},"allowanceUsd":4.5,"remainingUsd":4.5,"periodEndsAt":"2026-12-18T19:30:00Z","autoRenewing":true}"""

    @Test
    fun theRequestHashBindsAndroidIdAsApiMdSays() {
        assertEquals("abef3ce8ef9b46415cdb18f7df04ddd97a1f12e4cbf32efebc1ea31fda1417fd", TrialAttestation.requestHash("9774d56d682e549c"))
    }

    @Test
    fun aCopyNotFromPlayCantStartATrialAndTheWorkerIsntAsked() = runBlocking {
        store.available = false
        assertEquals(AccessErrorCode.NOT_FROM_PLAY, (backend().startTrial() as AccessResult.Failed).error.code)
        assertEquals(AccessErrorCode.NOT_FROM_PLAY, (backend().restore(null) as AccessResult.Failed).error.code)
        assertEquals(0, server.requestCount)
        assertTrue(attestation.hashes.isEmpty())
    }

    @Test
    fun aTrialSendsAndroidIdWithATokenForItsHash() = runBlocking {
        reply(200, trialOk)
        val g = (backend().startTrial() as AccessResult.Granted).grant
        assertEquals("sk-or-v1-t", g.key)
        assertEquals(listOf(TrialAttestation.requestHash("9774d56d682e549c")), attestation.hashes)
        val body = sent()
        assertEquals("9774d56d682e549c", body.s("androidId"))
        assertEquals("token-1", body.s("integrityToken"))
    }

    @Test
    fun aStaleIntegrityTokenGetsOneFreshTry() = runBlocking {
        reply(403, """{"error":"integrity_failed","reason":"token_expired"}""")
        reply(200, trialOk)
        assertTrue(backend().startTrial() is AccessResult.Granted)
        assertEquals("token-1", sent().s("integrityToken"))
        assertEquals("token-2", sent().s("integrityToken"))

        // Only once: a second stale answer is a service problem.
        reply(403, """{"error":"integrity_failed","reason":"token_expired"}""")
        reply(403, """{"error":"integrity_failed","reason":"token_expired"}""")
        assertEquals(AccessErrorCode.SERVICE, (backend().startTrial() as AccessResult.Failed).error.code)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun aBusyTrialIsAskedAgainWithAFreshToken() = runBlocking {
        reply(409, """{"error":"busy","message":"A trial request for this device is already in progress."}""")
        reply(200, trialOk)
        assertTrue(backend().startTrial() is AccessResult.Granted)
        assertEquals(listOf(3_000L), pauses)
        assertEquals("token-1", sent().s("integrityToken"))
        assertEquals("token-2", sent().s("integrityToken"))

        // Not forever.
        repeat(3) { reply(409, """{"error":"busy"}""") }
        assertEquals(AccessErrorCode.BUSY, (backend().startTrial() as AccessResult.Failed).error.code)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun aRateLimitedTrialIsntRetriedInALoop() = runBlocking {
        reply(429, """{"error":"rate_limited","retryAfterSeconds":60}""")
        val e = (backend().startTrial() as AccessResult.Failed).error
        assertEquals(AccessErrorCode.RATE_LIMITED, e.code)
        assertEquals(60_000L, e.retryAfterMs)
        assertEquals(1, server.requestCount)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun noIntegrityTokenSaysWhy() = runBlocking {
        attestation.failWith = AccessError(AccessErrorCode.DEVICE_NOT_SUPPORTED)
        assertEquals(AccessErrorCode.DEVICE_NOT_SUPPORTED, (backend().startTrial() as AccessResult.Failed).error.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun busyIsTriedAgainAfterAFewSeconds() = runBlocking {
        reply(409, """{"error":"busy"}""")
        reply(200, subOk())
        assertTrue(backend().refresh("ptok", "sh") is AccessResult.Granted)
        assertEquals(listOf(3_000L), pauses)

        repeat(3) { reply(409, """{"error":"busy"}""") }
        val e = (backend().refresh("ptok", "sh") as AccessResult.Failed).error
        assertEquals(AccessErrorCode.BUSY, e.code)
        assertTrue(e.retryable)
    }

    @Test
    fun aPurchaseSendsItsProductAndAndroidIdAndARefreshOnlyTheKey() = runBlocking {
        reply(200, subOk(key = "sk-or-v1-s"))
        val g = (backend().activatePurchase(PlayPurchase("transmit_monthly", "ptok"), currentKeyHash = null) as AccessResult.Granted).grant
        assertEquals("ptok", g.purchaseToken)
        assertEquals("sk-or-v1-s", g.key)
        val body = sent()
        assertEquals(setOf("purchaseToken", "productId", "androidId"), body.keys)

        // A refresh is never the user's say-so to take the key from another device.
        reply(200, subOk())
        backend().refresh("ptok", "sh")
        assertEquals(mapOf("purchaseToken" to "ptok", "keyHash" to "sh", "rotate" to "false"), sent().mapValues { it.value.jsonPrimitive.content })

        reply(409, """{"error":"key_mismatch"}""")
        assertEquals(AccessErrorCode.KEY_IN_USE_ELSEWHERE, (backend().refresh("ptok", "stale") as AccessResult.Failed).error.code)
        assertEquals("stale", sent().s("keyHash"))
    }

    @Test
    fun aReplacedTokenIsSwappedForPlaysNewerOne() = runBlocking {
        store.owned = listOf(
            OwnedPurchase("transmit_monthly", "old", purchased = true, acknowledged = true, purchaseTime = 1),
            OwnedPurchase("transmit_plus", "new", purchased = true, acknowledged = true, purchaseTime = 2),
        )
        reply(402, """{"error":"purchase_not_active","state":"replaced"}""")
        reply(200, subOk(product = "transmit_plus"))
        val g = (backend().refresh("old", "sh") as AccessResult.Granted).grant
        assertEquals("new", g.purchaseToken)
        assertEquals(Tier.PLUS, (g.entitlement as Entitlement.Subscription).tier)
        sent()
        val second = sent()
        assertEquals("new", second.s("purchaseToken"))
        assertEquals("transmit_plus", second.s("productId"))
        // The key is kept across the upgrade.
        assertEquals("sh", second.s("keyHash"))
        assertEquals("false", second.s("rotate"))

        // Nothing newer on Play: give up rather than loop.
        store.owned = listOf(OwnedPurchase("transmit_monthly", "old", purchased = true, acknowledged = true))
        reply(402, """{"error":"purchase_not_active","state":"replaced"}""")
        assertEquals(AccessErrorCode.SERVICE, (backend().refresh("old", "sh") as AccessResult.Failed).error.code)
    }

    @Test
    fun restoreSendsTheBestPurchaseWithTheKeyHeldNow() = runBlocking {
        store.owned = listOf(
            OwnedPurchase("transmit_monthly", "std", purchased = true, acknowledged = true, purchaseTime = 5),
            OwnedPurchase("transmit_plus", "plus", purchased = true, acknowledged = true, purchaseTime = 1),
        )
        reply(200, subOk(product = "transmit_plus", key = "sk-or-v1-r"))
        assertTrue(backend().restore("sh") is AccessResult.Granted)
        val body = sent()
        assertEquals("plus", body.s("purchaseToken"))
        assertEquals("sh", body.s("keyHash"))
        // "Restore purchase" is the user asking to take the key: rotate stays at its default.
        assertFalse("rotate" in body)

        store.owned = emptyList()
        assertEquals(AccessErrorCode.NO_PURCHASE, (backend().restore(null) as AccessResult.Failed).error.code)
        store.owned = null
        val offline = (backend().restore(null) as AccessResult.Failed).error
        assertEquals(AccessErrorCode.SERVICE, offline.code)
        assertTrue(offline.retryable)
    }

    @Test
    fun restorePrefersAPaidUpPurchase() {
        val held = OwnedPurchase("transmit_plus", "held", purchased = true, acknowledged = true, suspended = true, purchaseTime = 9)
        val ok = OwnedPurchase("transmit_monthly", "ok", purchased = true, acknowledged = true, purchaseTime = 1)
        val pending = OwnedPurchase("transmit_plus", "pending", purchased = false, acknowledged = false, purchaseTime = 10)
        assertEquals("ok", PlayAccessBackend.restorable(listOf(held, ok, pending))?.purchaseToken)
        assertEquals("held", PlayAccessBackend.restorable(listOf(held, pending))?.purchaseToken)
        assertNull(PlayAccessBackend.restorable(listOf(OwnedPurchase("other_app_sub", "x", purchased = true, acknowledged = true))))
    }

    @Test
    fun playSaysWhetherTheSubscriptionIsPaidUp() = runBlocking {
        store.owned = listOf(OwnedPurchase("transmit_monthly", "t", purchased = true, acknowledged = true))
        assertEquals(true, backend().activeOnPlay())
        store.owned = listOf(OwnedPurchase("transmit_monthly", "t", purchased = true, acknowledged = true, suspended = true))
        assertEquals(false, backend().activeOnPlay())
        store.owned = emptyList()
        assertEquals(false, backend().activeOnPlay())
        store.owned = null
        assertNull(backend().activeOnPlay())
    }

    @Test
    fun anUpgradeToPlusChargesTheFullPriceNowAndOtherChoicesDont() {
        val standard = OwnedPurchase("transmit_monthly", "std-token", purchased = true, acknowledged = true)
        val plus = OwnedPurchase("transmit_plus", "plus-token", purchased = true, acknowledged = true)
        assertEquals(PurchasePlan.New, PurchasePlan.choose(Tier.STANDARD, emptyList()))
        assertEquals(PurchasePlan.New, PurchasePlan.choose(Tier.PLUS, emptyList()))

        val upgrade = PurchasePlan.choose(Tier.PLUS, listOf(standard)) as PurchasePlan.Upgrade
        assertEquals("std-token", upgrade.oldPurchaseToken)
        assertEquals("transmit_monthly", upgrade.oldProductId)
        assertEquals(SubscriptionProductReplacementParams.ReplacementMode.CHARGE_FULL_PRICE, upgrade.replacementMode)
        assertEquals(4, upgrade.replacementMode)

        assertEquals(PurchasePlan.AlreadyOwned, PurchasePlan.choose(Tier.STANDARD, listOf(standard)))
        assertEquals(PurchasePlan.AlreadyOwned, PurchasePlan.choose(Tier.PLUS, listOf(plus)))
        assertEquals(PurchasePlan.NotOffered, PurchasePlan.choose(Tier.STANDARD, listOf(plus)))
        // A canceled Standard that's still running upgrades the same way.
        assertTrue(PurchasePlan.choose(Tier.PLUS, listOf(standard.copy(autoRenewing = false))) is PurchasePlan.Upgrade)
        // A payment that hasn't gone through isn't something to replace.
        assertEquals(PurchasePlan.New, PurchasePlan.choose(Tier.PLUS, listOf(standard.copy(purchased = false))))
        assertFalse(PurchasePlan.choose(Tier.PLUS, listOf(standard)) == PurchasePlan.New)
    }

    private class FakeStore : Storefront {
        override var available = true
        var owned: List<OwnedPurchase>? = emptyList()
        override val updates: Flow<PurchaseUpdate> = emptyFlow()
        override suspend fun offers(): Map<Tier, PlanOffer>? = null
        override suspend fun purchases() = owned
        override suspend fun launch(activity: Activity, tier: Tier, plan: PurchasePlan) = LaunchResult.UNAVAILABLE
    }

    private class FakeAttestation : TrialAttestation {
        val hashes = mutableListOf<String>()
        var failWith: AccessError? = null
        override suspend fun token(requestHash: String): String {
            failWith?.let { throw AttestationException(it) }
            hashes += requestHash
            return "token-${hashes.size}"
        }
    }
}
