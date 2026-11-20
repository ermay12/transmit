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
import kotlinx.coroutines.cancelAndJoin
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
    private var check: AllowanceCheck = AllowanceCheck.Unavailable
    private var inUseElsewhereNotices = 0

    /** A manager on a fresh store, or on [file] again to stand for a restart (with its own scope). */
    private fun manager(file: java.io.File = java.io.File(tmp.root, "access${stores++}.preferences_pb"), scope: CoroutineScope = this.scope): AccessManager {
        val store = AccessStore(PreferenceDataStoreFactory.create(scope = scope) { file }) {
            vault.has(ProviderType.OPENROUTER.secretName)
        }
        return AccessManager(store, vault, backend, { check }, { spent }, scope, isForeground = { foreground }).also {
            it.listener = object : AccessListener {
                override fun onEndedInBackground(reason: EndReason) {
                    backgroundEnds += reason
                }

                override fun onInUseElsewhereInBackground() {
                    inUseElsewhereNotices++
                }
            }
        }
    }

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    @After fun tearDown() { scope.cancel() }

    private val refused = AiException("OpenRouter: User not found.", AiException.Kind.AUTH, httpStatus = 401)

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

    /** A Standard subscription renewing at [ends], with [remaining] of its $4.50 left; [key] null when the key is kept. */
    private fun sub(remaining: Double, ends: Long = periodEnd, key: String? = "sk-sub") =
        Grant(Entitlement.Subscription(Tier.STANDARD, ends), key, "subhash", 4.5, remaining, purchaseToken = "tok")

    private val periodEnd = System.currentTimeMillis() + 20 * day
    private val keyLimit = AiException("OpenRouter: Key limit exceeded (total limit)", AiException.Kind.KEY_LIMIT, httpStatus = 403)

    @Test
    fun aUsedUpSubscriptionAsksTheBackendOnceForARenewal() = runBlocking {
        val m = manager()
        m.applyGrant(sub(0.0), activate = true)
        backend.refresh = AccessResult.Granted(sub(4.5, ends = periodEnd + 30 * day, key = null))
        assertFalse(m.allowanceUsedUp())
        assertEquals(1, backend.refreshes)
        assertEquals("sk-sub", m.managedKey())
        assertEquals(0, m.allowanceNow()!!.percent)
    }

    /**
     * The device test: a refresh had just run, so the throttle would have skipped the check. A call that takes the
     * allowance to 100% asks the Worker at once anyway, once; every blocked call after that asks nothing.
     */
    @Test
    fun runningOutAsksTheWorkerOnceAtOncePastTheThrottle() = runBlocking {
        val m = manager()
        m.applyGrant(sub(0.5), activate = true)
        // OpenRouter's figure lags, so the Worker keeps reporting the 50 cents the last calls have spent.
        backend.refresh = AccessResult.Granted(sub(0.5, key = null))
        assertTrue(m.refresh(force = true) is RefreshResult.Answered)
        // An ordinary refresh now is skipped, and says so.
        assertEquals(RefreshResult.Skipped, m.refresh())
        assertEquals(1, backend.refreshes)

        spent.value = 0.5
        m.afterManagedCall()
        withTimeout(5_000) { m.usedUpPrompt.first { it != null } }
        assertEquals(2, backend.refreshes)
        // No change, so it stays used up: what OpenRouter reported covers calls up to a few minutes ago only.
        assertTrue(m.allowanceNow()!!.usedUp)
        repeat(3) { assertTrue(m.allowanceUsedUp()) }
        m.afterManagedCall()
        assertTrue(m.onManagedError(keyLimit) is AiBlock.AllowanceUsedUp)
        delay(200)
        assertEquals(2, backend.refreshes)

        // Below 100% again (a correction), then out again: that's a new time, with its own check.
        check = AllowanceCheck.Reading(2.0, System.currentTimeMillis())
        m.reconcile()
        withTimeout(5_000) { m.allowance.first { it?.usedUp == false } }
        delay(200)
        check = AllowanceCheck.UsedUp
        m.reconcile()
        withTimeout(5_000) { while (backend.refreshes < 3) delay(20) }
        delay(200)
        assertEquals(3, backend.refreshes)
    }

    /** The Worker reports a new period or a higher limit: no longer used up, with no dialog. */
    @Test
    fun aRenewalOrAHigherLimitFoundWhenItRunsOutClearsItSilently() = runBlocking {
        val m = manager()
        m.applyGrant(sub(0.5), activate = true)
        // A higher limit in the same period (OpenRouter's lag is covered, so it's real room).
        backend.refresh = AccessResult.Granted(sub(4.5, key = null))
        spent.value = 0.5
        m.afterManagedCall()
        withTimeout(5_000) { m.allowance.first { it?.usedUp == false } }
        assertEquals(1, backend.refreshes)
        assertFalse(m.allowanceUsedUp())
        delay(200)
        assertNull(m.usedUpPrompt.value)

        // A 403 in a period that has renewed meanwhile: no block, so the call runs again.
        backend.refresh = AccessResult.Granted(sub(4.5, ends = periodEnd + 30 * day, key = null))
        assertNull(m.onManagedError(keyLimit))
        assertEquals(2, backend.refreshes)
        assertFalse(m.allowanceNow()!!.usedUp)
        delay(200)
        assertNull(m.usedUpPrompt.value)
    }

    /** What a refresh did: skipped (throttled or nothing to ask), answered (and whether it renewed), or failed. */
    @Test
    fun aRefreshTellsSkippedFromNoChange() = runBlocking {
        val m = manager()
        assertEquals(RefreshResult.Skipped, m.refresh(force = true))
        m.applyGrant(sub(0.0), activate = true)
        backend.refresh = AccessResult.Granted(sub(0.0, key = null))
        assertEquals(RefreshResult.Answered(renewed = false), m.refresh(force = true))
        // Within the throttle: skipped, which is not the same as "asked, and nothing changed".
        assertEquals(RefreshResult.Skipped, m.refresh())
        assertEquals(1, backend.refreshes)
        backend.refresh = AccessResult.Granted(sub(4.5, key = null))
        assertEquals(RefreshResult.Answered(renewed = true), m.refresh(force = true))
        backend.refresh = AccessResult.Granted(sub(4.5, ends = periodEnd + 30 * day, key = null))
        assertEquals(RefreshResult.Answered(renewed = true), m.refresh(force = true))
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.NETWORK))
        assertEquals(RefreshResult.Failed(AccessError(AccessErrorCode.NETWORK)), m.refresh(force = true))
    }

    /** The dialog for a run-out month: once per allowance period, remembered across restarts, never for a free week. */
    @Test
    fun theUsedUpDialogShowsOncePerPeriodAcrossRestarts() = runBlocking {
        val file = java.io.File(tmp.root, "prompt.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val m = manager(file, first)
        m.applyGrant(sub(0.2), activate = true)
        backend.refresh = AccessResult.Granted(sub(0.2, key = null))
        assertNull(m.usedUpPrompt.value)
        spent.value = 0.2
        m.afterManagedCall()
        val due = withTimeout(5_000) { m.usedUpPrompt.first { it != null } }!!
        assertEquals(AiAccess.Subscribed(Tier.STANDARD, periodEnd), due.access)
        m.usedUpPromptShown()
        withTimeout(5_000) { m.usedUpPrompt.first { it == null } }
        first.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

        // After a restart: not shown again, and the blocked calls don't ask the Worker again either.
        val again = manager(file)
        withTimeout(5_000) { again.allowance.first { it?.usedUp == true } }
        assertTrue(again.allowanceUsedUp())
        again.afterManagedCall()
        delay(300)
        assertNull(again.usedUpPrompt.value)
        assertEquals(1, backend.refreshes)

        // A new period that runs out too: shown again.
        backend.refresh = AccessResult.Granted(sub(0.2, ends = periodEnd + 30 * day, key = null))
        again.applyGrant(sub(0.2, ends = periodEnd + 30 * day, key = null), activate = false)
        again.afterManagedCall()
        withTimeout(5_000) { again.usedUpPrompt.first { it != null } }

        // A free week that runs out ends instead (the plans open); no month-used dialog.
        val trial = manager()
        trial.applyGrant(trialGrant(0.0), activate = true)
        trial.afterManagedCall()
        assertEquals(AiAccess.Ended(EndReason.TRIAL_USED_UP), trial.current())
        delay(200)
        assertNull(trial.usedUpPrompt.value)
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
        assertEquals(RefreshResult.Answered(renewed = false), m.refresh(force = true))
        assertEquals(AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED), m.current())
    }

    @Test
    fun aDismissedWarningStaysHiddenAcrossRestartsUntilThePeriodResets() = runBlocking {
        val file = java.io.File(tmp.root, "restart.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val m = manager(file, first)
        val periodEnd = System.currentTimeMillis() + 20 * day
        val grant = subGrant().copy(entitlement = Entitlement.Subscription(Tier.STANDARD, periodEnd))
        m.applyGrant(grant, activate = true)
        spent.value = 3.9
        withTimeout(5_000) { m.allowance.first { it?.nearlyUsedUp == true } }
        assertFalse(m.warningDismissed.value)
        m.dismissWarning()
        withTimeout(5_000) { m.warningDismissed.first { it } }
        // DataStore lets the file be opened again only once the first store's scope has finished.
        first.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

        // After a restart it is still dismissed, and a daily refresh in the same period doesn't bring it back.
        val again = manager(file)
        assertTrue(withTimeout(5_000) { again.warningDismissed.first { it } })
        again.applyGrant(grant.copy(key = null), activate = false)
        delay(100)
        assertTrue(again.warningDismissed.value)

        // A renewal is a new period: the banner shows again.
        again.applyGrant(grant.copy(key = null, entitlement = Entitlement.Subscription(Tier.STANDARD, periodEnd + 30 * day)), activate = false)
        assertFalse(withTimeout(5_000) { again.warningDismissed.first { !it } })
    }

    @Test
    fun theTranslationOffStripStaysDismissedAcrossRestarts() = runBlocking {
        val file = java.io.File(tmp.root, "strip.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val m = manager(file, first)
        assertTrue(withTimeout(5_000) { m.offStripShown.first { it } })
        m.dismissOffStrip()
        withTimeout(5_000) { m.offStripShown.first { !it } }
        first.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

        // One flag for every chat: after a restart it's still hidden, with no AI set up.
        val again = manager(file)
        assertEquals(AiAccess.None, withTimeout(5_000) { again.access.first { it != null } })
        delay(200)
        assertFalse(again.offStripShown.value)
    }

    @Test
    fun theHomeTranslationOffBannerStaysDismissedAcrossRestarts() = runBlocking {
        val file = java.io.File(tmp.root, "banner.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val m = manager(file, first)
        assertEquals(AiAccess.None, withTimeout(5_000) { m.access.first { it != null } })
        assertFalse(m.offBannerDismissed.value)
        m.dismissOffBanner()
        withTimeout(5_000) { m.offBannerDismissed.first { it } }
        // Its own flag: the chats' strip is still there.
        assertTrue(withTimeout(5_000) { m.offStripShown.first { it } })
        first.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

        val again = manager(file)
        assertEquals(AiAccess.None, withTimeout(5_000) { again.access.first { it != null } })
        assertTrue(withTimeout(5_000) { again.offBannerDismissed.first { it } })
        assertTrue(withTimeout(5_000) { again.offStripShown.first { it } })
    }

    @Test
    fun theTranslationOffStripIsOnlyForNoAi() = runBlocking {
        val m = manager()
        assertTrue(withTimeout(5_000) { m.offStripShown.first { it } })
        backend.trial = AccessResult.Granted(trialGrant())
        assertNull(m.startTrial())
        withTimeout(5_000) { m.offStripShown.first { !it } }
        // Ended and BYOK users don't get it either: they've set AI up, and their chats say what to do instead.
        m.markEnded(EndReason.TRIAL_USED_UP)
        delay(200)
        assertFalse(m.offStripShown.value)
        m.useOwnKey()
        delay(200)
        assertFalse(m.offStripShown.value)
    }

    @Test
    fun aDismissalLastsOnlyUntilTheAllowanceRunsOut() = runBlocking {
        val m = manager()
        m.applyGrant(trialGrant(), activate = true)
        spent.value = 0.42
        withTimeout(5_000) { m.allowance.first { it?.nearlyUsedUp == true } }
        m.dismissWarning()
        withTimeout(5_000) { m.warningDismissed.first { it } }
        spent.value = 0.5
        withTimeout(5_000) { m.warningDismissed.first { !it } }
        // A correction back below 100% shows the 80% banner again.
        spent.value = 0.42
        delay(200)
        assertFalse(m.warningDismissed.value)
    }

    @Test
    fun aKeyAnotherDeviceTookIsNotTakenBackWithoutAsking() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        backend.onPlay = true
        assertEquals(AiBlock.InUseElsewhere, m.onManagedError(refused))
        // No Worker call: with this device's stale keyHash, it would rotate the other device's key away.
        assertEquals(0, backend.refreshes)
        val a = m.current() as AiAccess.Subscribed
        assertTrue(a.inUseElsewhere)
        assertEquals(AiBlock.InUseElsewhere, app.transmit.ai.AiGate.check(a, false, { false }, { app.transmit.ai.BudgetStatus.Ok }))
        // The daily refresh leaves it alone too.
        assertEquals(RefreshResult.Skipped, m.refresh(force = true))
        assertEquals(0, backend.refreshes)

        // "Use it here" restores with the old hash, so the Worker rotates the key to this device.
        backend.restore = AccessResult.Granted(subGrant().copy(key = "sk-sub-2", keyHash = "subhash2"))
        assertNull(m.useHere())
        assertEquals("subhash", backend.restoredWith)
        assertEquals("sk-sub-2", m.managedKey())
        assertFalse((m.current() as AiAccess.Subscribed).inUseElsewhere)
    }

    @Test
    fun aRefusedKeyThatPlayNoLongerListsAsksTheWorkerOnce() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        backend.onPlay = false
        backend.refresh = AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
        assertEquals(AiBlock.Ended(EndReason.SUBSCRIPTION_ENDED), m.onManagedError(refused))
        assertEquals(1, backend.refreshes)
        assertEquals(AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED), m.current())
    }

    @Test
    fun aBurstOfRefusedCallsLooksIntoItOnce() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        backend.onPlay = false
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.NETWORK))
        repeat(3) { assertNull(m.onManagedError(refused)) }
        assertEquals(1, backend.refreshes)
    }

    @Test
    fun theDailyRefreshFirstChecksTheKeyStillWorks() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        check = AllowanceCheck.Reading(3.0, System.currentTimeMillis())
        backend.refresh = AccessResult.Granted(subGrant().copy(key = null))
        assertTrue(m.refresh(force = true) is RefreshResult.Answered)
        assertEquals(1, backend.refreshes)

        check = AllowanceCheck.KeyRejected
        backend.onPlay = true
        foreground = false
        assertEquals(RefreshResult.Answered(renewed = false), m.refresh(force = true))
        assertEquals(1, backend.refreshes)
        assertTrue((m.current() as AiAccess.Subscribed).inUseElsewhere)
        assertEquals(1, inUseElsewhereNotices)
    }

    @Test
    fun aRefreshThatFindsTheKeyOnAnotherDeviceAsksInsteadOfTakingIt() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        // OpenRouter still takes the key (or couldn't be asked), but the Worker says it's another device's now.
        check = AllowanceCheck.Unavailable
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.KEY_IN_USE_ELSEWHERE))
        foreground = false
        assertEquals(RefreshResult.Answered(renewed = false), m.refresh(force = true))
        assertEquals(1, backend.refreshes)
        assertEquals("sk-sub", m.managedKey())
        assertTrue((m.current() as AiAccess.Subscribed).inUseElsewhere)
        assertEquals(1, inUseElsewhereNotices)
        // Marked: later refreshes leave it alone until "Use it here".
        assertEquals(RefreshResult.Skipped, m.refresh(force = true))
        assertEquals(1, backend.refreshes)

        // The same answer after a refused key, with Play unsure, in the foreground: marked, no notice.
        val other = manager()
        other.applyGrant(subGrant(), activate = true)
        backend.onPlay = false
        foreground = true
        assertEquals(AiBlock.InUseElsewhere, other.onManagedError(refused))
        assertTrue((other.current() as AiAccess.Subscribed).inUseElsewhere)
        assertEquals(1, inUseElsewhereNotices)
    }

    @Test
    fun aSwitchToTransmitAsksTheWorkerOnceItsDayHasCome() = runBlocking {
        val m = manager()
        val now = System.currentTimeMillis()
        fun plus(startsAt: Long) = Grant(
            Entitlement.Subscription(Tier.PLUS, startsAt, pendingSwitch = PlanSwitch(Tier.STANDARD, startsAt)), "sk-sub", "subhash", 9.0, 6.0, purchaseToken = "switch",
        )
        backend.refresh = AccessResult.Granted(
            Grant(Entitlement.Subscription(Tier.STANDARD, now + 30 * day), null, "subhash", 4.5, 4.5, purchaseToken = "switch"),
        )
        // Before the day: Plus, with Plus's allowance, and no extra call.
        m.applyGrant(plus(now + 3 * day), activate = true)
        m.reconcile()
        assertEquals(0, backend.refreshes)
        assertEquals(Tier.PLUS, (m.current() as AiAccess.Subscribed).tier)
        assertEquals(1.0 / 3, m.allowanceNow()!!.fraction, 1e-9)

        // The renewal has come: the Worker reports Transmit and its allowance, and the switch is gone.
        m.applyGrant(plus(now - 60_000), activate = true)
        m.reconcile()
        assertEquals(1, backend.refreshes)
        val after = m.current() as AiAccess.Subscribed
        assertEquals(Tier.STANDARD, after.tier)
        assertNull(after.pendingSwitch)
        assertEquals("sk-sub", m.managedKey())
        assertEquals(0.0, m.allowanceNow()!!.fraction, 1e-9)
    }

    @Test
    fun aReplacedTokenWithNothingNewerOnPlayLeavesThePlanAndKeyAsTheyAre() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(Tier.PLUS), activate = true)
        // What PlayAccessBackend answers when the old token is replaced and Play shows nothing newer yet.
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE, detail = "Replaced, and Play has no newer purchase"))
        assertEquals(AccessErrorCode.SERVICE, (m.refresh(force = true) as RefreshResult.Failed).error.code)
        // Still Plus, never ended: a replaced token is a plan change, not a lapse.
        assertEquals(Tier.PLUS, (m.current() as AiAccess.Subscribed).tier)
        assertEquals(1, backend.refreshes)
        assertEquals("sk-sub", m.managedKey())
        assertEquals("tok", m.purchaseToken())
    }

    @Test
    fun reconcilingNoticesAUsedUpOrRefusedTrialKey() = runBlocking {
        val m = manager()
        m.applyGrant(trialGrant(), activate = true)
        check = AllowanceCheck.Reading(0.2, System.currentTimeMillis())
        m.reconcile()
        assertEquals(60, m.allowanceNow()!!.percent)
        check = AllowanceCheck.UsedUp
        m.reconcile()
        assertEquals(AiAccess.Ended(EndReason.TRIAL_USED_UP), m.current())

        val other = manager()
        other.applyGrant(trialGrant(), activate = true)
        check = AllowanceCheck.KeyRejected
        other.reconcile()
        assertEquals(AiAccess.Ended(EndReason.TRIAL_EXPIRED), other.current())
    }

    @Test
    fun aTrialKeyRefusedWithAnyWordingEndsTheTrial() = runBlocking {
        val m = manager()
        m.applyGrant(trialGrant(), activate = true)
        assertEquals(AiBlock.Ended(EndReason.TRIAL_EXPIRED), m.onManagedError(refused))
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
        var onPlay: Boolean? = null
        var restore: AccessResult = AccessResult.Failed(AccessError(AccessErrorCode.NO_PURCHASE))
        var restoredWith: String? = null
        override suspend fun startTrial(): AccessResult = if (throwOnTrial) throw java.io.IOException("offline") else trial
        override suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?) = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        override suspend fun restore(currentKeyHash: String?): AccessResult {
            restoredWith = currentKeyHash
            return restore
        }
        override suspend fun activeOnPlay() = onPlay
        override suspend fun refresh(purchaseToken: String, keyHash: String?): AccessResult {
            refreshes++
            return refresh
        }
    }
}
