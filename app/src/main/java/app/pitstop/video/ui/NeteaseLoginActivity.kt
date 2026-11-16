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
import app.pitstop.video.graph

/** NetEase Cloud Music's own mobile login page (phone + SMS / password) in a WebView; cookies are copied on success. */
class NeteaseLoginActivity : ComponentActivity() {
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
                    val header = cookies.getCookie("https://music.163.com") ?: return
                    if ("MUSIC_U=" in header) {
                        graph.neteaseCookies.importCookieHeader(header)
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
        web.loadUrl("https://y.music.163.com/m/login")
    }
}
