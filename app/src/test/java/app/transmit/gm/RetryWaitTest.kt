package app.transmit.gm

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long poll's retry after an outage comes when the network does, not when a wait of up to a minute ends. */
class RetryWaitTest {
    @Test
    fun withoutTheNetworkComingBackItWaitsItsTime() = runTest {
        assertFalse(RetryWait().await(60_000))
        assertEquals(60_000, currentTime)
    }

    @Test
    fun theNetworkComingBackEndsTheWait() = runTest {
        val wait = RetryWait()
        val woken = async { wait.await(60_000) }
        advanceTimeBy(5_000)
        runCurrent()
        wait.wake()
        assertTrue(woken.await())
        assertEquals(5_000, currentTime)
    }

    @Test
    fun aWakeDuringTheFailedAttemptEndsTheNextWait() = runTest {
        // The network came back while the attempt that then failed was still trying.
        val wait = RetryWait()
        wait.wake()
        assertTrue(wait.await(60_000))
        assertEquals(0, currentTime)
    }

    @Test
    fun aWakeFromBeforeAConnectionIsDropped() = runTest {
        // The network callback reports the current network once, as it's registered, while the poll connects.
        val wait = RetryWait()
        wait.wake()
        wait.reset()
        assertFalse(wait.await(10_000))
        assertEquals(10_000, currentTime)
    }

    @Test
    fun severalWakesEndOnlyOneWait() = runTest {
        val wait = RetryWait()
        wait.wake()
        wait.wake()
        assertTrue(wait.await(10_000))
        assertFalse(wait.await(10_000))
    }
}
