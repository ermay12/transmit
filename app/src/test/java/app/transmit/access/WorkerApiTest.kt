package app.transmit.access

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The Worker client against a mock server: request shape, 200 parsing, and every error in `backend/API.md`. */
class WorkerApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: WorkerApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        api = WorkerApi(http, server.url("/").toString())
    }

    @After fun tearDown() { server.shutdown() }

    private fun reply(status: Int, body: String, retryAfter: String? = null) {
        server.enqueue(
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
                .apply { retryAfter?.let { setHeader("Retry-After", it) } },
        )
    }

    private fun body() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun trial(): AccessResult = runBlocking { api.trial("9774d56d682e549c", "tok").toResult(WorkerApi::trialGrant) }
    private fun sub(): AccessResult = runBlocking { api.subscription("ptok", "transmit_monthly", "kh", null).toResult { WorkerApi.subscriptionGrant(it, "ptok") } }

    private fun AccessResult.error() = (this as AccessResult.Failed).error

    @Test
    fun aTrialGrantIsReadWithItsExpiry() {
        reply(200, """{"kind":"trial","key":"sk-or-v1-x","keyHash":"f01d","allowanceUsd":0.5,"remainingUsd":0.31,"expiresAt":"2026-11-25T19:30:00Z","restored":true}""")
        val g = (trial() as AccessResult.Granted).grant
        assertEquals(Entitlement.Trial(Instant.parse("2026-11-25T19:30:00Z").toEpochMilli()), g.entitlement)
        assertEquals("sk-or-v1-x", g.key)
        assertEquals("f01d", g.keyHash)
        assertEquals(0.5, g.allowanceUsd, 1e-9)
        assertEquals(0.31, g.remainingUsd, 1e-9)

        val req = server.takeRequest()
        assertEquals("/v1/trial", req.path)
        assertEquals("POST", req.method)
        assertTrue(req.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("9774d56d682e549c", sent["androidId"]!!.jsonPrimitive.content)
        assertEquals("tok", sent["integrityToken"]!!.jsonPrimitive.content)
    }

    @Test
    fun aSubscriptionKeptKeyHasNoKeyAndARotatedOneDoes() {
        reply(200, """{"kind":"subscription","productId":"transmit_plus","basePlanId":"monthly","state":"canceled","keyHash":"0b9e","newKey":false,"allowanceUsd":9,"remainingUsd":4,"allowanceRenewed":false,"periodEndsAt":"2026-12-18T19:30:00Z","autoRenewing":false}""")
        val kept = (sub() as AccessResult.Granted).grant
        assertNull(kept.key)
        assertEquals("0b9e", kept.keyHash)
        assertEquals("ptok", kept.purchaseToken)
        assertEquals(Entitlement.Subscription(Tier.PLUS, Instant.parse("2026-12-18T19:30:00Z").toEpochMilli(), autoRenewing = false), kept.entitlement)
        assertFalse(kept.allowanceRenewed)

        reply(200, """{"kind":"subscription","productId":"transmit_monthly","state":"active","key":"sk-or-v1-new","keyHash":"aa","newKey":true,"allowanceUsd":4.5,"remainingUsd":4.5,"periodEndsAt":"2026-12-18T19:30:00Z","autoRenewing":true}""")
        val rotated = (sub() as AccessResult.Granted).grant
        assertEquals("sk-or-v1-new", rotated.key)
        assertEquals(Tier.STANDARD, (rotated.entitlement as Entitlement.Subscription).tier)
        // Absent means false. A credited order says true: the key's limit was just raised.
        assertFalse(rotated.allowanceRenewed)
        reply(200, """{"kind":"subscription","productId":"transmit_monthly","state":"active","keyHash":"aa","newKey":false,"allowanceUsd":4.5,"remainingUsd":4.5,"allowanceRenewed":true,"periodEndsAt":"2027-01-18T19:30:00Z","autoRenewing":true}""")
        assertTrue((sub() as AccessResult.Granted).grant.allowanceRenewed)
    }

    @Test
    fun aCanceledStateMeansThePlanEndsWhateverAutoRenewingSays() {
        val ends = Instant.parse("2026-12-18T19:30:00Z").toEpochMilli()
        // API.md: `state` is `active`, `canceled` (renewal off; the AI works until `periodEndsAt`) or `grace`.
        reply(200, """{"kind":"subscription","productId":"transmit_plus","state":"canceled","keyHash":"0b9e","newKey":false,"allowanceUsd":9,"remainingUsd":4,"periodEndsAt":"2026-12-18T19:30:00Z"}""")
        assertEquals(Entitlement.Subscription(Tier.PLUS, ends, autoRenewing = false), (sub() as AccessResult.Granted).grant.entitlement)
        reply(200, """{"kind":"subscription","productId":"transmit_plus","state":"grace","keyHash":"0b9e","newKey":false,"allowanceUsd":9,"remainingUsd":4,"periodEndsAt":"2026-12-18T19:30:00Z","autoRenewing":true}""")
        assertEquals(Entitlement.Subscription(Tier.PLUS, ends, autoRenewing = true), (sub() as AccessResult.Granted).grant.entitlement)
    }

    @Test
    fun aSwitchToTransmitIsReadFromThePendingFieldsAndItsAbsenceMeansNone() {
        val ends = "2026-12-18T19:30:00Z"
        val endsAt = Instant.parse(ends).toEpochMilli()
        // API.md "Switch to Transmit" step 3: still Plus, with Plus's allowance, until pendingStartsAt.
        reply(200, """{"kind":"subscription","productId":"transmit_plus","basePlanId":"monthly","state":"active","keyHash":"0b9e","newKey":false,"allowanceUsd":9,"remainingUsd":6,"allowanceRenewed":false,"periodEndsAt":"$ends","autoRenewing":true,"pendingProductId":"transmit_monthly","pendingAllowanceUsd":4.5,"pendingStartsAt":"$ends"}""")
        val switching = (sub() as AccessResult.Granted).grant
        assertEquals(Entitlement.Subscription(Tier.PLUS, endsAt, autoRenewing = true, pendingSwitch = PlanSwitch(Tier.STANDARD, endsAt)), switching.entitlement)
        assertEquals(9.0, switching.allowanceUsd, 0.0)
        assertEquals(6.0, switching.remainingUsd, 0.0)
        assertNull(switching.key)

        // A Worker from before the downgrade, a cancelled switch, or renewal turned off: no pending fields at all.
        reply(200, """{"kind":"subscription","productId":"transmit_plus","state":"active","keyHash":"0b9e","newKey":false,"allowanceUsd":9,"remainingUsd":6,"periodEndsAt":"$ends","autoRenewing":true}""")
        assertNull(((sub() as AccessResult.Granted).grant.entitlement as Entitlement.Subscription).pendingSwitch)
        reply(200, """{"kind":"subscription","productId":"transmit_plus","state":"active","keyHash":"0b9e","newKey":false,"allowanceUsd":9,"remainingUsd":6,"periodEndsAt":"$ends","autoRenewing":true,"pendingProductId":null,"pendingStartsAt":null}""")
        assertNull(((sub() as AccessResult.Granted).grant.entitlement as Entitlement.Subscription).pendingSwitch)

        // pendingStartsAt always equals periodEndsAt, so a missing one falls back to it.
        reply(200, """{"kind":"subscription","productId":"transmit_plus","state":"active","keyHash":"0b9e","newKey":false,"allowanceUsd":9,"remainingUsd":6,"periodEndsAt":"$ends","autoRenewing":true,"pendingProductId":"transmit_monthly"}""")
        assertEquals(PlanSwitch(Tier.STANDARD, endsAt), ((sub() as AccessResult.Granted).grant.entitlement as Entitlement.Subscription).pendingSwitch)

        // Something the app doesn't know is ignored; the rest of the answer still counts.
        reply(200, """{"kind":"subscription","productId":"transmit_plus","state":"active","keyHash":"0b9e","newKey":false,"allowanceUsd":9,"remainingUsd":6,"periodEndsAt":"$ends","autoRenewing":true,"pendingProductId":"transmit_yearly","pendingStartsAt":"$ends"}""")
        val unknown = (sub() as AccessResult.Granted).grant
        assertNull((unknown.entitlement as Entitlement.Subscription).pendingSwitch)
        assertEquals(Tier.PLUS, (unknown.entitlement as Entitlement.Subscription).tier)
    }

    @Test
    fun theSubscriptionRequestLeavesOutWhatTheAppDoesntHave() = runBlocking {
        reply(400, """{"error":"bad_request","message":"x"}""")
        api.subscription("ptok", null, null, null)
        val sent = body()
        assertEquals(setOf("purchaseToken"), sent.keys)

        reply(400, """{"error":"bad_request","message":"x"}""")
        api.subscription("ptok", "transmit_plus", "kh", "aid")
        assertEquals(setOf("purchaseToken", "productId", "keyHash", "androidId"), body().keys)
        // "rotate" only goes out to say no.
        reply(400, """{"error":"bad_request","message":"x"}""")
        api.subscription("ptok", null, "kh", null, rotate = false)
        val noRotate = body()
        assertEquals(setOf("purchaseToken", "keyHash", "rotate"), noRotate.keys)
        assertEquals("false", noRotate["rotate"]!!.jsonPrimitive.content)
    }

    @Test
    fun aGrantMissingWhatItNeedsIsAServiceProblem() {
        reply(200, """{"kind":"trial","keyHash":"f01d","allowanceUsd":0.5,"expiresAt":"2026-11-25T19:30:00Z"}""")
        assertEquals(AccessErrorCode.SERVICE, trial().error().code)
        reply(200, """{"kind":"subscription","productId":"something_else","keyHash":"x","newKey":false,"allowanceUsd":4.5}""")
        assertEquals(AccessErrorCode.SERVICE, sub().error().code)
        reply(200, """{"kind":"subscription","productId":"transmit_plus","keyHash":"x","newKey":true,"allowanceUsd":9}""")
        assertEquals(AccessErrorCode.SERVICE, sub().error().code)
    }

    @Test
    fun trialErrorsMapToTheirPlainLanguageReason() {
        fun code(body: String, status: Int = 403) = run { reply(status, body); trial().error() }
        assertEquals(AccessErrorCode.DEVICE_NOT_SUPPORTED, code("""{"error":"integrity_failed","reason":"device_integrity"}""").code)
        assertEquals(AccessErrorCode.NOT_FROM_PLAY, code("""{"error":"integrity_failed","reason":"app_not_recognized"}""").code)
        assertEquals(AccessErrorCode.NOT_FROM_PLAY, code("""{"error":"integrity_failed","reason":"unlicensed"}""").code)
        for (reason in listOf("token_invalid", "wrong_package", "request_hash_mismatch", "token_expired")) {
            assertEquals(reason, AccessErrorCode.SERVICE, code("""{"error":"integrity_failed","reason":"$reason"}""").code)
        }
        assertEquals(AccessErrorCode.TRIAL_ALREADY_USED, code("""{"error":"trial_already_used"}""", 409).code)
        assertEquals(AccessErrorCode.SERVICE, code("""{"error":"bad_request"}""", 400).code)

        val full = code("""{"error":"trial_capacity_reached","retryAfterSeconds":7200}""", 429)
        assertEquals(AccessErrorCode.TRIALS_FULL, full.code)
        assertEquals(7_200_000L, full.retryAfterMs)
        assertFalse(full.retryable)
        // Only the header: still read.
        reply(429, """{"error":"trial_capacity_reached"}""", retryAfter = "60")
        assertEquals(60_000L, trial().error().retryAfterMs)

        // Too many calls from this network: its own reason, and the wait.
        val limited = code("""{"error":"rate_limited","message":"x","retryAfterSeconds":60}""", 429)
        assertEquals(AccessErrorCode.RATE_LIMITED, limited.code)
        assertEquals(60_000L, limited.retryAfterMs)
    }

    @Test
    fun subscriptionErrorsMapByErrorReasonAndState() {
        fun result(body: String, status: Int = 402) = run { reply(status, body); sub() }
        assertEquals(AccessErrorCode.NO_PURCHASE, result("""{"error":"purchase_invalid","reason":"token_unknown"}""", 400).error().code)
        // A product mix-up is the app's bug, not "no subscription found".
        assertEquals(AccessErrorCode.SERVICE, result("""{"error":"purchase_invalid","reason":"unknown_product"}""", 400).error().code)
        assertEquals(AccessErrorCode.SERVICE, result("""{"error":"purchase_invalid","reason":"product_mismatch"}""", 400).error().code)
        assertEquals(AccessErrorCode.PAYMENT_PENDING, result("""{"error":"purchase_not_active","state":"pending"}""").error().code)
        assertEquals(AccessErrorCode.PAYMENT_PROBLEM, result("""{"error":"purchase_not_active","state":"on_hold"}""").error().code)
        assertEquals(AccessErrorCode.PAYMENT_PROBLEM, result("""{"error":"purchase_not_active","state":"paused"}""").error().code)
        for (state in listOf("expired", "canceled_pending", "refunded")) {
            assertEquals(state, AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED), result("""{"error":"purchase_not_active","state":"$state"}"""))
        }
        // The caller swaps in the newer token first; reaching resultFor with it is unexpected.
        assertEquals(AccessErrorCode.SERVICE, result("""{"error":"purchase_not_active","state":"replaced"}""").error().code)

        // Only with "rotate": false. Nothing changed on the Worker, so neither sending it again nor retrying helps.
        val mismatch = result("""{"error":"key_mismatch"}""", 409).error()
        assertEquals(AccessErrorCode.KEY_IN_USE_ELSEWHERE, mismatch.code)
        assertFalse(mismatch.retryable)
                val busy = result("""{"error":"busy"}""", 409).error()
        assertEquals(AccessErrorCode.BUSY, busy.code)
        assertTrue(busy.retryable)
        val upstream = result("""{"error":"upstream_error","service":"google"}""", 502).error()
        assertEquals(AccessErrorCode.SERVICE, upstream.code)
        assertTrue(upstream.retryable)
        assertTrue(result("""{"error":"internal_error"}""", 500).error().retryable)
        assertFalse(result("""{"error":"not_found"}""", 404).error().retryable)
        assertFalse(result("""{"error":"method_not_allowed"}""", 405).error().retryable)
    }

    @Test
    fun theErrorFieldWinsOverTheStatus() {
        // A 200 that carries an error is still the error; a 400 that says busy is still busy.
        reply(200, """{"error":"busy"}""")
        assertEquals(AccessErrorCode.BUSY, sub().error().code)
        reply(400, """{"error":"busy"}""")
        assertEquals(AccessErrorCode.BUSY, sub().error().code)
    }

    @Test
    fun aProxyErrorPageIsARetryableServiceProblem() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val e = sub().error()
        assertEquals(AccessErrorCode.SERVICE, e.code)
        assertTrue(e.retryable)
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        assertFalse(sub().error().retryable)
    }

    @Test
    fun noConnectionIsNetwork() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = trial().error()
        assertEquals(AccessErrorCode.NETWORK, e.code)
        assertTrue(e.retryable)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(AccessErrorCode.NETWORK, sub().error().code)
    }

    @Test
    fun purchaseDeliveryRetriesOnlyWhatCanSucceedLater() {
        fun d(status: Int, error: String?, state: String? = null) = WorkerReply.Error(status, error, state = state).delivery
        assertEquals(Delivery.DONE, WorkerReply.Ok(Json.parseToJsonElement("{}").jsonObject).delivery)
        assertEquals(Delivery.RETRY, WorkerReply.Offline(java.io.IOException("x")).delivery)
        assertEquals(Delivery.RETRY, d(409, "busy"))
        assertEquals(Delivery.RETRY, d(502, "upstream_error"))
        assertEquals(Delivery.RETRY, d(500, "internal_error"))
        assertEquals(Delivery.RETRY, d(503, null))
        assertEquals(Delivery.RETRY, d(429, "rate_limited"))
        assertEquals(Delivery.RETRY, d(402, "purchase_not_active", "pending"))
        assertEquals(Delivery.USE_NEWER_TOKEN, d(402, "purchase_not_active", "replaced"))
        assertEquals(Delivery.DONE, d(400, "purchase_invalid"))
        assertEquals(Delivery.DONE, d(400, "bad_request"))
        assertEquals(Delivery.DONE, d(409, "key_mismatch"))
        assertEquals(Delivery.DONE, d(402, "purchase_not_active", "expired"))
        assertEquals(Delivery.DONE, d(402, "purchase_not_active", "on_hold"))
        assertEquals(Delivery.DONE, d(404, null))
    }
}
