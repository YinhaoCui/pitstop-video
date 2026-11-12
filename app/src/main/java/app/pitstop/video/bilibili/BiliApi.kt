package app.pitstop.video.bilibili

import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.SubtitleTrack
import app.pitstop.video.player.DeviceCodecs
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.getString
import app.pitstop.video.core.parseClock
import app.pitstop.video.player.StreamSelector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class BiliException(val code: Int, message: String) : Exception("Bilibili error $code: $message")

/** Minimal client for the public bilibili.com web API. */
class BiliApi(
    private val http: OkHttpClient,
    private val cookies: BiliCookieJar,
    /** Where converted subtitle files are written. */
    private val cacheDir: java.io.File,
) {
    private val keyLock = Mutex()
    private var mixinKey: String? = null
    private var mixinKeyAt = 0L

    data class Profile(val loggedIn: Boolean, val name: String?)
    data class QrCode(val url: String, val key: String)
    enum class QrState { WAITING, SCANNED, EXPIRED, DONE }

    private suspend fun raw(url: String): JSONObject = JSONObject(http.getString(Request.Builder().url(url).build()))

    private suspend fun data(url: String): JSONObject {
        val json = raw(url)
        val code = json.optInt("code")
        if (code != 0) throw BiliException(code, json.optString("message"))
        return json.optJSONObject("data") ?: JSONObject()
    }

    /** Anonymous device cookies; several endpoints return -412 / empty results without them. */
    private suspend fun ensureDeviceId() {
        if (cookies.get("buvid3") != null) return
        runCatching {
            val d = data("https://api.bilibili.com/x/frontend/finger/spi")
            cookies.putAll(mapOf("buvid3" to d.optString("b_3"), "buvid4" to d.optString("b_4")))
        }
    }

    private suspend fun wbiKey(): String = keyLock.withLock {
        val cached = mixinKey
        if (cached != null && System.currentTimeMillis() - mixinKeyAt < 6 * 3600_000) return cached
        // /nav answers code -101 when signed out but still carries the wbi keys.
        val img = raw("https://api.bilibili.com/x/web-interface/nav").getJSONObject("data").getJSONObject("wbi_img")
        Wbi.mixinKey(Wbi.keyFromUrl(img.getString("img_url")), Wbi.keyFromUrl(img.getString("sub_url"))).also {
            mixinKey = it
            mixinKeyAt = System.currentTimeMillis()
        }
    }

    private suspend fun wbiData(base: String, params: Map<String, String>): JSONObject {
        ensureDeviceId()
        val query = Wbi.sign(params, wbiKey(), System.currentTimeMillis() / 1000)
        return data("$base?$query")
    }

    suspend fun profile(): Profile {
        val d = raw("https://api.bilibili.com/x/web-interface/nav").optJSONObject("data") ?: JSONObject()
        val loggedIn = d.optBoolean("isLogin")
        return Profile(loggedIn, d.optString("uname").takeIf { loggedIn && it.isNotEmpty() })
    }

    suspend fun popular(): List<VideoItem> {
        ensureDeviceId()
        val list = data("https://api.bilibili.com/x/web-interface/popular?ps=30&pn=1").optJSONArray("list")
        return list.objects().map(::archiveToItem)
    }

    suspend fun recommended(): List<VideoItem> {
        val items = wbiData(
            "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd",
            mapOf("ps" to "30", "fresh_type" to "4", "fresh_idx" to "1"),
        ).optJSONArray("item")
        return items.objects().filter { it.optString("bvid").isNotEmpty() }.map(::archiveToItem)
    }

    suspend fun search(keyword: String): List<VideoItem> {
        val result = wbiData(
            "https://api.bilibili.com/x/web-interface/wbi/search/type",
            mapOf("search_type" to "video", "keyword" to keyword, "page" to "1"),
        ).optJSONArray("result")
        return result.objects().filter { it.optString("bvid").isNotEmpty() }.map {
            VideoItem(
                source = SourceId.BILIBILI,
                id = it.getString("bvid"),
                title = BiliText.clean(it.optString("title")),
                author = it.optString("author"),
                thumbnailUrl = BiliText.thumb(it.optString("pic")),
                durationSec = parseClock(it.optString("duration")),
            )
        }
    }

    /** Video posts from followed uploaders (requires login). */
    suspend fun following(): List<VideoItem> {
        if (!cookies.loggedIn.value) throw UserFacingException("Sign in to Bilibili on the phone first")
        val items = data("https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=video&page=1")
            .optJSONArray("items")
        return items.objects().mapNotNull { post ->
            val modules = post.optJSONObject("modules") ?: return@mapNotNull null
            val archive = modules.optJSONObject("module_dynamic")?.optJSONObject("major")?.optJSONObject("archive")
                ?: return@mapNotNull null
            VideoItem(
                source = SourceId.BILIBILI,
                id = archive.optString("bvid").ifEmpty { return@mapNotNull null },
                title = archive.optString("title"),
                author = modules.optJSONObject("module_author")?.optString("name"),
                thumbnailUrl = BiliText.thumb(archive.optString("cover")),
                durationSec = parseClock(archive.optString("duration_text")),
            )
        }
    }

    data class Page(val cid: Long, val page: Int, val name: String, val durationSec: Long)
    data class View(val title: String, val author: String?, val cover: String?, val cid: Long, val pages: List<Page>)

    suspend fun view(bvid: String): View {
        val d = data("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        return View(
            title = BiliText.clean(d.optString("title")),
            author = d.optJSONObject("owner")?.optString("name"),
            cover = BiliText.thumb(d.optString("pic")),
            cid = d.getLong("cid"),
            pages = d.optJSONArray("pages").objects().map {
                Page(it.getLong("cid"), it.optInt("page"), it.optString("part"), it.optLong("duration"))
            },
        )
    }

    suspend fun cidOf(bvid: String): Long = view(bvid).cid

    /** Uploaded + AI subtitles for one part, converted to WebVTT files. AI ones usually need login. */
    suspend fun subtitles(bvid: String, cid: Long): List<SubtitleTrack> {
        val list = runCatching {
            wbiData("https://api.bilibili.com/x/player/wbi/v2", mapOf("bvid" to bvid, "cid" to cid.toString()))
                .optJSONObject("subtitle")?.optJSONArray("subtitles").objects()
        }.getOrDefault(emptyList())
        return list.mapNotNull { s ->
            val raw = s.optString("subtitle_url").ifEmpty { return@mapNotNull null }
            val url = if (raw.startsWith("//")) "https:$raw" else raw.replace("http://", "https://")
            val lan = s.optString("lan").ifEmpty { "und" }
            runCatching {
                val vtt = BiliSubtitles.jsonToVtt(http.getString(Request.Builder().url(url).build()))
                val file = java.io.File(cacheDir, "bili-sub-$cid-$lan.vtt").apply { writeText(vtt) }
                SubtitleTrack(
                    uri = android.net.Uri.fromFile(file).toString(),
                    language = BiliSubtitles.language(lan),
                    label = s.optString("lan_doc").ifEmpty { lan },
                    autoGenerated = lan.startsWith("ai-"),
                )
            }.getOrNull()
        }
    }

    suspend fun playUrl(bvid: String, cid: Long): PlayableStream {
        val d = wbiData(
            "https://api.bilibili.com/x/player/wbi/playurl",
            mapOf("bvid" to bvid, "cid" to cid.toString(), "qn" to "0", "fnval" to "4048", "fnver" to "0", "fourk" to "1"),
        )
        val headers = mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to DESKTOP_UA)
        val overseas = isOverseas()
        fun url(o: JSONObject): String {
            val all = listOf(o.optString("baseUrl").ifEmpty { o.optString("base_url") }) +
                (o.optJSONArray("backupUrl") ?: o.optJSONArray("backup_url")).strings()
            return BiliCdn.choose(all, overseas) ?: all.first()
        }
        val dash = d.optJSONObject("dash")
        if (dash != null) {
            val videos = dash.optJSONArray("video").objects().map {
                StreamSelector.VideoTrack(
                    width = it.optInt("width"),
                    url = url(it),
                    height = it.optInt("height").takeIf { h -> h > 0 } ?: StreamSelector.biliQnHeight(it.optInt("id")),
                    codec = StreamSelector.biliCodec(it.optInt("codecid")),
                    bandwidth = it.optLong("bandwidth"),
                )
            }
            val audios = dash.optJSONArray("audio").objects().map {
                StreamSelector.AudioTrack(url(it), it.optLong("bandwidth"))
            }
            val v = StreamSelector.pickVideo(videos, StreamPolicy.maxHeight, DeviceCodecs::canPlay)
            if (v != null) return PlayableStream(v.url, StreamSelector.pickAudio(audios)?.url, headers, subtitles = subtitles(bvid, cid))
        }
        val durl = d.optJSONArray("durl").objects().firstOrNull()?.let { o ->
            val all = listOf(o.optString("url")) + o.optJSONArray("backup_url").strings()
            BiliCdn.choose(all, overseas)
        }
            ?: throw UserFacingException("No playable stream for $bvid (it may need a Bilibili login or be region-locked)")
        return PlayableStream(durl, headers = headers, subtitles = subtitles(bvid, cid))
    }

    private var overseas: Boolean? = null

    /** Bilibili's own IP geolocation; country_code 86 is mainland China. Defaults to overseas on failure. */
    private suspend fun isOverseas(): Boolean = overseas ?: runCatching {
        data("https://api.bilibili.com/x/web-interface/zone").optInt("country_code", 0) != 86
    }.getOrDefault(true).also { overseas = it }

    suspend fun qrGenerate(): QrCode {
        val d = data("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
        return QrCode(d.getString("url"), d.getString("qrcode_key"))
    }

    suspend fun qrPoll(key: String): QrState {
        val d = data("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$key")
        return when (d.optInt("code")) {
            0 -> {
                // Cookies arrive via Set-Cookie (stored by the jar); the redirect URL carries them too.
                val url = d.optString("url")
                if (url.isNotEmpty()) runCatching {
                    val parsed = url.toHttpUrl()
                    cookies.putAll(
                        listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
                            .mapNotNull { k -> parsed.queryParameter(k)?.let { k to it } }.toMap()
                    )
                }
                QrState.DONE
            }
            86090 -> QrState.SCANNED
            86038 -> QrState.EXPIRED
            else -> QrState.WAITING
        }
    }

    private fun archiveToItem(o: JSONObject) = VideoItem(
        source = SourceId.BILIBILI,
        id = o.getString("bvid"),
        title = BiliText.clean(o.optString("title")),
        author = o.optJSONObject("owner")?.optString("name"),
        thumbnailUrl = BiliText.thumb(o.optString("pic")),
        durationSec = o.optLong("duration").takeIf { it > 0 },
        extra = o.optLong("cid").takeIf { it > 0 }?.toString(),
    )
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
