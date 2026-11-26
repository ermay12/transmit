package app.transmit.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import java.net.URI

/**
 * The setup funnel, counted with Google Analytics for Firebase, so we can see where new users stop and Google Ads can
 * count the free weeks its ads lead to ([trialStarted] is the conversion).
 *
 * Event names can't start with "google_", "firebase_" or "ga_": Firebase drops them (it logs "reserved prefix").
 *
 * Only the events below are sent, and their parameters are fixed words, never message text, names, phone numbers,
 * email addresses or contacts. The manifest turns off the Advertising ID, ANDROID_ID, ad personalization and automatic
 * screen events. [start] turns collection on only for copies installed from Google Play (and debug builds, for
 * testing); a GitHub APK sends nothing.
 *
 * Firebase adds its automatic events (first_open, session_start, user_engagement, app_update, os_update, app_remove,
 * in_app_purchase) with the device model, Android version, app version, language and a country from the IP address.
 * The privacy policy and docs/launch/data-safety.md describe all of it: change them with this file.
 */
object Analytics {
    private const val TAG = "Analytics"

    @Volatile
    private var firebase: FirebaseAnalytics? = null

    /** From [android.app.Application.onCreate]. Until then, and in unit tests, every event is dropped. */
    fun start(context: Context, enabled: Boolean) {
        firebase = runCatching {
            FirebaseAnalytics.getInstance(context).also { it.setAnalyticsCollectionEnabled(enabled) }
        }.onFailure { Log.w(TAG, "Firebase Analytics unavailable", it) }.getOrNull()?.takeIf { enabled }
    }

    /** Onboarding: the languages step is done. */
    fun languagesChosen() = log("languages_chosen")

    /** Onboarding: "Connect later" on the Google Messages step. */
    fun pairingSkipped() = log("pairing_skipped")

    /** Google's sign-in page opened inside the app. */
    fun googleSignInOpened() = log("signin_opened")

    /** Left Google's sign-in page before it finished. [lastUrl] says which step, as [signInStep] and [signInPage]. */
    fun googleSignInLeft(lastUrl: String?) = log("signin_left", "step" to signInStep(lastUrl), "page" to signInPage(lastUrl))

    /** Signed in to Google: the cookies for Messages for Web are there. */
    fun googleSignInDone() = log("signin_done")

    fun qrPairingOpened() = log("qr_pairing_opened")

    /** Google-account pairing reached the emoji to confirm in Google Messages. */
    fun pairingEmojiShown() = log("pairing_emoji_shown")

    /** Linked to Google Messages. [method]: "google" or "qr". */
    fun paired(method: String) = log("paired", "method" to method)

    /** Onboarding done. [ai]: "trial", "plan", "own_key" or "none". */
    fun onboardingFinished(ai: String) = log("onboarding_finished", "ai" to ai)

    /** A new free week (not one restored after a reinstall). The Google Ads conversion. */
    fun trialStarted() = log("trial_started")

    /** The free week didn't start. [reason]: the app's error code, such as "trial_already_used" or "not_from_play". */
    fun trialFailed(reason: String) = log("trial_failed", "reason" to reason.lowercase())

    private fun log(event: String, vararg params: Pair<String, String>) {
        val fa = firebase ?: return
        val bundle = Bundle().apply { params.forEach { (k, v) -> putString(k, v.take(MAX_VALUE)) } }
        runCatching { fa.logEvent(event, bundle) }.onFailure { Log.w(TAG, "logEvent $event failed", it) }
    }

    /** Analytics drops parameter values longer than this. */
    private const val MAX_VALUE = 100

    /**
     * Which of Google's sign-in steps [url] is, in a word: where people give up. From the host and path only; the query
     * (which can hold the email address) is never read.
     */
    internal fun signInStep(url: String?): String {
        val (host, path) = hostAndPath(url) ?: return "loading"
        val p = path.lowercase()
        return when {
            host.startsWith("messages.google.") -> "messages"
            host == "gds.google.com" || "speedbump" in p -> "interstitial"
            "rejected" in p || "disallowed" in p -> "rejected"
            "/challenge/pwd" in p -> "password"
            "/challenge/selection" in p -> "choose_method"
            "/challenge/" in p -> "verify"
            "recovery" in p -> "recovery"
            "signup" in p -> "create_account"
            "accountchooser" in p -> "choose_account"
            "identifier" in p -> "email"
            else -> "other"
        }
    }

    /**
     * [url]'s host and path, for the steps [signInStep] doesn't know. Path segments other than plain words (and
     * versions like "v3") become "*", so an ID or token in a path never leaves the phone; the query and fragment are
     * dropped.
     */
    internal fun signInPage(url: String?): String {
        val (host, path) = hostAndPath(url) ?: return ""
        val segments = path.split('/').filter { it.isNotEmpty() }.map { if (WORD.matches(it)) it else "*" }
        return (host + "/" + segments.joinToString("/")).take(MAX_VALUE)
    }

    private val WORD = Regex("[A-Za-z_-]{1,40}|v\\d{1,2}")

    private fun hostAndPath(url: String?): Pair<String, String>? {
        val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return null
        val host = uri.host?.lowercase() ?: return null
        return host to (uri.rawPath ?: "")
    }
}
