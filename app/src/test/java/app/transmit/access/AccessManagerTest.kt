package app.transmit.access

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.transmit.ai.AiBlock
import app.transmit.ai.AiException
import app.transmit.ai.ProviderType
import app.transmit.data.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Switching modes, the managed key slot, and what managed failures turn into. */
class AccessManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 86_400_000L
    private lateinit var scope: CoroutineScope
    private val vault = MapVault()
    private val spent = MutableStateFlow(0.0)
    private val backend = ScriptedBackend()
    private var foreground = true
    private var backgroundEnds = mutableListOf<EndReason>()

    private var stores = 0

    private fun manager(): AccessManager {
        val file = java.io.File(tmp.root, "access${stores++}.preferences_pb")
        val store = AccessStore(PreferenceDataStoreFactory.create(scope = scope) { file }) {
            vault.has(ProviderType.OPENROUTER.secretName)
        }
        return AccessManager(store, vault, backend, { AllowanceCheck.Unavailable }, { spent }, scope, isForeground = { foreground }).also {
            it.listener = object : AccessListener {
                override fun onEndedInBackground(reason: EndReason) {
                    backgroundEnds += reason
                }
            }
        }
    }

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() { scope.cancel() }

    private fun trialGrant(remaining: Double = 0.5) =
        Grant(Entitlement.Trial(System.currentTimeMillis() + 7 * day), "sk-managed", "hash", 0.5, remaining)

    private fun subGrant(tier: Tier = Tier.STANDARD, remaining: Double = 4.5) =
        Grant(Entitlement.Subscription(tier, System.currentTimeMillis() + 20 * day), "sk-sub", "subhash", 4.5, remaining, purchaseToken = "tok")

    /** Waits until the manager has seen [kind], so a later change counts as a transition from it. */
    private suspend fun seen(m: AccessManager, kind: String) = withTimeout(5_000) { m.access.first { it?.kind == kind } }.also { delay(100) }

    @Test
    fun aFreshInstallStartsWithNoAiAndAnUpgradeWithAKeyInByok() = runBlocking {
        assertEquals(AiAccess.None, manager().current())
        vault.put(ProviderType.OPENROUTER.secretName, "sk-or-mine")
        assertEquals(AiAccess.Byok, manager().current())
    }

    @Test
    fun theManagedKeyIsKeptApartFromTheUsersOwn() = runBlocking {
        vault.put(ProviderType.OPENROUTER.secretName, "sk-or-mine")
        val m = manager()
        assertEquals(AiAccess.Byok, m.current())
        backend.trial = AccessResult.Granted(trialGrant())
        assertNull(m.startTrial())
        assertTrue(m.current() is AiAccess.Trial)
        assertEquals("sk-managed", m.managedKey())
        assertEquals("sk-or-mine", vault.get(ProviderType.OPENROUTER.secretName))

        // BYOK and back: neither key is lost.
        m.useOwnKey()
        assertEquals(AiAccess.Byok, m.current())
        assertEquals("sk-managed", m.managedKey())
        assertTrue(m.useManaged())
        assertTrue(m.current() is AiAccess.Trial)
        assertEquals("sk-or-mine", vault.get(ProviderType.OPENROUTER.secretName))
    }

    @Test
    fun switchingBackWithoutAPlanSaysSo() = runBlocking {
        val m = manager()
        m.useOwnKey()
        assertFalse(m.useManaged())
        assertEquals(AiAccess.Byok, m.current())
    }

    @Test
    fun aTrialThatCantStartLeavesTranslationOff() = runBlocking {
        val m = manager()
        for (code in listOf(AccessErrorCode.TRIAL_ALREADY_USED, AccessErrorCode.DEVICE_NOT_SUPPORTED, AccessErrorCode.NOT_FROM_PLAY, AccessErrorCode.TRIALS_FULL)) {
            backend.trial = AccessResult.Failed(AccessError(code))
            assertEquals(code, m.startTrial()?.code)
            assertEquals(AiAccess.None, m.current())
        }
        backend.throwOnTrial = true
        assertEquals(AccessErrorCode.SERVICE, m.startTrial()?.code)
    }

    @Test
    fun theTrialEndsWhenItsAiIsSpentAndThePlansOpen() = runBlocking {
        val m = manager()
        backend.trial = AccessResult.Granted(trialGrant())
        m.startTrial()
        seen(m, "TRIAL")
        spent.value = 0.25
        m.afterManagedCall()
        assertEquals(50, m.allowance.first { it?.percent == 50 }?.percent)
        assertTrue(m.current() is AiAccess.Trial)
        spent.value = 0.5
        m.afterManagedCall()
        assertEquals(AiAccess.Ended(EndReason.TRIAL_USED_UP), m.current())
        assertEquals(EndReason.TRIAL_USED_UP, withTimeout(5_000) { m.pendingEnd.first { it != null } })
        assertTrue(backgroundEnds.isEmpty())
        m.acknowledgeEnd()
        assertNull(m.pendingEnd.value)
    }

    @Test
    fun anEndInTheBackgroundIsSignalledForANotification() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        seen(m, "SUBSCRIBED")
        foreground = false
        m.markEnded(EndReason.SUBSCRIPTION_ENDED)
        withTimeout(5_000) { while (backgroundEnds.isEmpty()) delay(20) }
        assertEquals(listOf(EndReason.SUBSCRIPTION_ENDED), backgroundEnds)
    }

    @Test
    fun keyLimitMeansUsedUpButOtherFailuresAreNeverAPaywall() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        val sub = m.current()
        // Unfamiliar 4xx/5xx and refusals: a service problem, mode unchanged.
        for ((status, kind) in listOf(500 to AiException.Kind.OTHER, 402 to AiException.Kind.OTHER, 404 to AiException.Kind.BAD_REQUEST, 429 to AiException.Kind.RATE_LIMIT, 403 to AiException.Kind.AUTH)) {
            assertNull(m.onManagedError(AiException("OpenRouter: nope", kind, httpStatus = status)))
        }
        assertEquals(sub, m.current())
        val block = m.onManagedError(AiException("OpenRouter: Key limit exceeded (total limit)", AiException.Kind.KEY_LIMIT, httpStatus = 403))
        assertEquals(AiBlock.AllowanceUsedUp(sub), block)
        assertTrue(m.allowanceNow()!!.usedUp)
        assertEquals(sub, m.current())
    }

    @Test
    fun anExpiredTrialKeyEndsTheTrial() = runBlocking {
        val m = manager()
        m.applyGrant(trialGrant(), activate = true)
        val block = m.onManagedError(AiException("OpenRouter: API key expired.", AiException.Kind.AUTH, httpStatus = 401))
        assertEquals(AiBlock.Ended(EndReason.TRIAL_EXPIRED), block)
        assertEquals(AiAccess.Ended(EndReason.TRIAL_EXPIRED), m.current())
    }

    @Test
    fun aUsedUpSubscriptionAsksTheBackendOnceForARenewal() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(remaining = 0.0), activate = true)
        backend.refresh = AccessResult.Granted(subGrant(remaining = 4.5).copy(key = null))
        assertFalse(m.allowanceUsedUp())
        assertEquals(1, backend.refreshes)
        assertEquals("sk-sub", m.managedKey())

        m.setBaseline { it.copy(remainingUsd = 0.0) }
        // Within a few minutes it doesn't ask again.
        assertTrue(m.allowanceUsedUp())
        assertEquals(1, backend.refreshes)
    }

    @Test
    fun aRefreshDoesntMoveAByokUserOffTheirKey() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        m.useOwnKey()
        m.applyGrant(subGrant(Tier.PLUS), activate = false)
        assertEquals(AiAccess.Byok, m.current())
        assertTrue(m.useManaged())
        assertEquals(Tier.PLUS, (m.current() as AiAccess.Subscribed).tier)
    }

    @Test
    fun aLapsedSubscriptionFoundOnRefreshEnds() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        backend.refresh = AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
        assertNull(m.refresh(force = true))
        assertEquals(AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED), m.current())
    }

    private class MapVault : KeyVault {
        private val map = HashMap<String, String>()
        override fun get(name: String) = map[name]
        override fun put(name: String, value: String?) {
            if (value.isNullOrBlank()) map.remove(name) else map[name] = value
        }
        fun has(name: String) = name in map
    }

    private class ScriptedBackend : AccessBackend {
        var trial: AccessResult = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        var throwOnTrial = false
        var refresh: AccessResult = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        var refreshes = 0
        override suspend fun startTrial(): AccessResult = if (throwOnTrial) throw java.io.IOException("offline") else trial
        override suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?) = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        override suspend fun restore(currentKeyHash: String?) = AccessResult.Failed(AccessError(AccessErrorCode.NO_PURCHASE))
        override suspend fun refresh(purchaseToken: String, keyHash: String?): AccessResult {
            refreshes++
            return refresh
        }
    }
}
