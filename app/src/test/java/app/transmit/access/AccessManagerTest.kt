package app.transmit.access

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.transmit.ai.AiBlock
import app.transmit.ai.AiException
import app.transmit.ai.ProviderType
import app.transmit.data.KeyVault
import app.transmit.ui.access.usedUpStrip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    /**
     * A manager on a fresh store, or on [file] again to stand for a restart (with its own scope). [lag] is how late
     * OpenRouter's figures are; 0 where a test's readings are taken as of now.
     */
    private fun manager(
        file: java.io.File = java.io.File(tmp.root, "access${stores++}.preferences_pb"),
        scope: CoroutineScope = this.scope,
        lag: Long = OpenRouterAllowance.LAG_MS,
        clock: () -> Long = System::currentTimeMillis,
        grace: Long = AccessManager.PERIOD_END_GRACE_MS,
        retry: Long = AccessManager.PERIOD_END_RETRY_MS,
    ): AccessManager {
        val store = AccessStore(PreferenceDataStoreFactory.create(scope = scope) { file }) {
            vault.has(ProviderType.OPENROUTER.secretName)
        }
        return AccessManager(
            store, vault, backend, { check }, { spent }, scope, isForeground = { foreground }, clock = clock,
            usageLagMs = lag, periodEndGraceMs = grace, periodEndRetryMs = retry,
        ).also {
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
        var now = System.currentTimeMillis()
        val m = manager(clock = { now })
        m.applyGrant(subGrant(), activate = true)
        // OpenRouter's 403 counts once it's newer than the Worker's last answer (see anOlderOpenRouterReading…).
        now += OpenRouterAllowance.LAG_MS
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
        // OpenRouter's readings here are as of now, with no lag, so each one is newer than the Worker's last answer.
        val m = manager(lag = 0)
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
        var now = System.currentTimeMillis()
        val m = manager(clock = { now })
        m.applyGrant(sub(0.5), activate = true)
        // A higher limit in the same period, from an order the Worker just credited (OpenRouter's lag is covered, so
        // it's real room).
        backend.refresh = AccessResult.Granted(sub(4.5, key = null).copy(allowanceRenewed = true))
        spent.value = 0.5
        m.afterManagedCall()
        withTimeout(5_000) { m.allowance.first { it?.usedUp == false } }
        assertEquals(1, backend.refreshes)
        assertFalse(m.allowanceUsedUp())
        delay(200)
        assertNull(m.usedUpPrompt.value)

        // A 403 in a period that has renewed meanwhile (a while after the Worker last answered): no block, so the
        // call runs again.
        now += OpenRouterAllowance.LAG_MS + 60_000
        backend.refresh = AccessResult.Granted(sub(4.5, ends = periodEnd + 30 * day, key = null))
        assertNull(m.onManagedError(keyLimit))
        assertEquals(2, backend.refreshes)
        assertFalse(m.allowanceNow()!!.usedUp)
        delay(200)
        assertNull(m.usedUpPrompt.value)
    }

    /** A 403 just after the Worker found room (here a renewal) may predate it: a service problem, not used up. */
    @Test
    fun aForbiddenCallJustAfterTheWorkerFoundRoomIsAServiceProblem() = runBlocking {
        var now = System.currentTimeMillis()
        val m = manager(clock = { now })
        m.applyGrant(sub(0.0), activate = true)
        now += OpenRouterAllowance.LAG_MS + 60_000
        backend.refresh = AccessResult.Granted(sub(4.5, ends = periodEnd + 30 * day, key = null).copy(allowanceRenewed = true))
        assertFalse(m.allowanceUsedUp())
        assertEquals(1, backend.refreshes)

        now += 30_000
        assertNull(m.onManagedError(keyLimit))
        assertFalse(m.allowanceNow()!!.usedUp)
        assertFalse(m.allowanceUsedUp())
        assertEquals(1, backend.refreshes)
        delay(200)
        assertNull(m.usedUpPrompt.value)
    }

    /**
     * At 100% the Worker is asked, and answers "no change": the limit stands, so a 403 minutes later means used up and
     * the strip shows, even where the Worker's figure (OpenRouter's, which lags) still showed a little room.
     */
    @Test
    fun aForbiddenCallAfterTheWorkerSaidNoChangeIsUsedUp() = runBlocking {
        for (left in listOf(0.0, 0.05)) {
            var now = System.currentTimeMillis()
            val m = manager(clock = { now })
            m.applyGrant(sub(left), activate = true)
            now += OpenRouterAllowance.LAG_MS + 60_000
            val before = backend.refreshes
            backend.refresh = AccessResult.Granted(sub(left, key = null))
            assertEquals(RefreshResult.Answered(renewed = false), m.refresh(force = true))

            now += 30_000
            val block = m.onManagedError(keyLimit)
            assertTrue("$left left", block is AiBlock.AllowanceUsedUp)
            assertTrue(usedUpStrip((block as AiBlock.AllowanceUsedUp).access, canBuy = true)!!.upgrade)
            assertTrue(m.allowanceNow()!!.usedUp)
            // Every blocked call after that is used up too, with no more questions.
            val asked = backend.refreshes
            repeat(3) { assertTrue(m.allowanceUsedUp()) }
            assertTrue(m.onManagedError(keyLimit) is AiBlock.AllowanceUsedUp)
            assertEquals(asked, backend.refreshes)
            assertTrue(asked - before <= 2)
        }
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
        backend.refresh = AccessResult.Granted(sub(4.5, key = null).copy(allowanceRenewed = true))
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

    /**
     * Only the Worker says a key is another device's: a 401 asks it with `"rotate": false`, and its `key_mismatch`
     * marks the subscription. The key isn't taken back without asking.
     */
    @Test
    fun aKeyAnotherDeviceTookIsNotTakenBackWithoutAsking() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.KEY_IN_USE_ELSEWHERE))
        assertEquals(AiBlock.InUseElsewhere, m.onManagedError(refused))
        assertEquals(1, backend.refreshes)
        assertEquals("subhash", backend.refreshedWith)
        val a = m.current() as AiAccess.Subscribed
        assertTrue(a.inUseElsewhere)
        assertEquals(AiBlock.InUseElsewhere, app.transmit.ai.AiGate.check(a, false, { false }, { app.transmit.ai.BudgetStatus.Ok }))

        // "Use it here" restores with the old hash, so the Worker rotates the key to this device.
        backend.restore = AccessResult.Granted(subGrant().copy(key = "sk-sub-2", keyHash = "subhash2"))
        assertNull(m.useHere())
        assertEquals("subhash", backend.restoredWith)
        assertEquals("sk-sub-2", m.managedKey())
        assertFalse((m.current() as AiAccess.Subscribed).inUseElsewhere)
    }

    /**
     * The beta6 device test: the subscription expired and the Worker turned its key off. Coming back, OpenRouter
     * refuses the key; Play's cache still listed the subscription, which made the app say "in use on another device"
     * without asking anyone. Now the Worker is asked, says it expired, and it's ended, with the plans to follow.
     */
    @Test
    fun aLapsedSubscriptionShowsEndedNeverInUseElsewhere() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        seen(m, "SUBSCRIBED")
        check = AllowanceCheck.KeyRejected
        backend.refresh = AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
        m.reconcile()
        assertEquals(1, backend.refreshes)
        assertEquals(AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED), m.current())
        assertEquals(EndReason.SUBSCRIPTION_ENDED, withTimeout(5_000) { m.pendingEnd.first { it != null } })
        assertEquals(0, inUseElsewhereNotices)
    }

    /** The mark never stops a refresh: each asks the Worker again, so a subscription that has since lapsed ends. */
    @Test
    fun inUseElsewhereIsCheckedAgainOnEveryRefresh() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.KEY_IN_USE_ELSEWHERE))
        foreground = false
        assertEquals(RefreshResult.Answered(renewed = false), m.refresh(force = true))
        assertTrue((m.current() as AiAccess.Subscribed).inUseElsewhere)
        assertEquals(1, inUseElsewhereNotices)
        // Asked again, and still on the other device: no second notice.
        assertEquals(RefreshResult.Answered(renewed = false), m.refresh(force = true))
        assertEquals(2, backend.refreshes)
        assertEquals(1, inUseElsewhereNotices)

        // The other device's subscription has since expired: ended, no longer "in use elsewhere".
        backend.refresh = AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
        assertEquals(RefreshResult.Answered(renewed = false), m.refresh(force = true))
        assertEquals(AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED), m.current())

        // Or it came back to this device's key: a 200 for `"rotate": false` means the key is this device's.
        val other = manager()
        other.applyGrant(subGrant(), activate = true)
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.KEY_IN_USE_ELSEWHERE))
        other.refresh(force = true)
        backend.refresh = AccessResult.Granted(subGrant().copy(key = null))
        other.refresh(force = true)
        assertFalse((other.current() as AiAccess.Subscribed).inUseElsewhere)
        assertEquals("sk-sub", other.managedKey())
    }

    @Test
    fun aRefusedKeyAsksTheWorkerWhichSaysItEnded() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        backend.refresh = AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
        assertEquals(AiBlock.Ended(EndReason.SUBSCRIPTION_ENDED), m.onManagedError(refused))
        assertEquals(1, backend.refreshes)
        assertEquals(AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED), m.current())
    }

    @Test
    fun aBurstOfRefusedCallsLooksIntoItOnce() = runBlocking {
        val m = manager()
        m.applyGrant(subGrant(), activate = true)
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.NETWORK))
        repeat(3) { assertNull(m.onManagedError(refused)) }
        assertEquals(1, backend.refreshes)
        // A Worker that couldn't be asked marks nothing.
        assertFalse((m.current() as AiAccess.Subscribed).inUseElsewhere)
    }

    /**
     * The daily refresh goes straight to the Worker: OpenRouter isn't read first (that read came just before the
     * Worker changed the limit, and OpenRouter then answered with the old limit for a while).
     */
    @Test
    fun aRefreshAsksTheWorkerWithoutReadingOpenRouterFirst() = runBlocking {
        var reads = 0
        val counting = AccessManager(
            AccessStore(PreferenceDataStoreFactory.create(scope = scope) { java.io.File(tmp.root, "reads.preferences_pb") }) { false },
            vault, backend, { reads++; AllowanceCheck.KeyRejected }, { spent }, scope,
        )
        counting.applyGrant(subGrant(), activate = true)
        backend.refresh = AccessResult.Granted(subGrant().copy(key = null))
        assertTrue(counting.refresh(force = true) is RefreshResult.Answered)
        assertEquals(0, reads)
        assertEquals(1, backend.refreshes)
        assertFalse((counting.current() as AiAccess.Subscribed).inUseElsewhere)
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
        backend.refresh = AccessResult.Failed(AccessError(AccessErrorCode.TOKEN_REPLACED, detail = "Replaced, and Play has no newer purchase"))
        assertEquals(AccessErrorCode.TOKEN_REPLACED, (m.refresh(force = true) as RefreshResult.Failed).error.code)
        // Still Plus, never ended: a replaced token is a plan change, not a lapse.
        assertEquals(Tier.PLUS, (m.current() as AiAccess.Subscribed).tier)
        assertEquals(1, backend.refreshes)
        assertEquals("sk-sub", m.managedKey())
        assertEquals("tok", m.purchaseToken())

        // But once OpenRouter refuses the key too, nothing gives AI any more: ended.
        assertEquals(AiBlock.Ended(EndReason.SUBSCRIPTION_ENDED), m.onManagedError(refused))
        assertEquals(AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED), m.current())
    }

    // ---- the period's end, while the app is open (beta6: Home said "subscribed" for 10+ minutes after it lapsed) --

    /** Runs [AccessManager.watchPeriodEnd] as the app does while in the foreground, until [block] is done. */
    private suspend fun watching(m: AccessManager, busy: () -> Boolean = { false }, block: suspend () -> Unit) {
        val job = scope.launch { m.watchPeriodEnd(busy) }
        try {
            block()
        } finally {
            job.cancelAndJoin()
        }
    }

    private suspend fun refreshesReach(n: Int) = withTimeout(5_000) { while (backend.refreshes < n) delay(10) }

    @Test
    fun aLapseShowsShortlyAfterThePeriodEndsWhileTheAppIsOpen() = runBlocking {
        val m = manager(grace = 150, retry = 300)
        val ends = System.currentTimeMillis() + 200
        m.applyGrant(sub(4.5, ends = ends), activate = true)
        seen(m, "SUBSCRIBED")
        backend.refresh = AccessResult.Ended(EndReason.SUBSCRIPTION_ENDED)
        watching(m) {
            refreshesReach(1)
            // Not before the end plus the grace.
            assertTrue(System.currentTimeMillis() >= ends + 150)
            withTimeout(5_000) { m.access.first { it is AiAccess.Ended } }
            assertEquals(EndReason.SUBSCRIPTION_ENDED, withTimeout(5_000) { m.pendingEnd.first { it != null } })
            delay(600)
            // Ended: nothing more to ask.
            assertEquals(1, backend.refreshes)
        }
    }

    @Test
    fun aPeriodEndThatStillLooksActiveIsAskedAboutOnceMore() = runBlocking {
        val m = manager(grace = 100, retry = 300)
        val ends = System.currentTimeMillis() + 100
        m.applyGrant(sub(4.5, ends = ends), activate = true)
        // Play renews late: the Worker still reports the period that has just ended.
        backend.refresh = AccessResult.Granted(sub(4.5, ends = ends, key = null))
        watching(m) {
            refreshesReach(2)
            delay(800)
            // The check and one retry; after that the daily refresh takes over.
            assertEquals(2, backend.refreshes)
        }
        // Back in the foreground later (the watch starts again): this period end has had its checks.
        watching(m) {
            delay(300)
            assertEquals(2, backend.refreshes)
        }
    }

    @Test
    fun comingBackAfterThePeriodEndedAsksAtOnceAndARenewalStopsTheChecks() = runBlocking {
        val m = manager(grace = 100, retry = 300)
        m.applyGrant(sub(0.0, ends = System.currentTimeMillis() - 10 * 60_000), activate = true)
        assertTrue(m.allowanceNow()!!.usedUp)
        // The renewal came while the app was in the background: a new period, and the used-up state goes.
        backend.refresh = AccessResult.Granted(sub(4.5, ends = periodEnd, key = null))
        watching(m) {
            refreshesReach(1)
            withTimeout(5_000) { m.allowance.first { it?.usedUp == false } }
            delay(600)
            assertEquals(1, backend.refreshes)
        }
    }

    @Test
    fun theWatchWaitsWhilePlaysPurchaseSheetIsOpen() = runBlocking {
        val m = manager(grace = 50, retry = 300)
        m.applyGrant(sub(4.5, ends = System.currentTimeMillis() - 60_000), activate = true)
        backend.refresh = AccessResult.Granted(sub(4.5, ends = periodEnd, key = null))
        watching(m, busy = { true }) {
            delay(300)
            assertEquals(0, backend.refreshes)
        }
    }

    // ---- the allowance never goes back in time (beta6: 85% after a renewal, 100% after the limit was raised) ------

    @Test
    fun anOlderOpenRouterReadingNeverOverwritesTheWorkersNewerAnswer() = runBlocking {
        var now = System.currentTimeMillis()
        val m = manager(clock = { now })
        // 85% used.
        m.applyGrant(sub(0.675), activate = true)
        assertEquals(85, m.allowanceNow()!!.percent)
        // A renewal from the Worker: a new period.
        backend.refresh = AccessResult.Granted(sub(4.5, ends = periodEnd + 30 * day, key = null))
        assertEquals(RefreshResult.Answered(renewed = true), m.refresh(force = true))
        assertEquals(0, m.allowanceNow()!!.percent)

        // Back to Home 17 s later: OpenRouter's /key still has the limit from before the renewal.
        now += 17_000
        check = AllowanceCheck.Reading(0.675, now - OpenRouterAllowance.LAG_MS)
        m.reconcile()
        assertEquals(0, m.allowanceNow()!!.percent)
        // A 403 seen through that same old limit is ignored too: nothing blocked, and the Worker isn't asked again.
        assertNull(m.onManagedError(keyLimit))
        assertEquals(0, m.allowanceNow()!!.percent)
        assertEquals(1, backend.refreshes)

        // Once OpenRouter's figures are newer than the Worker's answer, they count.
        now += OpenRouterAllowance.LAG_MS
        check = AllowanceCheck.Reading(0.045, now - OpenRouterAllowance.LAG_MS)
        m.reconcile()
        assertEquals(99, m.allowanceNow()!!.percent)
        // And an older one than that doesn't undo it.
        check = AllowanceCheck.Reading(4.5, now - OpenRouterAllowance.LAG_MS - 60_000)
        m.reconcile()
        assertEquals(99, m.allowanceNow()!!.percent)
    }

    /** A relaunch whose newest reading has room clears the used-up state: no banner, no dialog, its own next check. */
    @Test
    fun aRelaunchClearsTheUsedUpStateOnceTheNewestFigureHasRoom() = runBlocking {
        val file = java.io.File(tmp.root, "usedup.preferences_pb")
        val first = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var now = System.currentTimeMillis()
        val m = manager(file, first, clock = { now })
        m.applyGrant(sub(0.2), activate = true)
        backend.refresh = AccessResult.Granted(sub(0.2, key = null))
        spent.value = 0.2
        m.afterManagedCall()
        withTimeout(5_000) { m.usedUpPrompt.first { it != null } }
        first.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()

        // The limit was raised meanwhile; minutes later the app is opened again and OpenRouter has caught up.
        now += OpenRouterAllowance.LAG_MS + 60_000
        val again = manager(file, clock = { now })
        withTimeout(5_000) { again.allowance.first { it?.usedUp == true } }
        check = AllowanceCheck.Reading(4.0, now - OpenRouterAllowance.LAG_MS)
        again.reconcile()
        withTimeout(5_000) { again.allowance.first { it?.usedUp == false } }
        assertFalse(again.allowanceUsedUp())
        delay(200)
        assertNull(again.usedUpPrompt.value)
        assertEquals(1, backend.refreshes)
    }

    @Test
    fun reconcilingNoticesAUsedUpOrRefusedTrialKey() = runBlocking {
        val m = manager(lag = 0)
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
        override fun has(name: String) = name in map
    }

    private class ScriptedBackend : AccessBackend {
        var trial: AccessResult = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        var throwOnTrial = false
        @Volatile var refresh: AccessResult = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        @Volatile var refreshes = 0
        var refreshedWith: String? = null
        var restore: AccessResult = AccessResult.Failed(AccessError(AccessErrorCode.NO_PURCHASE))
        var restoredWith: String? = null
        override suspend fun startTrial(): AccessResult = if (throwOnTrial) throw java.io.IOException("offline") else trial
        override suspend fun activatePurchase(purchase: PlayPurchase, currentKeyHash: String?) = AccessResult.Failed(AccessError(AccessErrorCode.SERVICE))
        override suspend fun restore(currentKeyHash: String?): AccessResult {
            restoredWith = currentKeyHash
            return restore
        }
        override suspend fun refresh(purchaseToken: String, keyHash: String?): AccessResult {
            refreshedWith = keyHash
            refreshes++
            return refresh
        }
    }
}
