package app.transmit.access

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.transmit.app
import java.util.concurrent.TimeUnit

/** Background work for AI access: purchase delivery, the daily refresh, and the day-6 trial reminder. */
object AccessWork {
    private const val DELIVERY = "purchase-delivery"
    private const val REFRESH = "access-refresh"
    private const val REMINDER = "trial-reminder"
    private const val DAY_MS = 24 * 3_600_000L

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Sends queued purchase tokens once a network is up, backing off while the Worker or Google is failing. */
    fun scheduleDelivery(context: Context) {
        val request = OneTimeWorkRequestBuilder<PurchaseDeliveryWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DELIVERY, ExistingWorkPolicy.KEEP, request)
    }

    /** Once a day: renewals, upgrades, lapses and refunds, and OpenRouter's view of the allowance. */
    fun scheduleDailyRefresh(context: Context) {
        val request = PeriodicWorkRequestBuilder<AccessRefreshWorker>(1, TimeUnit.DAYS)
            .setConstraints(online)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(REFRESH, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** "Your free week ends tomorrow", 24 h before [endsAt]. A new trial (or a re-issued one) replaces it. */
    fun scheduleTrialReminder(context: Context, endsAt: Long, now: Long = System.currentTimeMillis()) {
        val delay = reminderDelay(endsAt, now) ?: return
        val request = OneTimeWorkRequestBuilder<TrialReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(REMINDER, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelTrialReminder(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(REMINDER)
    }

    /** Null when the reminder's time has passed (a trial restored on its last day). */
    fun reminderDelay(endsAt: Long, now: Long): Long? = (endsAt - DAY_MS - now).takeIf { it >= 0 }

    /** The reminder still applies: the trial is running and ends within a day and a bit. */
    fun reminderDue(access: AiAccess, now: Long): Boolean =
        access is AiAccess.Trial && access.endsAt > now && access.endsAt - now <= DAY_MS + 3_600_000L
}

class PurchaseDeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val purchases = applicationContext.app.container.purchases
        purchases.deliver()
        return if (purchases.hasQueued()) Result.retry() else Result.success()
    }
}

class AccessRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.app.container
        c.purchases.sync()
        c.access.refresh(force = true)
        c.access.reconcile()
        return Result.success()
    }
}

class TrialReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.app.container
        if (AccessWork.reminderDue(c.access.current(), System.currentTimeMillis())) c.notifier.notifyTrialEndsTomorrow(c.purchases.available)
        return Result.success()
    }
}
