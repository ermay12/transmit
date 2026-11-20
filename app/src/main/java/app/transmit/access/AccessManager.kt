package app.transmit.access

import android.util.Log
import app.transmit.ai.AiBlock
import app.transmit.ai.AiException
import app.transmit.data.KeyVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor

/** How much of the managed AI is used, as users see it: a percentage, never dollars. */
data class AllowanceView(val fraction: Double, val resetsAt: Long?) {
    val percent: Int get() = floor(fraction * 100).toInt().coerceIn(0, 100)
    val usedUp get() = fraction >= 1.0
    val nearlyUsedUp get() = fraction >= WARN_AT

    companion object {
        /** The Home banner shows from here. */
        const val WARN_AT = 0.8
    }
}

/** What [AccessManager.refresh] did, so a caller can tell a call that was skipped from one that changed nothing. */
sealed interface RefreshResult {
    /** Not asked: within the throttle, or nothing to ask about (no subscription, ended, in use elsewhere). */
    data object Skipped : RefreshResult

    /**
     * The Worker (or Play) answered, and what it said is stored. [renewed]: a new allowance period (a renewal, an
     * upgrade), or an allowance that was used up has room again (a higher limit).
     */
    data class Answered(val renewed: Boolean) : RefreshResult

    /** The Worker couldn't be asked, or failed. */
    data class Failed(val error: AccessError) : RefreshResult
}

/**
 * Owns [AiAccess]: what it is, how it changes, and the managed key. The backend and Play are behind
 * [AccessBackend]; the allowance is counted from the usage ledger ([managedSpend], each call's `usage.cost`)
 * against the last reading from the backend or [AllowanceSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccessManager(
    private val store: AccessStore,
    private val vault: KeyVault,
    private val backend: AccessBackend,
    private val allowanceSource: AllowanceSource,
    /** Managed spending logged since a time. */
    private val managedSpend: (since: Long) -> Flow<Double>,
    private val scope: CoroutineScope,
    /** Whether a screen of the app is showing, so the end of a trial can open the plans screen. */
    private val isForeground: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    /** False to hold off noticing transitions until [start], once [listener] is set. */
    autoStart: Boolean = true,
    /** How late OpenRouter books spending, so a figure from it (through the Worker too) covers calls up to then. */
    private val usageLagMs: Long = OpenRouterAllowance.LAG_MS,
) {
    var listener: AccessListener = object : AccessListener {}

    /** Re-reads the mode now and then, so a trial ends on time while the app is open. */
    private val ticks = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    /** Null until loaded. */
    val access: StateFlow<AiAccess?> = combine(store.state, ticks) { s, _ -> s.resolve(clock()) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Null outside managed modes, or before the backend first reported the allowance. */
    val allowance: StateFlow<AllowanceView?> = combine(store.state, access.filterNotNull()) { s, a -> s.baseline to a }
        .distinctUntilChanged()
        .flatMapLatest { (b, a) ->
            if (b == null || !a.isManaged) flowOf(null)
            else managedSpend(b.at).map { spent -> AllowanceView(b.usedFraction(spent), (a as? AiAccess.Subscribed)?.renewsAt) }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _pendingEnd = MutableStateFlow<EndReason?>(null)
    /** A trial or subscription that just ended, for the UI to open the plans screen; cleared by [acknowledgeEnd]. */
    val pendingEnd: StateFlow<EndReason?> = _pendingEnd

    /**
     * The Home "nearly used up" banner was dismissed. It stays hidden, across restarts, until the allowance period
     * resets (a renewal, an upgrade) or the allowance reaches 100%.
     */
    val warningDismissed: StateFlow<Boolean> = store.state.map { it.warningDismissed }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Whether chats show the slim "Translation is off" strip: only with no AI set up, and only until it's dismissed.
     * A dismissal hides it in every chat, across restarts; Home and Settings keep their own way to turn AI on.
     */
    val offStripShown: StateFlow<Boolean> = combine(store.state, access.filterNotNull()) { s, a -> a == AiAccess.None && !s.offStripDismissed }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Home's "Translation is off" banner was dismissed. Someone who only wants Transmit for messaging can put it
     * away for good, across restarts; the Settings plan card always keeps a way to turn translation on.
     */
    val offBannerDismissed: StateFlow<Boolean> = store.state.map { it.offBannerDismissed }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * "You've used this month's AI", for a chat to show once per allowance period: a subscription ran out, the Worker
     * was asked once in case it had renewed ([limitReached]), and it hadn't. Null otherwise, and once
     * [usedUpPromptShown] has recorded it for this period (across restarts). A trial that runs out ends instead.
     */
    val usedUpPrompt: StateFlow<AiBlock.AllowanceUsedUp?> = combine(store.state, access.filterNotNull(), allowance) { s, a, v ->
        (a as? AiAccess.Subscribed)?.takeIf {
            !it.inUseElsewhere && v?.usedUp == true && s.allowancePeriod != null &&
                s.limitCheckedFor == s.allowancePeriod && s.usedUpPromptedFor != s.allowancePeriod
        }?.let { AiBlock.AllowanceUsedUp(it) }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val refreshLock = Mutex()
    private var lastRefreshAt = 0L
    /** One check at a time when the allowance runs out. */
    private val limitLock = Mutex()
    /** When a refused subscription key was last looked into, so a burst of failed calls checks once. */
    private var keyCheckedAt = 0L

    private val started = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        if (autoStart) started.complete(Unit)
        scope.launch {
            store.migrate()
            started.await()
            access.filterNotNull().collect { noticeTransition(it) }
        }
        scope.launch {
            allowance.filterNotNull().collect { v ->
                if (v.usedUp) {
                    // Used up: the dismissal is spent, so the 80% banner shows again if a correction brings it back below 100%.
                    store.update { s -> if (s.warningDismissedFor != null) s.copy(warningDismissedFor = null) else s }
                } else if (store.current().limitCheckedFor != null && allowanceNow()?.usedUp == false) {
                    // Below 100% again: the next time it runs out gets its own check.
                    store.update { s -> s.copy(limitCheckedFor = null) }
                }
            }
        }
    }

    /** Starts noticing transitions (an end found at launch, say) for [listener]. */
    fun start() {
        started.complete(Unit)
    }

    suspend fun dismissWarning() = store.update { it.copy(warningDismissedFor = it.allowancePeriod) }

    suspend fun dismissOffStrip() = store.update { it.copy(offStripDismissed = true) }

    suspend fun dismissOffBanner() = store.update { it.copy(offBannerDismissed = true) }

    /** The used-up dialog was shown: not again this allowance period. */
    suspend fun usedUpPromptShown() = store.update { it.copy(usedUpPromptedFor = it.allowancePeriod) }

    suspend fun current(): AiAccess = store.current().resolve(clock())

    fun managedKey(): String? = vault.get(MANAGED_KEY)?.takeIf { it.isNotBlank() }

    /** The Play purchase token the subscription's key belongs to, as the Worker last confirmed it. */
    fun purchaseToken(): String? = vault.get(PURCHASE_TOKEN)?.takeIf { it.isNotBlank() }

    /** The subscription the app holds, in whichever mode the user picked; null once it has ended. */
    suspend fun heldSubscription(): Entitlement.Subscription? =
        store.current().takeIf { it.ended == null }?.entitlement as? Entitlement.Subscription

    fun acknowledgeEnd() {
        _pendingEnd.value = null
    }

    private suspend fun noticeTransition(a: AiAccess) {
        val was = store.current().lastKind
        if (was == a.kind) return
        store.update { it.copy(lastKind = a.kind) }
        if (a is AiAccess.Ended && (was == "TRIAL" || was == "SUBSCRIBED")) {
            _pendingEnd.value = a.reason
            if (!isForeground()) listener.onEndedInBackground(a.reason)
        }
        if (a !is AiAccess.Ended) _pendingEnd.value = null
    }

    // ---- getting access ------------------------------------------------------------------------

    /** Null on success: the mode is TRIAL. */
    suspend fun startTrial(): AccessError? = handle(activate = true) { backend.startTrial() }

    /** For a purchase from Play Billing. Null on success: the mode is SUBSCRIBED. */
    suspend fun activatePurchase(purchase: PlayPurchase): AccessError? {
        val s = store.current()
        val hash = s.keyHash.takeIf { s.entitlement is Entitlement.Subscription }
        return handle(activate = true) { backend.activatePurchase(purchase, hash) }
    }

    /** "Restore purchase". Null when the backend answered: SUBSCRIBED, or ENDED for a lapsed subscription. */
    suspend fun restore(): AccessError? {
        val s = store.current()
        val hash = s.keyHash.takeIf { s.entitlement is Entitlement.Subscription }
        return handle(activate = true) { backend.restore(hash) }
    }

    /**
     * Re-checks the subscription with the backend, at most every few minutes unless [force]d.
     *
     * A refresh never takes the key from another device: it sends `"rotate": false`, and a `key_mismatch` marks
     * the subscription in use elsewhere (see [backgroundRefresh]). The key is also checked with OpenRouter first,
     * which is cheap and covers a Worker that predates `rotate`; a refused key goes through [refusedLocked].
     */
    suspend fun refresh(force: Boolean = false): RefreshResult = refreshLock.withLock {
        val s = store.current()
        if (s.entitlement !is Entitlement.Subscription || s.ended != null || s.inUseElsewhere) return RefreshResult.Skipped
        val token = vault.get(PURCHASE_TOKEN) ?: return RefreshResult.Skipped
        if (!force && clock() - lastRefreshAt < REFRESH_INTERVAL_MS) return RefreshResult.Skipped
        lastRefreshAt = clock()
        val wasUsedUp = allowanceNow()?.usedUp == true
        val key = managedKey()
        val error = if (key != null && read(key) == AllowanceCheck.KeyRejected) refusedLocked(token, s) else backgroundRefresh(token, s)
        if (error != null) return RefreshResult.Failed(error)
        val after = store.current()
        RefreshResult.Answered(renewed = after.allowancePeriod != s.allowancePeriod || (wasUsedUp && allowanceNow()?.usedUp == false))
    }

    /**
     * `POST /v1/subscription` with `"rotate": false`, for calls the user didn't ask for. If the key is on another
     * device the Worker changes nothing and answers `key_mismatch`: mark it so the app asks before taking it back.
     */
    private suspend fun backgroundRefresh(token: String, s: StoredAccess): AccessError? {
        val error = handle(activate = false) { backend.refresh(token, s.keyHash) }
        if (error?.code != AccessErrorCode.KEY_IN_USE_ELSEWHERE) return error
        markInUseElsewhere()
        return null
    }

    private suspend fun markInUseElsewhere() {
        store.update { it.copy(inUseElsewhere = true) }
        if (!isForeground()) listener.onInUseElsewhereInBackground()
    }

    private suspend fun read(key: String): AllowanceCheck = try {
        allowanceSource.read(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Allowance check failed", e)
        AllowanceCheck.Unavailable
    }

    /**
     * OpenRouter refused the subscription key. If Play still lists the subscription as paid up, another device
     * took the key: mark it so the app asks before taking it back (`backend/API.md`). Otherwise it lapsed or was
     * refunded, and a refresh lets the Worker say so. Returns the block for the call that failed, if any.
     */
    private suspend fun subscriptionKeyRefused(): AiBlock? {
        refreshLock.withLock {
            val s = store.current()
            if (s.entitlement is Entitlement.Subscription && s.ended == null && !s.inUseElsewhere) {
                val token = vault.get(PURCHASE_TOKEN)
                if (token != null && clock() - keyCheckedAt >= REFRESH_INTERVAL_MS) refusedLocked(token, s)
            }
        }
        return when (val a = current()) {
            is AiAccess.Subscribed -> if (a.inUseElsewhere) AiBlock.InUseElsewhere else null
            is AiAccess.Ended -> AiBlock.Ended(a.reason)
            else -> null
        }
    }

    private suspend fun refusedLocked(token: String, s: StoredAccess): AccessError? {
        keyCheckedAt = clock()
        return when (backend.activeOnPlay()) {
            true -> {
                markInUseElsewhere()
                null
            }
            false -> backgroundRefresh(token, s)
            // Play can't be asked now: a service problem; the next failure or refresh looks again.
            null -> AccessError(AccessErrorCode.SERVICE, detail = "Play purchases unavailable", retryable = true)
        }
    }

    /** "Use it here": takes the subscription's key back from the other device. Null on success. */
    suspend fun useHere(): AccessError? = restore()

    private suspend fun handle(activate: Boolean, call: suspend () -> AccessResult): AccessError? {
        val result = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Access backend failed", e)
            AccessResult.Failed(AccessError(AccessErrorCode.SERVICE, detail = e.toString()))
        }
        return when (result) {
            is AccessResult.Granted -> {
                applyGrant(result.grant, activate)
                null
            }
            is AccessResult.Ended -> {
                markEnded(result.reason)
                null
            }
            is AccessResult.Failed -> result.error
        }
    }

    /**
     * Stores a key from the backend. [activate] switches to it (a trial started, a purchase); a background refresh
     * leaves a BYOK user on their own key.
     */
    suspend fun applyGrant(g: Grant, activate: Boolean) {
        g.key?.let { vault.put(MANAGED_KEY, it) }
        if (g.entitlement is Entitlement.Subscription) g.purchaseToken?.let { vault.put(PURCHASE_TOKEN, it) }
        store.update {
            val next = it.copy(entitlement = g.entitlement)
            // The same key in the same period: what's left is OpenRouter's figure, which lags, so the calls logged in
            // that time count on top (as in [reconcile]). Otherwise a used-up allowance would look free again.
            val samePeriod = g.key == null && it.allowancePeriod != null && next.allowancePeriod == it.allowancePeriod
            next.copy(
                ownKey = if (activate) false else it.ownKey,
                ended = null,
                keyHash = g.keyHash ?: it.keyHash.takeIf { g.key == null },
                baseline = AllowanceBaseline(g.allowanceUsd, g.remainingUsd, if (samePeriod) clock() - usageLagMs else clock()),
                // A key from the Worker works here, whichever device had it before.
                inUseElsewhere = false,
            )
        }
        when (val e = g.entitlement) {
            is Entitlement.Trial -> listener.onTrialGranted(e.endsAt)
            is Entitlement.Subscription -> listener.onSubscriptionGranted()
        }
    }

    suspend fun markEnded(reason: EndReason) = store.update { it.copy(ended = reason) }

    // ---- choosing ------------------------------------------------------------------------------

    /** BYOK. The managed key stays stored for a switch back. */
    suspend fun useOwnKey() = store.update { it.copy(ownKey = true) }

    /** Back to the trial or subscription. False when there's none that still gives AI (then show the plans). */
    suspend fun useManaged(): Boolean {
        if (!store.current().hasActiveEntitlement(clock())) return false
        store.update { it.copy(ownKey = false) }
        return true
    }

    /** "Not now": no AI for now. */
    suspend fun turnOff() = store.update { it.copy(ownKey = false) }

    // ---- allowance -----------------------------------------------------------------------------

    suspend fun allowanceNow(): AllowanceView? {
        val s = store.current()
        val a = s.resolve(clock())
        val b = s.baseline ?: return null
        if (!a.isManaged) return null
        return AllowanceView(b.usedFraction(managedSpend(b.at).first()), (a as? AiAccess.Subscribed)?.renewsAt)
    }

    /** Before a managed call. The first time a subscription is found used up, the Worker is asked ([limitReached]). */
    suspend fun allowanceUsedUp(): Boolean {
        val a = allowanceNow() ?: return false
        if (!a.usedUp) return false
        return limitReached()
    }

    /**
     * After each managed call: a trial whose AI is spent ends; a subscription that just ran out asks the Worker
     * ([limitReached], without holding up the result), and one near its end checks for a renewal.
     */
    suspend fun afterManagedCall() {
        val a = allowanceNow() ?: return
        when (current()) {
            is AiAccess.Trial -> if (a.usedUp) markEnded(EndReason.TRIAL_USED_UP)
            is AiAccess.Subscribed -> when {
                a.usedUp -> scope.launch { limitReached() }
                a.fraction >= REFRESH_AT -> scope.launch { refresh() }
            }
            else -> Unit
        }
    }

    /**
     * A subscription's allowance is used up (the ledger after a call, a 403, OpenRouter's figure). Once each time it
     * runs out, the Worker is asked at once, past [refresh]'s throttle, in case a new period or a higher limit has
     * come: then it's no longer used up, without a word. If not, that's recorded (across restarts) and nothing more is
     * asked until it's below 100% again; [usedUpPrompt] then has the dialog. Returns whether it's still used up.
     */
    private suspend fun limitReached(): Boolean = limitLock.withLock {
        if (allowanceNow()?.usedUp != true) return false
        if (current() !is AiAccess.Subscribed) return true
        val s = store.current()
        if (s.limitCheckedFor == s.allowancePeriod) return true
        refresh(force = true)
        // Whatever the answer (renewed, no change, failed, skipped), this was the check for this time.
        val usedUp = allowanceNow()?.usedUp == true
        if (usedUp) store.update { it.copy(limitCheckedFor = it.allowancePeriod) }
        usedUp
    }

    /**
     * Corrects the local tally with OpenRouter's (lagging) figure, and notices a key that stopped working. Once a
     * switch to Transmit is due, it first asks the Worker for the new plan and allowance (at most every few minutes).
     */
    suspend fun reconcile() {
        if ((current() as? AiAccess.Subscribed)?.pendingSwitch?.let { clock() >= it.startsAt } == true) refresh()
        val key = managedKey() ?: return
        val a = current()
        if (!a.isManaged) return
        when (val check = read(key)) {
            is AllowanceCheck.Reading -> {
                setBaseline { b -> b.copy(remainingUsd = check.remainingUsd, at = check.at) }
                afterManagedCall()
            }
            AllowanceCheck.UsedUp -> {
                setBaseline { b -> b.copy(remainingUsd = 0.0, at = clock()) }
                afterManagedCall()
            }
            AllowanceCheck.KeyRejected -> keyRefused(a)
            AllowanceCheck.Unavailable -> Unit
        }
    }

    /** A 401 for the managed key: a trial is over; a subscription key was taken or turned off. */
    private suspend fun keyRefused(a: AiAccess): AiBlock? = when (a) {
        is AiAccess.Trial -> {
            markEnded(EndReason.TRIAL_EXPIRED)
            AiBlock.Ended(EndReason.TRIAL_EXPIRED)
        }
        is AiAccess.Subscribed -> subscriptionKeyRefused()
        else -> null
    }

    suspend fun setBaseline(block: (AllowanceBaseline) -> AllowanceBaseline) =
        store.update { s -> s.copy(baseline = block(s.baseline ?: AllowanceBaseline(0.0, 0.0, clock()))) }

    /**
     * A managed call failed. Returns the block to show when the failure means the allowance is spent or the trial is
     * over; null for anything else, which is a service problem and never a paywall. A 403 for a subscription asks the
     * Worker once ([limitReached]): when it reports a renewal or a higher limit, it's null, and the call may run again.
     */
    suspend fun onManagedError(e: AiException): AiBlock? {
        val a = current()
        if (!a.isManaged) return null
        return when {
            e.kind == AiException.Kind.KEY_LIMIT -> {
                setBaseline { it.copy(remainingUsd = 0.0, at = clock()) }
                when {
                    a is AiAccess.Trial -> {
                        markEnded(EndReason.TRIAL_USED_UP)
                        AiBlock.AllowanceUsedUp(a)
                    }
                    limitReached() -> AiBlock.AllowanceUsedUp(a)
                    else -> null
                }
            }
            // By status, never by wording: "API key expired." and "User not found." are both a refused key.
            e.httpStatus == 401 -> keyRefused(a)
            else -> null
        }
    }

    /** Debug builds only: edits the stored state directly. */
    suspend fun debugEdit(block: (StoredAccess) -> StoredAccess) {
        store.update { block(it).copy(warningDismissedFor = null) }
    }

    companion object {
        private const val TAG = "Access"
        const val MANAGED_KEY = "managed_openrouter_key"
        const val PURCHASE_TOKEN = "managed_purchase_token"
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L
        /** Past this, a subscription asks the backend whether it has renewed. */
        private const val REFRESH_AT = 0.9
    }
}
