package app.transmit.access

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.transmit.data.KeyVault
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams.ReplacementMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * Plus → Transmit and back, end to end: the plan Play's sheet opens with, the new token reaching the Worker
 * (`backend/API.md` "Switch to Transmit" and "Keep Plus"), and what the app shows meanwhile.
 */
class PlanSwitchTest {
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
    private val play = SheetStore()
    private lateinit var access: AccessManager
    private lateinit var purchases: Purchases

    private val ends = "2026-12-18T19:30:00Z"
    private val endsAt = Instant.parse(ends).toEpochMilli()

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = MockWebServer().apply { start() }
        val backend = PlayAccessBackend(WorkerApi(OkHttpClient(), server.url("/").toString()), play, NoAttestation, { "9774d56d682e549c" }, pause = {})
        val file = java.io.File(tmp.root, "access.preferences_pb")
        access = AccessManager(
            AccessStore(PreferenceDataStoreFactory.create(scope = scope) { file }) { false },
            vault, backend, { AllowanceCheck.Reading(6.0, System.currentTimeMillis()) }, { flowOf(0.0) }, scope,
        )
        purchases = Purchases(play, access, PurchaseQueue(vault), scope) {}
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun reply(status: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun sent(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun plusAnswer(pending: Boolean) =
        """{"kind":"subscription","productId":"transmit_plus","basePlanId":"monthly","state":"active","keyHash":"sh","newKey":false,"allowanceUsd":9,"remainingUsd":6,"allowanceRenewed":false,"periodEndsAt":"$ends","autoRenewing":true""" +
            (if (pending) ""","pendingProductId":"transmit_monthly","pendingAllowanceUsd":4.5,"pendingStartsAt":"$ends"}""" else "}")

    private fun owned(token: String, time: Long, acknowledged: Boolean = true) =
        OwnedPurchase("transmit_plus", token, purchased = true, acknowledged = acknowledged, purchaseTime = time)

    /** A Plus subscriber whose key and token the Worker issued earlier. */
    private suspend fun onPlus() {
        play.owned = listOf(owned("plus-1", 1))
        access.applyGrant(
            Grant(Entitlement.Subscription(Tier.PLUS, endsAt), "sk-or-v1-plus", "sh", 9.0, 6.0, purchaseToken = "plus-1"),
            activate = true,
        )
    }

    /** Opens the sheet for [tier], then answers as Play does: a new token, still listed as Plus. */
    private suspend fun change(tier: Tier, newToken: String, time: Long): PurchasePlan {
        assertEquals(LaunchResult.LAUNCHED, purchases.buy(Activity(), tier))
        val plan = play.launched.last()
        play.owned = listOf(owned(newToken, time))
        withTimeout(5_000) { play.updates.subscriptionCount.first { it > 0 } }
        play.updates.emit(PurchaseUpdate.Purchases(listOf(owned(newToken, time, acknowledged = false))))
        return plan
    }

    @Test
    fun switchingToTransmitKeepsPlusUntilTheRenewalAndKeepPlusUndoesIt() = runBlocking {
        onPlus()

        // Switch to Transmit: DEFERRED on the Plus token.
        reply(200, plusAnswer(pending = true))
        val down = change(Tier.STANDARD, "switch-2", 2)
        assertEquals(PurchasePlan.Downgrade("plus-1", "transmit_plus", ReplacementMode.DEFERRED), down)
        assertEquals(PurchaseUi.SwitchSet(endsAt), withTimeout(5_000) { purchases.state.first { it is PurchaseUi.SwitchSet } })
        // Sent at once with the key held now, so the Worker keeps the key (and acknowledges the new token).
        val body = sent()
        assertEquals("switch-2", body.s("purchaseToken"))
        assertEquals("sh", body.s("keyHash"))

        // Plus, with Plus's allowance and percent, until the switch; the key and its hash are unchanged.
        val a = access.current() as AiAccess.Subscribed
        assertEquals(Tier.PLUS, a.tier)
        assertEquals(PlanSwitch(Tier.STANDARD, endsAt), a.pendingSwitch)
        assertEquals(endsAt, a.renewsAt)
        assertEquals(33, access.allowanceNow()!!.percent)
        assertEquals("sk-or-v1-plus", access.managedKey())
        assertEquals("switch-2", access.purchaseToken())

        // While it's set: Plus is Keep Plus on the switch's token, and another switch isn't offered.
        assertEquals(PurchasePlan.NotOffered, purchases.planFor(Tier.STANDARD))
        reply(200, plusAnswer(pending = false))
        val keep = change(Tier.PLUS, "keep-3", 3)
        assertEquals(PurchasePlan.KeepPlus("switch-2", "transmit_plus", ReplacementMode.DEFERRED), keep)
        assertEquals(PurchaseUi.KeptPlus, withTimeout(5_000) { purchases.state.first { it == PurchaseUi.KeptPlus } })
        assertEquals("keep-3", sent().s("purchaseToken"))
        val kept = access.current() as AiAccess.Subscribed
        assertEquals(Tier.PLUS, kept.tier)
        assertNull(kept.pendingSwitch)
        assertEquals("sk-or-v1-plus", access.managedKey())
        assertEquals("keep-3", access.purchaseToken())
    }

    @Test
    fun theOldPlusTokenAnsweringReplacedStillWorksAndTheNewTokenIsSent() = runBlocking {
        onPlus()
        // The switch happened, but the app never got to send the new token (it was closed, say).
        play.owned = listOf(owned("switch-2", 2))
        reply(402, """{"error":"purchase_not_active","state":"replaced"}""")
        reply(200, plusAnswer(pending = true))
        assertNull(access.refresh(force = true))
        assertEquals("plus-1", sent().s("purchaseToken"))
        val retry = sent()
        assertEquals("switch-2", retry.s("purchaseToken"))
        assertEquals("sh", retry.s("keyHash"))
        assertEquals("false", retry.s("rotate"))

        // Not ended, the same key, and from now on the new token.
        val a = access.current() as AiAccess.Subscribed
        assertEquals(Tier.PLUS, a.tier)
        assertEquals(Tier.STANDARD, a.pendingSwitch?.tier)
        assertEquals("sk-or-v1-plus", access.managedKey())
        assertEquals("switch-2", access.purchaseToken())
    }

    @Test
    fun aSubscriberIsNeverSoldASecondPlanWhilePlayCantBeAsked() = runBlocking {
        onPlus()
        play.owned = null
        assertEquals(PurchasePlan.NotOffered, purchases.planFor(Tier.STANDARD))
        assertEquals(PurchasePlan.NotOffered, purchases.planFor(Tier.PLUS))
        assertTrue(play.launched.isEmpty())
    }

    @Test
    fun aSwitchThatPlayRefusesSaysItWasAPlanChange() = runBlocking {
        onPlus()
        purchases.buy(Activity(), Tier.STANDARD)
        withTimeout(5_000) { play.updates.subscriptionCount.first { it > 0 } }
        play.updates.emit(PurchaseUpdate.Failed(5, "Developer error"))
        assertEquals(PurchaseUi.Failed(null, planChange = true), withTimeout(5_000) { purchases.state.first { it is PurchaseUi.Failed } })
    }

    private object NoAttestation : TrialAttestation {
        override suspend fun token(requestHash: String) = error("no trial here")
    }

    private class SheetStore : Storefront {
        override val available = true
        override val updates = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 4)
        var owned: List<OwnedPurchase>? = emptyList()
        val launched = mutableListOf<PurchasePlan>()
        override suspend fun offers(): Map<Tier, PlanOffer>? = null
        override suspend fun purchases() = owned
        override suspend fun launch(activity: Activity, tier: Tier, plan: PurchasePlan): LaunchResult {
            if (plan == PurchasePlan.NotOffered) return LaunchResult.UNAVAILABLE
            launched += plan
            return LaunchResult.LAUNCHED
        }
    }
}
