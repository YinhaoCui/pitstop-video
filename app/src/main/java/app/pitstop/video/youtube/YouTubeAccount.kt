package app.pitstop.video.youtube

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.AppLanguage
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.SiteCookieJar
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.util.Locale

/**
 * Optional Google account link for YouTube's personal feeds, with no Google Cloud project, API key
 * or quota: the user signs in on Google's own page in a WebView ([app.pitstop.video.ui.YouTubeLoginActivity]),
 * and the session cookies sign requests to YouTube's web API the way youtube.com does.
 *
 * Only feed listings use the account. Playback stays anonymous (NewPipe on the shared client, which
 * never gets these cookies).
 *
 * Subscriptions keep working when the web API changes or the session ends: the subscribed channels
 * are remembered and their public RSS feeds are merged instead.
 */
class YouTubeAccount(context: Context, private val http: OkHttpClient) {
    private val jar = SiteCookieJar(context.getSharedPreferences("yt_cookies", Context.MODE_PRIVATE), "youtube.com", "SAPISID")
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    /** Own client: the account cookies (and their rotation) only ever go to youtube.com API calls. */
    private val client: OkHttpClient = http.newBuilder().cookieJar(jar).build()

    val signedIn: StateFlow<Boolean> = jar.signedIn

    /** Channels remembered from the last sync, for the RSS fallback (id to name). */
    @Volatile private var channels: List<Pair<String, String>> = readChannels()

    /** How many subscribed channels are remembered for the RSS fallback. */
    val rememberedChannels: Int get() = channels.size

    /** True when Subscriptions can show something: signed in, or channels remembered from before. */
    val hasSubscriptions get() = signedIn.value || channels.isNotEmpty()

    /** Called by the login page with the WebView's cookie header for youtube.com. */
    fun completeWebSignIn(cookieHeader: String) {
        jar.importCookieHeader(cookieHeader)
        lastSync = 0
    }

    /** Signs out here and in the WebView, so the next sign-in starts clean. Keeps remembered channels. */
    fun signOut() {
        jar.clear()
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    /** Forgets the remembered subscription list too. */
    fun forgetChannels() {
        channels = emptyList()
        prefs.edit().remove(KEY_CHANNELS).remove(KEY_SYNCED).apply()
    }

    // ---- feeds (the cursor is YouTube's continuation token, or "rss:<offset>" for the fallback) ----

    suspend fun subscriptionPage(cursor: String?): FeedPage {
        cursor?.removePrefix(RSS)?.takeIf { it != cursor }?.let { return rssPage(it.toIntOrNull() ?: 0) }
        if (!signedIn.value) return rssPage(0)
        return try {
            val page = browsePage("FEsubscriptions", cursor, SourceId.YOUTUBE)
            if (cursor == null) syncChannelsIfDue()
            page
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Web API changed or the session ended: fall back to the channels' public feeds.
            Log.w(TAG, "subscriptions via web API failed, using RSS", e)
            if (cursor == null && channels.isNotEmpty()) rssPage(0) else throw e
        }
    }

    suspend fun likedPage(cursor: String?) = browsePage("VLLL", cursor, SourceId.YOUTUBE)
    suspend fun watchLaterPage(cursor: String?) = browsePage("VLWL", cursor, SourceId.YOUTUBE)
    suspend fun historyPage(cursor: String?) = browsePage("FEhistory", cursor, SourceId.YOUTUBE)

    /** YouTube Music's "Liked music" (the LM auto playlist). */
    suspend fun likedMusicPage(cursor: String?) = browsePage("VLLM", cursor, SourceId.YT_MUSIC)

    /** The user's playlists (made on YouTube or YouTube Music), as collection rows. */
    suspend fun playlistsPage(source: SourceId, cursor: String?): FeedPage {
        val j = browse("FEplaylist_aggregation", cursor)
        return FeedPage(InnertubeParse.playlists(j, source, I18n.s::tracks), InnertubeParse.continuation(j))
    }

    /** Every video in one playlist (up to [MAX_PLAYLIST]), for playing / adding a whole playlist. */
    suspend fun playlistItems(playlistId: String, source: SourceId): List<VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        var cursor: String? = null
        do {
            val page = browsePage("VL$playlistId", cursor, source)
            page.items.forEach { out.putIfAbsent(it.id, it) }
            cursor = page.next
        } while (cursor != null && out.size < MAX_PLAYLIST && page.items.isNotEmpty())
        return out.values.toList()
    }

    private suspend fun browsePage(browseId: String, cursor: String?, source: SourceId): FeedPage {
        val j = browse(browseId, cursor)
        return FeedPage(InnertubeParse.videos(j, source), InnertubeParse.continuation(j))
    }

    // ---- web API ----

    /** One `youtubei/v1/browse` call: a page by [browseId], or the page after [continuation]. */
    private suspend fun browse(browseId: String, continuation: String?): JSONObject = withContext(Dispatchers.IO) {
        val sapisid = jar.get("SAPISID") ?: jar.get("__Secure-3PAPISID")
            ?: throw UserFacingException(I18n.s.youtubeSignInAgain)
        val version = clientVersion()
        val lang = if (I18n.language.value == AppLanguage.ZH) "zh-CN" else "en"
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", version)
                .put("hl", lang).put("gl", Locale.getDefault().country.ifEmpty { "US" })))
            .apply { if (continuation != null) put("continuation", continuation) else put("browseId", browseId) }
        val req = Request.Builder()
            .url("$ORIGIN/youtubei/v1/browse?prettyPrint=false")
            .header("Authorization", InnertubeParse.sapisidHash(sapisid, ORIGIN, System.currentTimeMillis() / 1000))
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", version)
            .header("User-Agent", DESKTOP_UA)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) { signOut(); throw UserFacingException(I18n.s.youtubeSignInAgain) }
            if (!resp.isSuccessful) throw IOException("YouTube HTTP ${resp.code} for $browseId")
            JSONObject(text)
        }
        if (InnertubeParse.loggedOut(json)) { signOut(); throw UserFacingException(I18n.s.youtubeSignInAgain) }
        json
    }

    private fun clientVersion(): String =
        runCatching { YoutubeParsingHelper.getClientVersion() }.getOrNull() ?: FALLBACK_CLIENT_VERSION

    // ---- subscriptions fallback: remembered channels + public RSS ----

    private var lastSync: Long
        get() = prefs.getLong(KEY_SYNCED, 0)
        set(v) { prefs.edit().putLong(KEY_SYNCED, v).apply() }

    /** Re-reads the subscribed channels at most once a day (a few web API pages). */
    private suspend fun syncChannelsIfDue() {
        if (System.currentTimeMillis() - lastSync < SYNC_MS) return
        runCatching {
            val all = LinkedHashMap<String, String>()
            var cursor: String? = null
            var pages = 0
            do {
                val j = browse("FEchannels", cursor)
                InnertubeParse.channels(j).forEach { (id, name) -> all.putIfAbsent(id, name) }
                cursor = InnertubeParse.continuation(j)
            } while (cursor != null && ++pages < 20)
            if (all.isNotEmpty()) saveChannels(all.map { it.key to it.value })
            lastSync = System.currentTimeMillis()
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "channel sync failed", it) }
    }

    /** Newest uploads of [RSS_PAGE] remembered channels from [offset], merged newest first. */
    private suspend fun rssPage(offset: Int): FeedPage = coroutineScope {
        val list = channels
        if (list.isEmpty()) throw UserFacingException(I18n.s.youtubeSignInAgain)
        val slice = list.drop(offset).take(RSS_PAGE)
        val gate = Semaphore(6)
        val entries = slice.map { (id, _) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val req = Request.Builder().url("$ORIGIN/feeds/videos.xml?channel_id=$id").header("User-Agent", DESKTOP_UA).build()
                        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
                    }.getOrDefault("").let { InnertubeParse.rss(it, SourceId.YOUTUBE).take(4) }
                }
            }
        }.awaitAll().flatten()
        val items = entries.sortedByDescending { it.published }.map { it.item }
        FeedPage(items, (offset + RSS_PAGE).takeIf { it < list.size }?.let { "$RSS$it" })
    }

    private fun saveChannels(list: List<Pair<String, String>>) {
        channels = list
        val arr = JSONArray()
        list.forEach { (id, name) -> arr.put(JSONObject().put("id", id).put("name", name)) }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    private fun readChannels(): List<Pair<String, String>> {
        val arr = runCatching { JSONArray(prefs.getString(KEY_CHANNELS, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optString("id") to it.optString("name") }
            .filter { it.first.startsWith("UC") }
    }

    private companion object {
        const val TAG = "YouTubeAccount"
        const val ORIGIN = "https://www.youtube.com"
        /** Used only if NewPipe can't read the current web client version. */
        const val FALLBACK_CLIENT_VERSION = "2.20251110.01.00"
        const val RSS = "rss:"
        const val RSS_PAGE = 25
        const val MAX_PLAYLIST = 500
        const val SYNC_MS = 24 * 60 * 60_000L
        const val KEY_CHANNELS = "channels"
        const val KEY_SYNCED = "channels_synced"
        val JSON = "application/json".toMediaType()
    }
}
