package app.transmit.access

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Proof for `/v1/trial` that this is the Play copy of Transmit on a genuine device. */
interface TrialAttestation {
    /** Gets ready ahead of time (onboarding step 3, the plans screen), so starting the trial is quicker. */
    fun warm() {}

    /** A fresh token bound to [requestHash]. Throws [AttestationException] when none can be had. */
    suspend fun token(requestHash: String): String

    companion object {
        /** `backend/API.md`: lowercase hex SHA-256 of "transmit-trial-v1:" + ANDROID_ID. */
        fun requestHash(androidId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("transmit-trial-v1:$androidId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

class AttestationException(val error: AccessError, cause: Throwable? = null) : Exception(error.detail, cause)

/**
 * Play Integrity's standard request. The token provider is prepared lazily (or by [warm]) and kept; a provider that
 * fails is prepared again once. The token itself is requested right before `/v1/trial`, since the Worker rejects
 * tokens older than 10 minutes.
 */
class PlayIntegrity(
    context: Context,
    private val scope: CoroutineScope,
    private val cloudProjectNumber: Long = CLOUD_PROJECT_NUMBER,
) : TrialAttestation {
    private val appContext = context.applicationContext
    private val manager by lazy { IntegrityManagerFactory.createStandard(appContext) }
    private var prepared: Deferred<StandardIntegrityTokenProvider>? = null

    override fun warm() {
        provider(fresh = false)
    }

    private fun provider(fresh: Boolean): Deferred<StandardIntegrityTokenProvider> = synchronized(this) {
        val current = prepared
        val failed = current != null && current.isCompleted && current.getCompletionExceptionOrNull() != null
        if (!fresh && current != null && !failed) return current
        scope.async {
            manager.prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build()).await()
        }.also { prepared = it }
    }

    override suspend fun token(requestHash: String): String {
        val request = StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()
        return try {
            provider(fresh = false).await().request(request).await().token()
        } catch (e: CancellationException) {
            throw e
        } catch (first: Exception) {
            Log.w(TAG, "Integrity token failed; preparing the provider again", first)
            try {
                provider(fresh = true).await().request(request).await().token()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Integrity token failed again", e)
                throw AttestationException(errorFor(e), e)
            }
        }
    }

    companion object {
        private const val TAG = "Integrity"
        /** Google Cloud project `transmit-515111`. */
        const val CLOUD_PROJECT_NUMBER = 36162671718L

        /** No Play Store or Play services means the trial can't run on this device; a network blip is just that. */
        fun errorFor(e: Throwable): AccessError {
            val code = (e as? StandardIntegrityException)?.errorCode
            val mapped = when (code) {
                StandardIntegrityErrorCode.NETWORK_ERROR -> AccessErrorCode.NETWORK
                StandardIntegrityErrorCode.PLAY_STORE_NOT_FOUND,
                StandardIntegrityErrorCode.PLAY_SERVICES_NOT_FOUND,
                StandardIntegrityErrorCode.API_NOT_AVAILABLE,
                StandardIntegrityErrorCode.APP_NOT_INSTALLED -> AccessErrorCode.DEVICE_NOT_SUPPORTED
                else -> AccessErrorCode.SERVICE
            }
            return AccessError(mapped, detail = "integrity ${code ?: e.javaClass.simpleName}", retryable = mapped == AccessErrorCode.NETWORK)
        }
    }
}

/** Waits for a Play services [Task]. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        val e = t.exception
        when {
            e != null -> cont.resumeWithException(e)
            t.isCanceled -> cont.cancel()
            else -> cont.resume(t.result)
        }
    }
}
