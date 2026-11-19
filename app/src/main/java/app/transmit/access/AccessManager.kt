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
import kotlinx.coroutines.flow.filter
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

    private val refreshLock = Mutex()
    private var lastRefreshAt = 0L
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
        // Used up: the dismissal is spent, so the 80% banner shows again if a correction brings it back below 100%.
        scope.launch {
            allowance.filterNotNull().filter { it.usedUp }.collect {
                store.update { s -> if (s.warningDismissedFor != null) s.copy(warningDismissedFor = null) else s }
            }
        }
    }

    /** Starts noticing transitions (an end found at launch, say) for [listener]. */
    fun start() {
        started.complete(Unit)
    }

    suspend fun dismissWarning() = store.update { it.copy(warningDismissedFor = it.allowancePeriod) }

    suspend fun current(): AiAccess = store.current().resolve(clock())

    fun managedKey(): String? = vault.get(MANAGED_KEY)?.takeIf { it.isNotBlank() }

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
     * The Worker rotates a key whose hash doesn't match, so refreshing with a key another device has since taken
     * would silently take it back (and the two devices would keep taking it from each other). So the key is first
     * checked with OpenRouter, and a refused key goes through [subscriptionKeyRefused] instead.
     */
    suspend fun refresh(force: Boolean = false): AccessError? = refreshLock.withLock {
        val s = store.current()
        if (s.entitlement !is Entitlement.Subscription || s.ended != null || s.inUseElsewhere) return null
        val token = vault.get(PURCHASE_TOKEN) ?: return null
        if (!force && clock() - lastRefreshAt < REFRESH_INTERVAL_MS) return null
        lastRefreshAt = clock()
        val key = managedKey()
        if (key != null && read(key) == AllowanceCheck.KeyRejected) {
            refusedLocked(token, s)
            return null
        }
        handle(activate = false) { backend.refresh(token, s.keyHash) }
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

    private suspend fun refusedLocked(token: String, s: StoredAccess) {
        keyCheckedAt = clock()
        when (backend.activeOnPlay()) {
            true -> {
                store.update { it.copy(inUseElsewhere = true) }
                if (!isForeground()) listener.onInUseElsewhereInBackground()
            }
            false -> handle(activate = false) { backend.refresh(token, s.keyHash) }
            // Play can't be asked now: a service problem; the next failure or refresh looks again.
            null -> Unit
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
            it.copy(
                ownKey = if (activate) false else it.ownKey,
                entitlement = g.entitlement,
                ended = null,
                keyHash = g.keyHash ?: it.keyHash.takeIf { g.key == null },
                baseline = AllowanceBaseline(g.allowanceUsd, g.remainingUsd, clock()),
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

    /** Before a managed call. A subscription used up asks the backend once, in case it has renewed. */
    suspend fun allowanceUsedUp(): Boolean {
        val a = allowanceNow() ?: return false
        if (!a.usedUp) return false
        if (current() is AiAccess.Subscribed && refresh() == null) return allowanceNow()?.usedUp ?: false
        return true
    }

    /** After each managed call: a trial whose AI is spent ends; a subscription near its end checks for a renewal. */
    suspend fun afterManagedCall() {
        val a = allowanceNow() ?: return
        when (current()) {
            is AiAccess.Trial -> if (a.usedUp) markEnded(EndReason.TRIAL_USED_UP)
            is AiAccess.Subscribed -> if (a.fraction >= REFRESH_AT) scope.launch { refresh() }
            else -> Unit
        }
    }

    /** Corrects the local tally with OpenRouter's (lagging) figure, and notices a key that stopped working. */
    suspend fun reconcile() {
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
     * over; null for anything else, which is a service problem and never a paywall.
     */
    suspend fun onManagedError(e: AiException): AiBlock? {
        val a = current()
        if (!a.isManaged) return null
        return when {
            e.kind == AiException.Kind.KEY_LIMIT -> {
                setBaseline { it.copy(remainingUsd = 0.0, at = clock()) }
                if (a is AiAccess.Trial) markEnded(EndReason.TRIAL_USED_UP)
                AiBlock.AllowanceUsedUp(a)
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
