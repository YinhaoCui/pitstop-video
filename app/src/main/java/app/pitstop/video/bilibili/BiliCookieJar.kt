package app.pitstop.video.bilibili

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Persists bilibili.com cookies (login session SESSDATA/bili_jct, device id buvid3) in app-private prefs.
 * Cookies for any other host are not stored.
 */
class BiliCookieJar(private val prefs: SharedPreferences) : CookieJar {
    private val _loggedIn = MutableStateFlow(get(SESSDATA) != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.host.endsWith(DOMAIN)) return
        putAll(cookies.associate { it.name to it.value })
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!url.host.endsWith(DOMAIN)) return emptyList()
        return all().map { (k, v) -> Cookie.Builder().name(k).value(v).domain(DOMAIN).path("/").build() }
    }

    fun get(name: String): String? = prefs.getString(PREFIX + name, null)

    fun all(): Map<String, String> = prefs.all
        .filterKeys { it.startsWith(PREFIX) }
        .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(PREFIX) to it } }
        .toMap()

    fun putAll(values: Map<String, String>) {
        val edit = prefs.edit()
        values.forEach { (k, v) ->
            // Bilibili clears cookies by sending empty / "deleted" values.
            if (v.isEmpty() || v == "deleted") edit.remove(PREFIX + k) else edit.putString(PREFIX + k, v)
        }
        edit.commit()
        _loggedIn.value = get(SESSDATA) != null
    }

    /** Parses a browser cookie header ("a=b; c=d"), e.g. from the WebView login. */
    fun importCookieHeader(header: String?) {
        if (header.isNullOrBlank()) return
        putAll(header.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap())
    }

    /** Signs out but keeps the anonymous device id cookies. */
    fun logout() {
        val edit = prefs.edit()
        listOf(SESSDATA, "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid").forEach { edit.remove(PREFIX + it) }
        edit.commit()
        _loggedIn.value = false
    }

    companion object {
        const val DOMAIN = "bilibili.com"
        const val SESSDATA = "SESSDATA"
        private const val PREFIX = "c_"
    }
}
