package app.transmit.ui.access

import app.transmit.R
import app.transmit.TestStrings
import app.transmit.access.AiAccess
import app.transmit.access.AllowanceView
import app.transmit.access.EndReason
import app.transmit.access.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowanceBannerTest {
    private val day = 86_400_000L
    private val trial = AiAccess.Trial(System.currentTimeMillis() + 3 * day)
    private val standard = AiAccess.Subscribed(Tier.STANDARD, System.currentTimeMillis() + 20 * day)
    private val plus = AiAccess.Subscribed(Tier.PLUS, System.currentTimeMillis() + 20 * day)

    private fun used(percent: Int, resets: Long? = null) = AllowanceView(percent / 100.0, resets)

    private fun AllowanceBannerContent.texts() = listOfNotNull(TestStrings.resolve(title), body?.let(TestStrings::resolve), action?.let(TestStrings::text))

    @Test
    fun noAiOffersTheFreeWeekAndCanBeDismissedForGood() {
        val b = allowanceBanner(AiAccess.None, null)!!
        assertEquals(listOf("Translation is off", "7 days free. No card needed.", "Start your free week"), b.texts())
        assertEquals(BannerDismiss.TRANSLATION_OFF, b.dismiss)
        assertEquals(BannerAction.START_TRIAL, b.does)
        assertFalse(b.ownKeyLink)

        // Not installed from Play: the Play version first, the user's own key second.
        val notFromPlay = allowanceBanner(AiAccess.None, null, canBuy = false)!!
        assertEquals(
            listOf("Translation is off", "The free week needs Transmit installed from Google Play.", "Get Transmit on Google Play"),
            notFromPlay.texts(),
        )
        assertEquals(BannerDismiss.TRANSLATION_OFF, notFromPlay.dismiss)
        assertEquals(BannerAction.GET_PLAY, notFromPlay.does)
        assertTrue(notFromPlay.ownKeyLink)
    }

    @Test
    fun anEndedFreeWeekHasOneNameAndStays() {
        for (reason in listOf(EndReason.TRIAL_EXPIRED, EndReason.TRIAL_USED_UP)) {
            val b = allowanceBanner(AiAccess.Ended(reason), null)!!
            assertEquals(listOf("Your free week has ended", "Subscribe to keep translating.", "Subscribe"), b.texts())
            assertNull(b.dismiss)
        }
        // A free week whose AI is spent reads the same before it's marked ended.
        assertEquals("Your free week has ended", TestStrings.resolve(allowanceBanner(trial, used(100))!!.title))
        // Not installed from Play: the Play version, then the user's own key. Never a dead end.
        val own = allowanceBanner(AiAccess.Ended(EndReason.SUBSCRIPTION_ENDED), null, canBuy = false)!!
        assertEquals(
            listOf("Your subscription has ended", "Subscriptions need Transmit installed from Google Play.", "Get Transmit on Google Play"),
            own.texts(),
        )
        assertEquals(BannerAction.GET_PLAY, own.does)
        assertTrue(own.ownKeyLink)
    }

    @Test
    fun onAPlayInstallAnEndedPlanOffersOnlySubscribe() {
        val stopped = EndReason.entries.map { allowanceBanner(AiAccess.Ended(it), null)!! } + allowanceBanner(trial, used(100))!!
        for (b in stopped) {
            assertEquals(R.string.plan_subscribe, b.action)
            assertEquals(BannerAction.PLANS, b.does)
            assertFalse(b.ownKeyLink)
        }
    }

    @Test
    fun nearlyUsedUpIsDismissibleUntilThePeriodResets() {
        assertNull(allowanceBanner(trial, used(79)))
        val t = allowanceBanner(trial, used(85))!!
        assertEquals(listOf("You've used 85% of your free week's AI", "Subscribe to keep translating after it runs out.", "Subscribe"), t.texts())
        assertEquals(BannerDismiss.NEARLY_USED_UP, t.dismiss)

        val s = allowanceBanner(standard, used(90, resets = standard.renewsAt))!!
        assertEquals(listOf("You've used 90% of this month's AI", "Resets <date>.", "Upgrade"), s.texts())
        assertEquals(BannerDismiss.NEARLY_USED_UP, s.dismiss)
        // Plus has nothing to upgrade to.
        assertNull(allowanceBanner(plus, used(90))!!.action)
    }

    @Test
    fun notInstalledFromPlayNothingAsksToBuy() {
        val t = allowanceBanner(trial, used(85), canBuy = false)!!
        assertEquals(listOf("You've used 85% of your free week's AI"), t.texts())
        val s = allowanceBanner(standard, used(100, resets = standard.renewsAt), canBuy = false)!!
        assertEquals(listOf("You've used this month's AI", "Resets <date>."), s.texts())
        for (b in listOf(t, s)) assertFalse(b.texts().any { "Subscribe" in it || "Plus" in it })
    }

    @Test
    fun theFreeWeekFailureReasonsNeverSayTrial() {
        for (code in app.transmit.access.AccessErrorCode.entries) {
            val text = TestStrings.text(AccessText.trialFailure(code))
            assertFalse(text, text.contains("trial", ignoreCase = true))
        }
        assertEquals("Today's free weeks are all taken. Try again tomorrow.", TestStrings.text(R.string.trial_failed_full))
    }
}
