package app.pitstop.video.netease

import android.content.SharedPreferences
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.I18n
import app.pitstop.video.core.SiteCookieJar
import app.pitstop.video.core.SourceId
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

    /** 热歌榜 / 新歌榜 / 飙升榜, or any playlist: the tracks in order. */
    suspend fun playlistTracks(id: Long): List<VideoItem> {
        val pl = weapi("v6/playlist/detail", JSONObject().put("id", id).put("n", 200).put("s", 0)).optJSONObject("playlist")
        val tracks = pl?.optJSONArray("tracks").objects()
        if (tracks.isNotEmpty()) return tracks.map(::song)
        // Big playlists only return ids; fetch details in one go.
        val ids = pl?.optJSONArray("trackIds").objects().map { it.optLong("id") }.take(200)
        return songDetails(ids)
    }

    private suspend fun songDetails(ids: List<Long>): List<VideoItem> {
        if (ids.isEmpty()) return emptyList()
        val c = JSONArray(ids.map { JSONObject().put("id", it) }).toString()
        return weapi("v3/song/detail", JSONObject().put("c", c)).optJSONArray("songs").objects().map(::song)
    }

    suspend fun search(query: String): List<VideoItem> =
        weapi("cloudsearch/get/web", JSONObject().put("s", query).put("type", 1).put("limit", 40).put("offset", 0))
            .optJSONObject("result")?.optJSONArray("songs").objects().map(::song)

    /** Recommended playlists (works signed out). */
    suspend fun recommendedPlaylists(): List<VideoItem> =
        weapi("personalized/playlist", JSONObject().put("limit", 30).put("total", true).put("n", 1000))
            .optJSONArray("result").objects().map { playlist(it, it.optString("picUrl")) }

    suspend fun dailySongs(): List<VideoItem> {
        requireLogin()
        return weapi("v3/discovery/recommend/songs", JSONObject()).optJSONObject("data")?.optJSONArray("dailySongs").objects().map(::song)
    }

    suspend fun myPlaylists(): List<VideoItem> {
        requireLogin()
        val uid = account()?.first ?: throw UserFacingException(I18n.s.needNeteaseLogin)
        return weapi("user/playlist", JSONObject().put("uid", uid).put("limit", 100).put("offset", 0).put("includeVideo", true))
            .optJSONArray("playlist").objects().map { playlist(it, it.optString("coverImgUrl")) }
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

    private fun song(o: JSONObject) = VideoItem(
        source = SourceId.NETEASE,
        id = o.optLong("id").toString(),
        title = o.optString("name"),
        author = (o.optJSONArray("ar") ?: o.optJSONArray("artists")).objects().joinToString(" / ") { it.optString("name") }.ifEmpty { null },
        thumbnailUrl = (o.optJSONObject("al") ?: o.optJSONObject("album"))?.optString("picUrl")?.ifEmpty { null }?.let { https(it) + "?param=300y300" },
        durationSec = (o.optLong("dt").takeIf { it > 0 } ?: o.optLong("duration").takeIf { it > 0 })?.div(1000),
    )

    private fun playlist(o: JSONObject, cover: String) = VideoItem(
        source = SourceId.NETEASE,
        id = COLLECTION_PREFIX + o.optLong("id"),
        title = o.optString("name"),
        author = I18n.s.tracks(o.optInt("trackCount")),
        thumbnailUrl = cover.ifEmpty { null }?.let { https(it) + "?param=300y300" },
    )

    companion object {
        private const val QR_POLL = "login/qrcode/client/login"
        const val TOPLIST_HOT = 3778678L
        const val TOPLIST_NEW = 3779629L
        const val TOPLIST_RISING = 19723756L
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** NetEase hands out http:// image links; Android blocks cleartext, and the CDN serves https too. */
private fun https(url: String) = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
