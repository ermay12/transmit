package app.transmit.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnalyticsTest {
    private val g = "https://accounts.google.com"

    @Test
    fun signInSteps() {
        assertEquals("loading", Analytics.signInStep(null))
        assertEquals("loading", Analytics.signInStep("not a url"))
        assertEquals("choose_account", Analytics.signInStep("$g/AccountChooser?continue=https://messages.google.com/web/config"))
        assertEquals("email", Analytics.signInStep("$g/v3/signin/identifier?continue=x&flowName=GlifWebSignIn"))
        assertEquals("password", Analytics.signInStep("$g/v3/signin/challenge/pwd?TL=abc"))
        assertEquals("choose_method", Analytics.signInStep("$g/v3/signin/challenge/selection"))
        assertEquals("verify", Analytics.signInStep("$g/v3/signin/challenge/dp"))
        assertEquals("verify", Analytics.signInStep("$g/v3/signin/challenge/ipp/collect"))
        assertEquals("rejected", Analytics.signInStep("$g/v3/signin/rejected?rrk=46"))
        assertEquals("recovery", Analytics.signInStep("$g/signin/v2/recoveryidentifier"))
        assertEquals("create_account", Analytics.signInStep("$g/lifecycle/flows/signup"))
        assertEquals("interstitial", Analytics.signInStep("$g/speedbump/idvreenable"))
        assertEquals("interstitial", Analytics.signInStep("https://gds.google.com/web/recoveryoptions"))
        assertEquals("messages", Analytics.signInStep("https://messages.google.com/web/config"))
        assertEquals("other", Analytics.signInStep("$g/CheckCookie"))
    }

    @Test
    fun signInPageKeepsOnlyHostAndPlainPathWords() {
        val page = Analytics.signInPage("$g/v3/signin/challenge/pwd?Email=someone%40gmail.com&TL=AHk2x#frag")
        assertEquals("accounts.google.com/v3/signin/challenge/pwd", page)
        assertFalse("@" in page || "gmail" in page)
        // Tokens and numbers in the path don't leave the phone.
        assertEquals("accounts.google.com/b/*/signin/*", Analytics.signInPage("$g/b/0/signin/AHk2xQ9zLmN0pQ"))
        assertEquals("", Analytics.signInPage(null))
    }

    @Test
    fun eventsBeforeStartAreDropped() {
        // No Firebase in unit tests: logging must be a no-op, not a crash.
        Analytics.trialStarted()
        Analytics.googleSignInLeft("$g/v3/signin/challenge/pwd")
    }
}
