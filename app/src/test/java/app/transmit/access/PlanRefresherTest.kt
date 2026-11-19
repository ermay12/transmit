package app.transmit.access

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the plan is refreshed while the app stays open: on showing Plans or Settings (debounced) and after Play. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanRefresherTest {
    private var now = 1_000_000L

    @Test
    fun showingAScreenRefreshesAtMostEvery30Seconds() = runTest {
        var runs = 0
        val r = PlanRefresher(this, { now }) { runs++ }

        assertTrue(r.request(RefreshTrigger.PLANS_OPENED))
        advanceUntilIdle()
        assertEquals(1, runs)

        // Plans → Settings → back to Plans within 30 s: one refresh is enough.
        now += 10_000
        assertFalse(r.request(RefreshTrigger.SETTINGS_OPENED))
        now += 19_999
        assertFalse(r.request(RefreshTrigger.PLANS_OPENED))
        advanceUntilIdle()
        assertEquals(1, runs)

        now += 1
        assertTrue(r.request(RefreshTrigger.SETTINGS_OPENED))
        advanceUntilIdle()
        assertEquals(2, runs)
    }

    @Test
    fun aPurchaseFlowOrPlaysPageRefreshesAtOnceWhateverTheInterval() = runTest {
        var runs = 0
        val r = PlanRefresher(this, { now }) { runs++ }
        r.request(RefreshTrigger.PLANS_OPENED)
        advanceUntilIdle()

        now += 1_000
        assertTrue(r.request(RefreshTrigger.PURCHASE_FLOW))
        advanceUntilIdle()
        now += 1_000
        assertTrue(r.request(RefreshTrigger.PLAY_PAGE_RETURNED))
        advanceUntilIdle()
        assertEquals(3, runs)

        // A refresh after Play counts for the debounce too.
        now += 1_000
        assertFalse(r.request(RefreshTrigger.SETTINGS_OPENED))
        advanceUntilIdle()
        assertEquals(3, runs)
    }

    @Test
    fun whileOneRunsAScreenAddsNothingButPlayRunsOnceMoreAfter() = runTest {
        var runs = 0
        var gate = CompletableDeferred<Unit>()
        val r = PlanRefresher(this, { now }) {
            runs++
            gate.await()
        }
        r.request(RefreshTrigger.SETTINGS_OPENED)
        runCurrent()
        assertEquals(1, runs)

        // The screens show the last known state meanwhile; another screen opening doesn't stack a second refresh.
        now += 40_000
        assertFalse(r.request(RefreshTrigger.PLANS_OPENED))
        // Play's sheet closed while the refresh was under way, which may have started before the change: once more.
        assertTrue(r.request(RefreshTrigger.PURCHASE_FLOW))
        assertTrue(r.request(RefreshTrigger.PURCHASE_FLOW))
        val first = gate
        gate = CompletableDeferred()
        first.complete(Unit)
        runCurrent()
        assertEquals(2, runs)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, runs)
    }

    @Test
    fun aFailedRefreshDoesntStopTheNextOne() = runTest {
        var runs = 0
        val r = PlanRefresher(this, { now }) {
            runs++
            if (runs == 1) error("Play went away")
        }
        r.request(RefreshTrigger.PLANS_OPENED)
        advanceUntilIdle()
        assertTrue(r.request(RefreshTrigger.PURCHASE_FLOW))
        advanceUntilIdle()
        assertEquals(2, runs)
    }
}
