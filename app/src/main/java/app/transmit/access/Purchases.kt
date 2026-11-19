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
    data class Failed(val error: AccessError?) : PurchaseUi
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
    /** Schedules a background delivery with backoff, once a network is available. */
    private val scheduleRetry: () -> Unit,
) {
    private val _state = MutableStateFlow<PurchaseUi>(PurchaseUi.Idle)
    val state: StateFlow<PurchaseUi> = _state
    private val delivering = Mutex()

    val available get() = store.available

    /** Tokens still waiting for the Worker. */
    fun hasQueued() = queue.all().isNotEmpty()

    init {
        scope.launch { store.updates.collect { onUpdate(it) } }
    }

    suspend fun offers(): Map<Tier, PlanOffer>? = store.offers()

    /** What buying [tier] would mean now (a new subscription, an upgrade, or one already owned). */
    suspend fun planFor(tier: Tier): PurchasePlan = PurchasePlan.choose(tier, store.purchases().orEmpty())

    /** Opens Play's sheet for [tier], as an upgrade when the account has Standard. */
    suspend fun buy(activity: Activity, tier: Tier): LaunchResult {
        _state.value = PurchaseUi.Working
        val result = store.launch(activity, tier, planFor(tier))
        if (result != LaunchResult.LAUNCHED) _state.value = PurchaseUi.Idle
        return result
    }

    fun resetState() {
        if (_state.value !is PurchaseUi.Working) _state.value = PurchaseUi.Idle
    }

    private suspend fun onUpdate(u: PurchaseUpdate) {
        when (u) {
            is PurchaseUpdate.Purchases -> {
                val bought = u.purchases.filter { it.purchased && it.tier != null }
                bought.forEach { queue.add(PlayPurchase(it.productId, it.purchaseToken)) }
                when {
                    bought.isNotEmpty() -> {
                        _state.value = PurchaseUi.Working
                        val error = deliver()
                        val tier = (access.current() as? AiAccess.Subscribed)?.tier
                        _state.value = if (error == null && tier != null) PurchaseUi.Done(tier) else PurchaseUi.Failed(error)
                    }
                    u.purchases.isNotEmpty() -> _state.value = PurchaseUi.Pending
                    else -> _state.value = PurchaseUi.Idle
                }
            }
            PurchaseUpdate.Canceled -> _state.value = PurchaseUi.Idle
            PurchaseUpdate.AlreadyOwned -> {
                // Most likely bought earlier and never delivered: look for it.
                sync()
                _state.value = PurchaseUi.Idle
            }
            is PurchaseUpdate.Failed -> {
                Log.w(TAG, "Purchase failed: ${u.responseCode} ${u.message}")
                _state.value = PurchaseUi.Failed(null)
            }
        }
    }

    /** On launch and daily: purchases Play hasn't seen acknowledged yet are queued again, then the queue is sent. */
    suspend fun sync(): AccessError? {
        store.purchases()?.filter { it.purchased && it.tier != null && !it.acknowledged }
            ?.forEach { queue.add(PlayPurchase(it.productId, it.purchaseToken)) }
        return deliver()
    }

    /**
     * Sends every queued token. Returns the last error, if one is still waiting to be retried (then a background
     * retry is scheduled).
     */
    suspend fun deliver(): AccessError? = delivering.withLock {
        var waiting: AccessError? = null
        var failed: AccessError? = null
        val tried = HashSet<String>()
        while (true) {
            val next = queue.all().firstOrNull { it.purchaseToken !in tried } ?: break
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
