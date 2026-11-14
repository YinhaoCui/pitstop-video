package app.pitstop.video.netease

import android.content.SharedPreferences
import app.pitstop.video.core.Category
import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.SiteCookieJar
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.getString
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class NeteaseCookieJar(prefs: SharedPreferences) : SiteCookieJar(prefs, "163.com", "MUSIC_U")

class NeteaseException(val code: Int, message: String) : Exception("NetEase error $code: $message")

/** Minimal client for music.163.com's web API ("weapi", see [NeteaseCrypto]). */
class NeteaseApi(private val http: OkHttpClient, private val cookies: NeteaseCookieJar) {

    enum class QrState { WAITING, SCANNED, EXPIRED, DONE }

    private suspend fun weapi(path: String, body: JSONObject): JSONObject {
        body.put("csrf_token", cookies.get("__csrf").orEmpty())
        val enc = NeteaseCrypto.weapi(body.toString())
        val req = Request.Builder()
            .url("https://music.163.com/weapi/$path")
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .post(FormBody.Builder().add("params", enc.params).add("encSecKey", enc.encSecKey).build())
            .build()
        val json = JSONObject(http.getString(req))
        val code = json.optInt("code", 200)
        if (code != 200 && path != QR_POLL) throw NeteaseException(code, json.optString("message", json.optString("msg")))
        return json
    }

    /** 热歌榜 / 新歌榜 / 飙升榜, or any playlist: every track in order (up to [limit]). */
    suspend fun playlistTracks(id: Long, limit: Int = 1000): List<VideoItem> {
        val (ids, tracks) = playlistIds(id, fresh = true)
        if (tracks.size >= minOf(ids.size, limit)) return tracks.take(limit)
        return ids.take(limit).chunked(DETAIL_CHUNK).flatMap { songDetails(it) }
    }

    /** One page of a playlist's (or chart's) tracks; cursor = offset into its track list. */
    suspend fun playlistTracksPage(id: Long, cursor: String?): FeedPage {
        val offset = NeteaseParse.offset(cursor)
        val (ids, tracks) = playlistIds(id, fresh = cursor == null)
        val end = minOf(ids.size, offset + PAGE)
        if (offset >= end) return FeedPage(emptyList())
        // Charts come with all their tracks; big playlists only with ids (and ~10 tracks) when signed out.
        val items = if (tracks.size >= end) tracks.subList(offset, end) else songDetails(ids.subList(offset, end))
        return FeedPage(items, NeteaseParse.next(offset, end - offset, total = ids.size))
    }

    /** (track ids, full tracks if the server sent them), cached briefly so later pages skip the detail call. */
    private suspend fun playlistIds(id: Long, fresh: Boolean = false): Pair<List<Long>, List<VideoItem>> {
        if (!fresh) synchronized(idCache) { idCache[id] }?.let { return it }
        val pl = weapi("v6/playlist/detail", JSONObject().put("id", id).put("n", 100000).put("s", 8)).optJSONObject("playlist")
        val tracks = NeteaseParse.songs(pl?.optJSONArray("tracks"))
        val ids = NeteaseParse.trackIds(pl).ifEmpty { tracks.mapNotNull { it.id.toLongOrNull() } }
        val entry = ids to tracks
        synchronized(idCache) {
            idCache[id] = entry
            while (idCache.size > 8) idCache.remove(idCache.keys.first())
        }
        return entry
    }

    private val idCache = LinkedHashMap<Long, Pair<List<Long>, List<VideoItem>>>()

    private suspend fun songDetails(ids: List<Long>): List<VideoItem> {
        if (ids.isEmpty()) return emptyList()
        val c = JSONArray(ids.map { JSONObject().put("id", it) }).toString()
        val byId = NeteaseParse.songs(weapi("v3/song/detail", JSONObject().put("c", c)).optJSONArray("songs")).associateBy { it.id }
        return ids.mapNotNull { byId[it.toString()] }
    }

    suspend fun search(query: String): List<VideoItem> = searchSongs(query, null).items

    /** Song search, endless; cursor = offset. (cloudsearch/get/web refuses anonymous calls now; /pc works.) */
    suspend fun searchSongs(query: String, cursor: String?): FeedPage {
        val offset = NeteaseParse.offset(cursor)
        val r = weapi("cloudsearch/pc", JSONObject().put("s", query).put("type", 1).put("limit", PAGE).put("offset", offset))
            .optJSONObject("result")
        val songs = NeteaseParse.songs(r?.optJSONArray("songs"))
        return FeedPage(songs, NeteaseParse.next(offset, songs.size, total = r?.optInt("songCount")?.takeIf { it > 0 }))
    }

    /** The best few playlists matching [query] (shown above the songs on the first search page). */
    suspend fun searchPlaylists(query: String, limit: Int = 6): List<VideoItem> =
        NeteaseParse.playlists(
            weapi("cloudsearch/pc", JSONObject().put("s", query).put("type", 1000).put("limit", limit).put("offset", 0))
                .optJSONObject("result")?.optJSONArray("playlists"),
            I18n.s::tracks,
        )

    suspend fun suggestions(query: String): List<String> =
        NeteaseParse.suggestions(weapi("search/suggest/keyword", JSONObject().put("s", query)))

    /** The official charts and the playlist tags (by group). */
    suspend fun categories(): List<Category> {
        val charts = runCatching { weapi("toplist", JSONObject()) }.getOrNull()
        val catalogue = if (charts == null) weapi("playlist/catalogue", JSONObject()) // let this one's error through
        else runCatching { weapi("playlist/catalogue", JSONObject()) }.getOrNull()
        return NeteaseParse.categories(catalogue, charts, I18n.s.neteaseCharts)
    }

    /** Hot playlists with one tag ("全部" = all), endless; cursor = offset. */
    suspend fun tagPlaylists(tag: String, cursor: String?): FeedPage {
        val offset = NeteaseParse.offset(cursor)
        val j = weapi(
            "playlist/list",
            JSONObject().put("cat", tag).put("order", "hot").put("limit", PAGE).put("offset", offset).put("total", true),
        )
        val items = NeteaseParse.playlists(j.optJSONArray("playlists"), I18n.s::tracks)
        return FeedPage(items, NeteaseParse.next(offset, items.size, total = j.optInt("total").takeIf { it > 0 }, more = j.more()))
    }

    /** One artist's songs, most popular first, endless; cursor = offset. */
    suspend fun artistSongs(artistId: Long, cursor: String?): FeedPage {
        val offset = NeteaseParse.offset(cursor)
        val j = weapi(
            "v1/artist/songs",
            JSONObject().put("id", artistId).put("private_cloud", "true").put("work_type", 1).put("order", "hot")
                .put("offset", offset).put("limit", PAGE),
        )
        val songs = NeteaseParse.songs(j.optJSONArray("songs"))
        return FeedPage(songs, NeteaseParse.next(offset, songs.size, total = j.optInt("total").takeIf { it > 0 }, more = j.more()))
    }

    private fun JSONObject.more(): Boolean? = if (has("more")) optBoolean("more") else null

    /** Recommended playlists (works signed out). */
    suspend fun recommendedPlaylists(): List<VideoItem> =
        NeteaseParse.playlists(
            weapi("personalized/playlist", JSONObject().put("limit", 30).put("total", true).put("n", 1000)).optJSONArray("result"),
            I18n.s::tracks,
        )

    suspend fun dailySongs(): List<VideoItem> {
        requireLogin()
        return NeteaseParse.songs(weapi("v3/discovery/recommend/songs", JSONObject()).optJSONObject("data")?.optJSONArray("dailySongs"))
    }

    suspend fun myPlaylists(): List<VideoItem> = myPlaylistsPage(null).items

    /** The signed-in user's own and saved playlists, endless; cursor = offset. */
    suspend fun myPlaylistsPage(cursor: String?): FeedPage {
        requireLogin()
        val uid = account()?.first ?: throw UserFacingException(I18n.s.needNeteaseLogin)
        val offset = NeteaseParse.offset(cursor)
        val j = weapi("user/playlist", JSONObject().put("uid", uid).put("limit", PAGE).put("offset", offset).put("includeVideo", true))
        val items = NeteaseParse.playlists(j.optJSONArray("playlist"), I18n.s::tracks)
        return FeedPage(items, NeteaseParse.next(offset, items.size, more = j.more()))
    }

    /** (user id, nickname) of the signed-in account, or null. */
    suspend fun account(): Pair<Long, String?>? {
        if (!cookies.signedIn.value) return null
        val j = weapi("w/nuser/account/get", JSONObject())
        val id = j.optJSONObject("account")?.optLong("id")?.takeIf { it > 0 } ?: return null
        return id to j.optJSONObject("profile")?.optString("nickname")
    }

    /** Stream URL; null url = VIP-only / region-locked / removed. */
    suspend fun songUrl(id: Long): String {
        // exhigh = 320 kbps (signed in); standard = 128 kbps, also used on mobile data to save data.
        val level = if (cookies.signedIn.value && !app.pitstop.video.core.StreamPolicy.metered) "exhigh" else "standard"
        val d = weapi(
            "song/enhance/player/url/v1",
            JSONObject().put("ids", "[$id]").put("level", level).put("encodeType", "aac"),
        ).optJSONArray("data").objects().firstOrNull()
        val url = d?.optString("url").orEmpty()
        // A 30-second "trial" clip is all you get for VIP songs without VIP; treat as unavailable.
        if (url.isEmpty() || url == "null" || d?.optJSONObject("freeTrialInfo") != null) throw UserFacingException(I18n.s.neteaseVip)
        return url
    }

    suspend fun qrKey(): String = weapi("login/qrcode/unikey", JSONObject().put("type", 1)).getString("unikey")

    fun qrContent(key: String) = "https://music.163.com/login?codekey=$key"

    suspend fun qrPoll(key: String): QrState = when (weapi(QR_POLL, JSONObject().put("key", key).put("type", 1)).optInt("code")) {
        803 -> QrState.DONE // cookies (MUSIC_U, __csrf) arrive through Set-Cookie into the jar
        802 -> QrState.SCANNED
        800 -> QrState.EXPIRED
        else -> QrState.WAITING
    }

    fun logout() = cookies.clear()

    private fun requireLogin() {
        if (!cookies.signedIn.value) throw UserFacingException(I18n.s.needNeteaseLogin)
    }

    companion object {
        private const val QR_POLL = "login/qrcode/client/login"
        const val TOPLIST_HOT = 3778678L
        const val TOPLIST_NEW = 3779629L
        const val TOPLIST_RISING = 19723756L
        /** Page size for every endless list. */
        const val PAGE = 50
        /** Ids per v3/song/detail call. */
        private const val DETAIL_CHUNK = 200
    }
}
