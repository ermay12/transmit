package app.transmit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product decisions about wording, checked across every string resource. */
class CopyTest {
    @Test
    fun theTrialIsAlwaysTheFreeWeek() {
        val trial = TestStrings.all.filterValues { it.contains("trial", ignoreCase = true) }
        assertEquals(emptyMap<String, String>(), trial)
        assertEquals("Your free week has ended", TestStrings.all["ended_free_week"])
    }

    /**
     * Moving down from Plus is "Switch to Transmit", offered calmly beside Manage subscription: never called a
     * downgrade, and with no new words about cancelling (only the auto-renew line the Subscriptions policy asks for,
     * "…until you cancel in Google Play", and the dialogs' Cancel).
     */
    @Test
    fun plusMovesDownBySwitchingToTransmitAndNothingNewTalksAboutCancelling() {
        val downgrade = TestStrings.all.filterValues { v -> listOf("downgrad", "move down").any { v.contains(it, ignoreCase = true) } }
        assertEquals(emptyMap<String, String>(), downgrade)
        assertEquals("Switch to Transmit", TestStrings.all["plan_switch"])
        assertEquals("Paying too much? Switch to Transmit instead", TestStrings.all["plan_switch_instead"])
        assertEquals("Switches to Transmit on %1\$s", TestStrings.all["plan_switches_on"])
        assertEquals("Keep Plus", TestStrings.all["plan_keep_plus"])
        val cancelling = TestStrings.all.filterValues { it.contains("cancel", ignoreCase = true) }.keys
        assertEquals(setOf("action_cancel", "plan_auto_renew", "plan_auto_renew_no_price"), cancelling)
    }

    @Test
    fun theStringsAreRead() {
        assertTrue(TestStrings.all.size > 100)
    }
}
