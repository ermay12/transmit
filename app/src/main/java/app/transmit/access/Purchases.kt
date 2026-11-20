package app.transmit.access

import android.app.Activity
import android.util.Log
import app.transmit.data.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Purchase tokens that still have to reach the Worker, kept (encrypted) until it has answered for them. Play refunds
 * a purchase the Worker hasn't acknowledged within 3 days, so a token is only dropped once it has been delivered, or
 * can never be.
 */
class PurchaseQueue(private val vault: KeyVault) {
    private val lock = Any()

    fun all(): List<PlayPurchase> = synchronized(lock) { decode(vault.get(NAME)) }

    fun add(p: PlayPurchase) = synchronized(lock) {
        val list = decode(vault.get(NAME))
        if (list.none { it.purchaseToken == p.purchaseToken }) vault.put(NAME, encode(list + p))
    }

    fun remove(token: String) = synchronized(lock) {
        val list = decode(vault.get(NAME))
        val left = list.filterNot { it.purchaseToken == token }
        if (left.size != list.size) vault.put(NAME, if (left.isEmpty()) null else encode(left))
    }

    private fun decode(s: String?): List<PlayPurchase> = s?.let {
        runCatching {
            Json.parseToJsonElement(it).jsonArray.map { e ->
                val o = e.jsonObject
                PlayPurchase(o["productId"]!!.jsonPrimitive.content, o["token"]!!.jsonPrimitive.content)
            }
        }.getOrNull()
    }.orEmpty()

    private fun encode(list: List<PlayPurchase>) =
        JsonArray(list.map { JsonObject(mapOf("productId" to JsonPrimitive(it.productId), "token" to JsonPrimitive(it.purchaseToken))) }).toString()

    private companion object {
        const val NAME = "purchase_queue"
    }
}

/** Where a purchase is, for the plans screen. */
sealed interface PurchaseUi {
    data object Idle : PurchaseUi
    /** Play's sheet is open or the Worker is issuing the key. */
    data object Working : PurchaseUi
    /** Paid with a method that takes a while (cash, bank transfer); the AI starts once Play confirms it. */
    data object Pending : PurchaseUi
    data class Done(val tier: Tier) : PurchaseUi
    /** A switch to Transmit is set: Plus and its allowance stay until [at] (null if unknown), then it's Transmit. */
    data class SwitchSet(val at: Long?) : PurchaseUi
    /**
     * Buying or changing the plan didn't work. [error] is the Worker's, or null when Google Play itself refused or
     * failed (its sheet's error, which the user may not even have seen explained). [planChange]: it was an upgrade or
     * a switch to Transmit, not a new purchase.
     */
    data class Failed(val error: AccessError?, val planChange: Boolean = false) : PurchaseUi
}

/**
 * Buying through Play and getting the key for it. Every PURCHASED token goes into the [queue] and is sent to the
 * Worker right away; one that can't be sent yet ([AccessError.retryable]) waits for [scheduleRetry]'s background job
 * and for the next launch. PENDING purchases wait for Play's next update.
 */
class Purchases(
    private val store: Storefront,
    private val access: AccessManager,
    private val queue: PurchaseQueue,
    private val scope: CoroutineScope,
    /**
     * Play's purchase sheet has closed, whatever its outcome, or it didn't open: the plan may have changed (see
     * [PlanRefresher], [RefreshTrigger.PURCHASE_FLOW]). Called once what the sheet returned has been handled.
     */
    private val onFlowReturned: () -> Unit = {},
    /** Schedules a background delivery with backoff, once a network is available. */
    private val scheduleRetry: () -> Unit,
) {
    private val _state = MutableStateFlow<PurchaseUi>(PurchaseUi.Idle)
    val state: StateFlow<PurchaseUi> = _state
    private val delivering = Mutex()
    /** What Play's sheet was last opened for, so its outcome can say what happened. */
    @Volatile private var launched: PurchasePlan? = null

    val available get() = store.available

    /** Tokens still waiting for the Worker. */
    fun hasQueued() = queue.all().isNotEmpty()

    init {
        scope.launch { store.updates.collect { onUpdate(it) } }
    }

    suspend fun offers(): Map<Tier, PlanOffer>? = store.offers()

    /**
     * What buying [tier] would mean now: a new subscription, an upgrade, a switch to Transmit, or one already owned.
     * A switch the Worker says is set comes from the app's state, since Play still lists Plus; nothing more is
     * offered until it has happened. Nor is a switch offered on a Plus whose renewal is off.
     */
    suspend fun planFor(tier: Tier): PurchasePlan {
        val held = access.heldSubscription()
        // For a subscriber, a plain purchase while Play can't be asked could start a second subscription.
        val owned = store.purchases() ?: return if (held != null) PurchasePlan.NotOffered else PurchasePlan.New
        return PurchasePlan.choose(tier, owned, held?.pendingSwitch?.tier, access.purchaseToken(), canceled = held?.autoRenewing == false)
    }

    /**
     * Opens Play's sheet for [tier]: an upgrade when the account has Standard, or a switch to Transmit (from Plus)
     * when [tier] is Standard.
     */
    suspend fun buy(activity: Activity, tier: Tier): LaunchResult {
        _state.value = PurchaseUi.Working
        val plan = planFor(tier)
        launched = plan
        val result = store.launch(activity, tier, plan)
        if (result != LaunchResult.LAUNCHED) {
            launched = null
            _state.value = PurchaseUi.Idle
            // Nothing to buy may mean the app's view is out of date (a switch that has already happened, say).
            onFlowReturned()
        }
        return result
    }

    fun resetState() {
        if (_state.value !is PurchaseUi.Working) _state.value = PurchaseUi.Idle
    }

    private suspend fun onUpdate(u: PurchaseUpdate) {
        handle(u)
        onFlowReturned()
    }

    private suspend fun handle(u: PurchaseUpdate) {
        when (u) {
            is PurchaseUpdate.Purchases -> {
                val bought = u.purchases.filter { it.purchased && it.tier != null }
                bought.forEach { queue.add(PlayPurchase(it.productId, it.purchaseToken)) }
                when {
                    bought.isNotEmpty() -> {
                        _state.value = PurchaseUi.Working
                        val error = deliver()
                        _state.value = outcome(error, takeLaunched())
                    }
                    u.purchases.isNotEmpty() -> _state.value = PurchaseUi.Pending
                    else -> _state.value = PurchaseUi.Idle
                }
            }
            PurchaseUpdate.Canceled -> {
                takeLaunched()
                _state.value = PurchaseUi.Idle
            }
            PurchaseUpdate.AlreadyOwned -> {
                takeLaunched()
                // Most likely bought earlier and never delivered: look for it.
                sync()
                _state.value = PurchaseUi.Idle
            }
            is PurchaseUpdate.Failed -> {
                Log.w(TAG, "Purchase failed: ${u.responseCode} ${u.message}")
                _state.value = PurchaseUi.Failed(null, planChange = takeLaunched().isPlanChange)
            }
        }
    }

    private fun takeLaunched(): PurchasePlan? = launched.also { launched = null }

    /** An upgrade or a switch to Transmit, rather than a new subscription. */
    private val PurchasePlan?.isPlanChange get() = this is PurchasePlan.Change

    /** What the plans screen says once the Worker has the new token. */
    private suspend fun outcome(error: AccessError?, plan: PurchasePlan?): PurchaseUi {
        val a = access.current() as? AiAccess.Subscribed
        if (error != null || a == null) return PurchaseUi.Failed(error, plan.isPlanChange)
        return when (plan) {
            is PurchasePlan.Downgrade -> PurchaseUi.SwitchSet(a.pendingSwitch?.startsAt ?: a.periodEndsAt)
            else -> PurchaseUi.Done(a.tier)
        }
    }

    /** On launch and daily: purchases Play hasn't seen acknowledged yet are queued again, then the queue is sent. */
    suspend fun sync(): AccessError? {
        queueUnacknowledged()
        return deliver()
    }

    /**
     * Play's side of a plan refresh ([PlanRefresher]): a purchase Play lists that the Worker hasn't acknowledged and
     * that isn't queued yet (bought or resubscribed outside the app, say) is sent now. Tokens already waiting keep to
     * their background retry, so a refresh right after a failed delivery doesn't send it again at once.
     */
    suspend fun syncNew(): AccessError? {
        val fresh = queueUnacknowledged()
        return if (fresh.isEmpty()) null else deliver(only = fresh)
    }

    /** Queues Play's PURCHASED Transmit purchases the Worker hasn't acknowledged; returns the tokens newly queued. */
    private suspend fun queueUnacknowledged(): Set<String> {
        val before = queue.all().mapTo(HashSet()) { it.purchaseToken }
        store.purchases()?.filter { it.purchased && it.tier != null && !it.acknowledged }
            ?.forEach { queue.add(PlayPurchase(it.productId, it.purchaseToken)) }
        return queue.all().mapTo(HashSet()) { it.purchaseToken } - before
    }

    /**
     * Sends every queued token (or those in [only]). Returns the last error, if one is still waiting to be retried
     * (then a background retry is scheduled).
     */
    suspend fun deliver(only: Set<String>? = null): AccessError? = delivering.withLock {
        var waiting: AccessError? = null
        var failed: AccessError? = null
        val tried = HashSet<String>()
        while (true) {
            val next = queue.all().firstOrNull { it.purchaseToken !in tried && (only == null || it.purchaseToken in only) } ?: break
            tried += next.purchaseToken
            val error = access.activatePurchase(next)
            if (error == null || !error.retryable) queue.remove(next.purchaseToken)
            if (error != null) {
                Log.w(TAG, "Purchase delivery: ${error.code} ${error.detail}")
                failed = error
                if (error.retryable) waiting = error
            }
        }
        if (waiting != null) scheduleRetry()
        failed
    }

    private companion object {
        const val TAG = "Purchases"
    }
}
