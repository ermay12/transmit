package app.transmit.ui.pairing

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.transmit.analytics.Analytics

/** The cookies Google Messages for Web authenticates with, read from the in-app sign-in. */
object GoogleSignInCookies {
    /** Where the web app sends people to sign in; it lands on messages.google.com when done. */
    const val SIGN_IN_URL = "https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config"
    private const val MESSAGES_URL = "https://messages.google.com/web/config"
    private val REQUIRED = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
    private val WANTED = REQUIRED + listOf("OSID", "__Secure-1PSIDTS", "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID")

    /** The signed-in cookies, or null while not signed in. */
    fun read(): Map<String, String>? {
        val header = CookieManager.getInstance().getCookie(MESSAGES_URL) ?: return null
        val all = header.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
        if (REQUIRED.any { it !in all }) return null
        return all.filterKeys { it in WANTED }
    }

    fun hasMessagesCookie() = read()?.containsKey("OSID") == true

    /** Signs the in-app browser out, to pick another account. */
    fun clear() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}

/**
 * Google's sign-in page in a WebView. Google refuses sign-in from apps that identify as embedded
 * browsers, so this one presents itself as Chrome. Calls [onSignedIn] once Google hands over to
 * messages.google.com with the session cookies set.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleSignIn(onSignedIn: (Map<String, String>) -> Unit, onBack: () -> Unit) {
    val signedIn by rememberUpdatedState(onSignedIn)
    var loading by remember { mutableStateOf(true) }
    var done by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The page Google showed last, for the step people give up on (only its host and path are counted).
    var lastUrl by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        Analytics.googleSignInOpened()
        onDispose { if (!done) Analytics.googleSignInLeft(lastUrl) }
    }
    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to Google") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        configureAsChrome(this)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                loading = true
                                lastUrl = url
                                check(view, url)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                check(view, url)
                            }

                            private fun check(view: WebView, url: String) {
                                if (done || !url.startsWith("https://messages.google.com/")) return
                                CookieManager.getInstance().flush()
                                // OSID is set by messages.google.com itself; give it a moment after the redirect.
                                fun attempt(triesLeft: Int) {
                                    if (done) return
                                    val cookies = GoogleSignInCookies.read()
                                    if (cookies != null && (cookies.containsKey("OSID") || triesLeft == 0)) {
                                        done = true
                                        Analytics.googleSignInDone()
                                        signedIn(cookies)
                                    } else if (triesLeft > 0) {
                                        view.postDelayed({ attempt(triesLeft - 1) }, 500)
                                    }
                                }
                                attempt(10)
                            }
                        }
                        loadUrl(GoogleSignInCookies.SIGN_IN_URL)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

private fun configureAsChrome(view: WebView) {
    val settings = view.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    val default = WebSettings.getDefaultUserAgent(view.context)
    val full = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: "138.0.0.0"
    val major = full.substringBefore('.')
    // Chrome's reduced user agent, without WebView's "wv" and "Version/4.0" markers.
    settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Mobile Safari/537.36"
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        // Otherwise every request names this app in an X-Requested-With header.
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
        // Client hints otherwise list the brand "Android WebView".
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Not.A/Brand").setMajorVersion("99").setFullVersion("99.0.0.0").build(),
        )
        WebSettingsCompat.setUserAgentMetadata(
            settings,
            UserAgentMetadata.Builder().setBrandVersionList(brands).setFullVersion(full).setPlatform("Android").setMobile(true).build(),
        )
    }
}
