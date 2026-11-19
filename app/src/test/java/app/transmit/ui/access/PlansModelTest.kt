package app.transmit.ui.access

import app.transmit.TestStrings
import app.transmit.UiText
import app.transmit.access.AiAccess
import app.transmit.access.EndReason
import app.transmit.access.PlanSwitch
import app.transmit.access.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Plans screen and the Settings row show for each plan state, cancelled plans above all. */
class PlansModelTest {
    private val ends = 1_800_000_000_000L
    private fun say(t: UiText?) = t?.let(TestStrings::resolve)

    @Test
    fun aPlusWithRenewalOffEndsAndOffersOnlyKeepingIt() {
        val m = PlansModel.of(AiAccess.Subscribed(Tier.PLUS, renewsAt = null, periodEndsAt = ends, canceled = true))
        assertEquals(PlansAction.KEEP_SUBSCRIPTION, m.action)
        assertFalse(m.upgradeToo)
        assertEquals("Keep my subscription", TestStrings.all["plan_keep_subscription"])
        assertEquals("Your plan ends on <date>", say(m.note))
        assertEquals("Ends <date>", say(m.ownDetail))
        // No Switch to Transmit (nor its card or "Paying too much?" link), and nothing set.
        assertNull(m.switchAt)
        assertNull(m.switch)
        assertEquals(listOf(Tier.PLUS), m.shown)
    }

    @Test
    fun aTransmitWithRenewalOffEndsTooAndCanStillUpgrade() {
        val m = PlansModel.of(AiAccess.Subscribed(Tier.STANDARD, renewsAt = null, periodEndsAt = ends, canceled = true))
        assertEquals(PlansAction.KEEP_SUBSCRIPTION, m.action)
        assertTrue(m.upgradeToo)
        assertEquals(Tier.entries, m.shown)
        assertEquals("Your plan ends on <date>", say(m.note))
        assertEquals("Ends <date>", say(m.ownDetail))
    }

    @Test
    fun aSwitchGoneWithRenewalIsNotShown() {
        // The Worker drops `pending…` once renewal is off; a stale one is never shown beside "ends".
        val m = PlansModel.of(AiAccess.Subscribed(Tier.PLUS, null, pendingSwitch = PlanSwitch(Tier.STANDARD, ends), periodEndsAt = ends, canceled = true))
        assertNull(m.switch)
        assertEquals("Ends <date>", say(m.ownDetail))
    }

    @Test
    fun renewingPlansKeepTheirActions() {
        val plus = PlansModel.of(AiAccess.Subscribed(Tier.PLUS, ends))
        assertEquals(PlansAction.NONE, plus.action)
        assertEquals(ends, plus.switchAt)
        assertEquals(listOf(Tier.PLUS, Tier.STANDARD), plus.shown)
        assertEquals("Renews <date>", say(plus.ownDetail))
        assertNull(plus.note)

        val switching = PlansModel.of(AiAccess.Subscribed(Tier.PLUS, ends, pendingSwitch = PlanSwitch(Tier.STANDARD, ends)))
        assertNull(switching.switchAt)
        assertEquals("Switches to Transmit on <date>", say(switching.ownDetail))
        assertEquals(
            "Switching to Transmit on <date>. Changed your mind? You can upgrade back to Plus anytime after the switch.",
            say(switching.note),
        )

        val standard = PlansModel.of(AiAccess.Subscribed(Tier.STANDARD, ends))
        assertEquals(PlansAction.UPGRADE, standard.action)
        assertFalse(standard.upgradeToo)
        assertEquals(Tier.entries, standard.shown)

        // Another device has the key: no switch from here.
        assertNull(PlansModel.of(AiAccess.Subscribed(Tier.PLUS, ends, inUseElsewhere = true)).switchAt)

        for (a in listOf(null, AiAccess.None, AiAccess.Trial(ends), AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED))) {
            assertEquals(PlansAction.SUBSCRIBE, PlansModel.of(a).action)
            assertNull(PlansModel.of(a).note)
        }
    }

    @Test
    fun theSettingsRowSaysWhenThePlanRenewsSwitchesOrEnds() {
        assertEquals("Renews <date>", say(subscriptionDetail(AiAccess.Subscribed(Tier.PLUS, ends))))
        assertEquals("Ends <date>", say(subscriptionDetail(AiAccess.Subscribed(Tier.PLUS, null, periodEndsAt = ends, canceled = true))))
        assertEquals("Ends <date>", say(subscriptionDetail(AiAccess.Subscribed(Tier.STANDARD, null, periodEndsAt = ends, canceled = true))))
        assertEquals("Switches to Transmit on <date>", say(subscriptionDetail(AiAccess.Subscribed(Tier.PLUS, ends, pendingSwitch = PlanSwitch(Tier.STANDARD, ends)))))
        assertEquals("In use on another device", say(subscriptionDetail(AiAccess.Subscribed(Tier.PLUS, null, inUseElsewhere = true, periodEndsAt = ends, canceled = true))))
        // Under the plan's name: "Transmit Plus" / "Transmit".
        assertEquals("Transmit Plus", TestStrings.all["plan_plus_name"])
        assertEquals("Transmit", TestStrings.all["plan_standard_name"])
    }
}
