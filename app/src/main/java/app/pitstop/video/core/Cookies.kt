package app.pitstop.video.core

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** One shared OkHttp client, several services: each jar only answers for its own domain. */
class CompositeCookieJar(private val jars: List<CookieJar>) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = jars.forEach { it.saveFromResponse(url, cookies) }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = jars.flatMap { it.loadForRequest(url) }
}

/**
 * Persists the cookies of one site (e.g. "163.com") in app-private prefs. [sessionCookie] marks
 * a signed-in session; [signedIn] follows it.
 */
open class SiteCookieJar(
    private val prefs: SharedPreferences,
    private val domain: String,
    private val sessionCookie: String,
) : CookieJar {
    private val _signedIn = MutableStateFlow(get(sessionCookie) != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.endsWith(domain)) return
        putAll(cookies.associate { it.name to it.value })
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.endsWith(domain)) return emptyList()
        return all().map { (k, v) -> Cookie.Builder().name(k).value(v).domain(domain).path("/").build() }
    }

    fun get(name: String): String? = prefs.getString(PREFIX + name, null)

    fun all(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith(PREFIX) }
        .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(PREFIX) to it } }
        .toMap()

    fun putAll(values: Map<String, String>) {
        val edit = prefs.edit()
        values.forEach { (k, v) -> if (v.isEmpty() || v == "deleted") edit.remove(PREFIX + k) else edit.putString(PREFIX + k, v) }
        edit.commit()
        _signedIn.value = get(sessionCookie) != null
    }

    /** Parses a browser cookie header ("a=b; c=d"), e.g. from a WebView login. */
    fun importCookieHeader(header: String?) {
        if (header.isNullOrBlank()) return
        putAll(header.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap())
    }

    fun clear() {
        prefs.edit().clear().commit()
        _signedIn.value = false
    }

    private companion object {
        const val PREFIX = "c_"
    }
}
