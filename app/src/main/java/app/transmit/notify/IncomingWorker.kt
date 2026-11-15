package app.transmit.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.transmit.app
import kotlinx.coroutines.withTimeoutOrNull

/** Translates a newly received message, then refreshes its notification without re-alerting. */
class IncomingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val threadId = inputData.getLong(KEY_THREAD, -1)
        val key = inputData.getString(KEY_MESSAGE) ?: return Result.success()
        val container = applicationContext.app.container
        withTimeoutOrNull(60_000) { container.translations.translateIncoming(threadId, key) }
        container.notifier.showThread(threadId, alert = false)
        return Result.success()
    }

    companion object {
        private const val KEY_THREAD = "thread"
        private const val KEY_MESSAGE = "key"

        fun enqueue(context: Context, threadId: Long, key: String) {
            val request = OneTimeWorkRequestBuilder<IncomingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_THREAD to threadId, KEY_MESSAGE to key))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("incoming-$key", ExistingWorkPolicy.KEEP, request)
        }
    }
}
