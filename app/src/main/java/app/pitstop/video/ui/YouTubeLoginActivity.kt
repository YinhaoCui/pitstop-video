package app.pitstop.video.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.pitstop.video.graph

/**
 * Google's own sign-in page in a WebView; when it lands back on YouTube, the youtube.com session
 * cookies are handed to [app.pitstop.video.youtube.YouTubeAccount]. No OAuth client is involved.
 *
 * Google refuses sign-in in pages it detects as embedded ("This browser or app may not be secure"),
 * so the page gets a plain mobile Chrome user agent and no `X-Requested-With` app header.
 */
class YouTubeLoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = browserUserAgent(settings.userAgentString)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
            cookies.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val host = Uri.parse(url).host.orEmpty()
                    if (!host.endsWith("youtube.com")) return
                    val header = cookies.getCookie("https://www.youtube.com") ?: return
                    if ("SAPISID=" in header) {
                        cookies.flush()
                        graph.youTubeAccount.completeWebSignIn(header)
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(web)
        web.loadUrl(LOGIN_URL)
    }

    private companion object {
        /** Google sign-in for the YouTube service; YouTube then sets its own session cookies and opens m.youtube.com. */
        val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=" +
            Uri.encode("https://www.youtube.com/signin?action_handle_signin=true&app=m&next=" + Uri.encode("https://m.youtube.com/"))

        /** The WebView's own UA minus the WebView markers ("; wv", "Version/4.0"), i.e. what Chrome sends. */
        fun browserUserAgent(webViewUa: String) =
            webViewUa.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")
    }
}
