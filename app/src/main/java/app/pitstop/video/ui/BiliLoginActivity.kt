package app.pitstop.video.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.pitstop.video.bilibili.BiliCookieJar
import app.pitstop.video.graph

/** Bilibili's own mobile login page (password / SMS / captcha) in a WebView; cookies are copied on success. */
class BiliLoginActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            cookies.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val header = cookies.getCookie("https://www.bilibili.com") ?: return
                    if ("${BiliCookieJar.SESSDATA}=" in header) {
                        graph.biliCookies.importCookieHeader(header)
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
        web.loadUrl("https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F")
    }
}
