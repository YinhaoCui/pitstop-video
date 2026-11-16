package app.pitstop.video.core

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

fun buildHttpClient(cookieJar: CookieJar): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val req = chain.request()
        val host = req.url.host
        // Bilibili rejects API/CDN requests without a browser UA + Referer.
        val out = if (host.endsWith("bilibili.com") || host.endsWith("hdslb.com") || host.endsWith("bilivideo.com")) {
            req.newBuilder().apply {
                if (req.header("User-Agent") == null) header("User-Agent", DESKTOP_UA)
                if (req.header("Referer") == null) header("Referer", "https://www.bilibili.com/")
            }.build()
        } else req
        chain.proceed(out)
    }
    .build()

suspend fun OkHttpClient.getString(request: Request): String = withContext(Dispatchers.IO) {
    newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${request.url.encodedPath}")
        resp.body?.string() ?: throw IOException("Empty body")
    }
}
