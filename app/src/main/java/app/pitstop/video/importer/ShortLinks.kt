package app.pitstop.video.importer

import app.pitstop.video.core.DESKTOP_UA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Expands share short links (QQ Music `c6.y.qq.com/base/fcgi-bin/u?__=…`, Kugou `t1.kugou.com/…`,
 * `spotify.link/…`, `apple.co/…`) by following HTTP redirects hop by hop without reading bodies. Stops as soon
 * as the link is no longer a short link, so the playlist page itself is never downloaded (and an http:// hop is
 * upgraded to https, since Android blocks cleartext). Any other URL is returned unchanged; on network errors
 * the last URL reached is returned.
 */
suspend fun resolveShortLink(http: OkHttpClient, url: String): String = withContext(Dispatchers.IO) {
    var current = url.trim()
    if (!ShortLinks.isShortLink(current)) return@withContext current
    val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    repeat(ShortLinks.MAX_HOPS) {
        val next = try {
            client.newCall(Request.Builder().url(current).header("User-Agent", DESKTOP_UA).build()).execute().use { r ->
                if (r.isRedirect) r.header("Location") else null
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        val resolved = next?.let { ShortLinks.nextHop(current, it) } ?: return@withContext current
        current = resolved
        if (!ShortLinks.isShortLink(current)) return@withContext current
    }
    current
}

/** Pure parts of short-link expansion (unit-tested). */
object ShortLinks {
    const val MAX_HOPS = 8

    private val shortHosts = setOf(
        "c6.y.qq.com", "url.cn", // QQ Music share links
        "t1.kugou.com", "t.kugou.com", "m.t.kugou.com", // Kugou share links
        "spotify.link", "spotify.app.link", "link.tospotify.com",
        "apple.co",
    )

    /** True for share short links that need expanding before an importer can read them. */
    fun isShortLink(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        if (u.host in shortHosts) return true
        // QQ Music also shortens through c.y.qq.com/base/fcgi-bin/u?__=…
        return u.host.endsWith("y.qq.com") && u.encodedPath == "/base/fcgi-bin/u"
    }

    /** Absolute next URL for a `Location` header (relative locations resolved; http upgraded to https). */
    fun nextHop(current: String, location: String): String? {
        val base = current.toHttpUrlOrNull() ?: return null
        val next = base.resolve(location.trim()) ?: return null
        return (if (next.scheme == "http") next.newBuilder().scheme("https").build() else next).toString()
    }
}
