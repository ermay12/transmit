package app.transmit.access

import app.transmit.ai.JSON_MEDIA
import app.transmit.ai.bool
import app.transmit.ai.long
import app.transmit.ai.num
import app.transmit.ai.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** One answer from the Worker (`backend/API.md`). */
sealed interface WorkerReply {
    data class Ok(val body: JsonObject) : WorkerReply

    /**
     * The Worker's error, branched on by [error] rather than [status]. [error] is null when the body wasn't the
     * Worker's JSON at all (a proxy's error page, say).
     */
    data class Error(
        val status: Int,
        val error: String?,
        val reason: String? = null,
        val state: String? = null,
        val retryAfterSeconds: Long? = null,
    ) : WorkerReply

    /** No connection, or the call timed out. */
    data class Offline(val cause: IOException) : WorkerReply

    /** What to do with a purchase token this reply was for. */
    val delivery: Delivery
        get() = when (this) {
            is Ok -> Delivery.DONE
            is Offline -> Delivery.RETRY
            is Error -> when {
                error == "purchase_not_active" && state == "replaced" -> Delivery.USE_NEWER_TOKEN
                // Play already said PURCHASED; the Worker's view of Google lags a moment.
                error == "purchase_not_active" && state == "pending" -> Delivery.RETRY
                error in AccessErrorCode.RETRYABLE -> Delivery.RETRY
                // Not the Worker talking (a proxy's 5xx page): the Worker may not have seen the request.
                error == null && status >= 500 -> Delivery.RETRY
                // purchase_invalid, bad_request, an ended purchase, anything unknown: sending it again won't help.
                else -> Delivery.DONE
            }
        }

    /** The result for the app, with [grant] reading a 200's body (null when it doesn't make sense). */
    fun toResult(grant: (JsonObject) -> Grant?): AccessResult = when (this) {
        is Ok -> grant(body)?.let { AccessResult.Granted(it) }
            ?: AccessResult.Failed(AccessError(AccessErrorCode.SERVICE, detail = "Unreadable Worker response"))
        is Offline -> AccessResult.Failed(AccessError(AccessErrorCode.NETWORK, detail = cause.toString(), retryable = true))
        is Error -> when (val r = AccessErrorCode.resultFor(error, reason, state, retryAfterSeconds)) {
            is AccessResult.Failed -> AccessResult.Failed(r.error.copy(retryable = delivery == Delivery.RETRY, detail = r.error.detail.orEmpty().ifEmpty { "HTTP $status" }))
            else -> r
        }
    }
}

/** What happens to a purchase token after a call: delivered (or never deliverable), try again, or use the newer one. */
enum class Delivery { DONE, RETRY, USE_NEWER_TOKEN }

/** The Worker's two calls. Each takes up to about 30 s: the Worker calls Google and OpenRouter in turn. */
class WorkerApi(private val http: OkHttpClient = defaultHttp(), baseUrl: String = BASE_URL) {
    private val base = baseUrl.trimEnd('/')

    suspend fun trial(androidId: String, integrityToken: String): WorkerReply = post(
        "/v1/trial",
        buildJsonObject {
            put("androidId", androidId)
            put("integrityToken", integrityToken)
        },
    )

    /** [keyHash]: the subscription key held now (kept when it matches, else rotated). [androidId]: after a purchase. */
    suspend fun subscription(purchaseToken: String, productId: String?, keyHash: String?, androidId: String?): WorkerReply = post(
        "/v1/subscription",
        buildJsonObject {
            put("purchaseToken", purchaseToken)
            productId?.let { put("productId", it) }
            keyHash?.let { put("keyHash", it) }
            androidId?.let { put("androidId", it) }
        },
    )

    private suspend fun post(path: String, body: JsonObject): WorkerReply = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base + path).post(body.toString().toRequestBody(JSON_MEDIA)).build()
        try {
            http.newCall(request).execute().use { r -> parse(r.code, r.header("Retry-After"), r.body?.string().orEmpty()) }
        } catch (e: IOException) {
            WorkerReply.Offline(e)
        }
    }

    companion object {
        const val BASE_URL = "https://transmit.ermay12.workers.dev"

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()

        fun parse(status: Int, retryAfterHeader: String?, body: String): WorkerReply {
            val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = json?.get("error").str()
            if (status in 200..299 && json != null && error == null) return WorkerReply.Ok(json)
            return WorkerReply.Error(
                status = status,
                error = error,
                reason = json?.get("reason").str(),
                state = json?.get("state").str(),
                retryAfterSeconds = json?.get("retryAfterSeconds").long() ?: retryAfterHeader?.trim()?.toLongOrNull(),
            )
        }

        /** A `/v1/trial` 200. */
        fun trialGrant(b: JsonObject): Grant? {
            val expiresAt = instant(b["expiresAt"].str()) ?: return null
            val allowance = b["allowanceUsd"].num() ?: return null
            val key = b["key"].str()?.takeIf { it.isNotBlank() } ?: return null
            return Grant(Entitlement.Trial(expiresAt), key, b["keyHash"].str(), allowance, b["remainingUsd"].num() ?: allowance)
        }

        /** A `/v1/subscription` 200 for [purchaseToken]. `key` comes only with `newKey: true`. */
        fun subscriptionGrant(b: JsonObject, purchaseToken: String): Grant? {
            val tier = Tier.fromProductId(b["productId"].str()) ?: return null
            val allowance = b["allowanceUsd"].num() ?: return null
            val newKey = b["newKey"].bool() ?: (b["key"] != null)
            val key = if (newKey) b["key"].str()?.takeIf { it.isNotBlank() } ?: return null else null
            return Grant(
                Entitlement.Subscription(tier, instant(b["periodEndsAt"].str()), autoRenewing = b["autoRenewing"].bool() ?: true),
                key, b["keyHash"].str(), allowance, b["remainingUsd"].num() ?: allowance, purchaseToken = purchaseToken,
            )
        }

        private fun instant(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}
