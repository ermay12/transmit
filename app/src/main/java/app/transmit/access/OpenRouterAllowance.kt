package app.transmit.access

import app.transmit.ai.num
import app.transmit.ai.obj
import app.transmit.ai.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The managed key's remaining allowance, from OpenRouter's `GET /api/v1/key`. */
class OpenRouterAllowance(
    private val http: OkHttpClient = WorkerApi.defaultHttp(),
    baseUrl: String = "https://openrouter.ai/api/v1",
    private val clock: () -> Long = System::currentTimeMillis,
) : AllowanceSource {
    private val base = baseUrl.trimEnd('/')

    override suspend fun read(managedKey: String): AllowanceCheck = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$base/key").header("Authorization", "Bearer $managedKey").get().build()
        try {
            http.newCall(request).execute().use { r -> interpret(r.code, r.body?.string().orEmpty(), clock()) }
        } catch (e: IOException) {
            AllowanceCheck.Unavailable
        }
    }

    companion object {
        /** OpenRouter books spending about this late, so a reading covers calls up to this long ago. */
        const val LAG_MS = 3 * 60_000L

        /**
         * 401 is the key itself being refused, whatever the wording ("API key expired.", "User not found."): the mode
         * decides what it means. Only a 403 that says the key limit is exceeded means used up; every other failure is a
         * service problem, never a paywall.
         */
        fun interpret(status: Int, body: String, now: Long): AllowanceCheck {
            val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            return when (status) {
                200 -> json?.get("data").obj()?.get("limit_remaining").num()
                    ?.let { AllowanceCheck.Reading(it.coerceAtLeast(0.0), now - LAG_MS) }
                    ?: AllowanceCheck.Unavailable
                401 -> AllowanceCheck.KeyRejected
                403 -> {
                    val message = json?.get("error")?.let { it.obj()?.get("message").str() ?: it.str() } ?: body
                    if (message.contains("key limit exceeded", ignoreCase = true)) AllowanceCheck.UsedUp else AllowanceCheck.Unavailable
                }
                else -> AllowanceCheck.Unavailable
            }
        }
    }
}
