package app.transmit.access

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What asked for the plan to be refreshed. Opening a screen is [debounced]; coming back from Google Play never is. */
enum class RefreshTrigger(val debounced: Boolean) {
    PLANS_OPENED(true),
    SETTINGS_OPENED(true),

    /** Play's purchase sheet closed, whatever happened in it (bought, backed out, refused), or it didn't open. */
    PURCHASE_FLOW(false),

    /** Back from Play's own subscription page (Manage subscription), where the plan may have changed. */
    PLAY_PAGE_RETURNED(false),
}

/**
 * Brings what the app knows about the plan up to date while it stays in the foreground, so a switch that took
 * effect, a renewal turned off or on, or a lapse shows without leaving the app: [run] asks Play for its purchases and
 * the Worker for the subscription (`"rotate": false`, so it never takes the key from another device).
 *
 * The Plans and Settings screens ask each time they show, at most once every [minIntervalMs]; a purchase flow that
 * sent nothing to the Worker, or a return from Play's subscription page, asks right away. A request while a refresh
 * runs is dropped, except one from Play, which runs once more afterwards (the running refresh may predate what Play
 * just did). While Play's purchase sheet is open or its purchase is on its way ([busy]), screens don't ask: the
 * purchase's own answer brings the plan, and then counts as a refresh ([upToDate]). Screens keep showing the last known
 * state meanwhile: [run] only updates the stored state they already watch, and nothing waits on it.
 */
class PlanRefresher(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val busy: () -> Boolean = { false },
    private val run: suspend () -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var again = false
    private var lastStartedAt: Long? = null

    /** Whether [trigger] starts a refresh (or queues one after the running refresh). */
    fun request(trigger: RefreshTrigger): Boolean {
        if (trigger.debounced && busy()) return false
        synchronized(lock) {
            if (running) {
                if (trigger.debounced) return false
                again = true
                return true
            }
            val now = clock()
            val last = lastStartedAt
            if (trigger.debounced && last != null && now - last < minIntervalMs) return false
            running = true
            lastStartedAt = now
        }
        scope.launch {
            try {
                do {
                    try {
                        run()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Plan refresh failed", e)
                    }
                } while (takeAgain())
            } finally {
                synchronized(lock) {
                    running = false
                    again = false
                }
            }
        }
        return true
    }

    /**
     * The Worker has just answered with the plan (a purchase from Play's sheet): screens that open within
     * [minIntervalMs] don't ask again.
     */
    fun upToDate() {
        synchronized(lock) { lastStartedAt = clock() }
    }

    private fun takeAgain(): Boolean = synchronized(lock) {
        if (!again) return false
        again = false
        lastStartedAt = clock()
        true
    }

    companion object {
        private const val TAG = "PlanRefresh"
        const val MIN_INTERVAL_MS = 30_000L

        /** Play's purchases first (a purchase made outside the app reaches the Worker), then the Worker's view. */
        fun forPlan(scope: CoroutineScope, purchases: Purchases, access: AccessManager, clock: () -> Long = System::currentTimeMillis) =
            PlanRefresher(scope, clock, busy = { purchases.flowOpen }) {
                purchases.syncNew()
                access.refresh(force = true)
            }
    }
}
