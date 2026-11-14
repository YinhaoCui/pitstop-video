package app.pitstop.video.bilibili

import app.pitstop.video.core.I18n
import app.pitstop.video.core.FeedPage
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

    suspend fun popular(): List<VideoItem> = popularPage(1).items

    /** Popular (热门), 30 per page; the cursor is the next page number until Bilibili says no_more. */
    suspend fun popularPage(page: Int): FeedPage {
        ensureDeviceId()
        val d = data("https://api.bilibili.com/x/web-interface/popular?ps=30&pn=$page")
        val items = d.optJSONArray("list").objects().map(::archiveToItem)
        return FeedPage(items, (page + 1).toString().takeIf { items.isNotEmpty() && !d.optBoolean("no_more") })
    }

    suspend fun recommended(): List<VideoItem> = recommendedPage(1).items

    /** Homepage recommendations: an endless stream, each "refresh" index gives a new batch. */
    suspend fun recommendedPage(index: Int): FeedPage {
        val items = wbiData(
            "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd",
            mapOf("ps" to "30", "fresh_type" to "4", "fresh_idx" to "$index", "fresh_idx_1h" to "$index", "brush" to "$index"),
        ).optJSONArray("item")
        val list = items.objects().filter { it.optString("bvid").isNotEmpty() }.map(::archiveToItem)
        return FeedPage(list, (index + 1).toString().takeIf { list.isNotEmpty() })
    }

    /** Video search; [tids] narrows to a zone (3 = music). */
    suspend fun search(keyword: String, tids: String? = null, source: SourceId = SourceId.BILIBILI): List<VideoItem> =
        searchPage(keyword, 1, tids, source).items

    /** One page (20 videos) of a video search; the cursor is the next page number until `numPages`. */
    suspend fun searchPage(keyword: String, page: Int, tids: String? = null, source: SourceId = SourceId.BILIBILI): FeedPage {
        val params = mapOf("search_type" to "video", "keyword" to keyword, "page" to "$page") +
            (tids?.let { mapOf("tids" to it) } ?: emptyMap())
        return BiliZones.searchPage(wbiData("https://api.bilibili.com/x/web-interface/wbi/search/type", params), page, source)
    }

    /** Search-box completions (the web site's suggest service). */
    suspend fun suggestions(term: String): List<String> {
        if (term.isBlank()) return emptyList()
        val url = "https://s.search.bilibili.com/main/suggest?main_ver=v1&term=" + java.net.URLEncoder.encode(term.trim(), "UTF-8")
        return BiliZones.suggestions(raw(url))
    }

    private val zoneLock = Mutex()
    private var zoneCache: List<BiliZones.Zone>? = null

    /** The site's video zones (分区) with their sub-zones, as the web navigation lists them (kv-frontend config). */
    suspend fun zones(): List<BiliZones.Zone> = zoneLock.withLock {
        zoneCache ?: run {
            val json = raw("https://api.bilibili.com/x/kv-frontend/namespace/data?appKey=333.1339&nscode=10")
            if (json.optInt("code") != 0) throw BiliException(json.optInt("code"), json.optString("message"))
            BiliZones.parseZones(json.optJSONObject("data")?.optJSONObject("data")).also { if (it.isNotEmpty()) zoneCache = it }
        }
    }

    /**
     * A top-level zone's recommendation feed (what bilibili.com/c/<zone> shows), 15 per call; [index] starts at 1
     * and keeps going until Bilibili answers "no more" (code 62013). Sub-zone ids are rejected (-400) by this endpoint.
     */
    suspend fun zonePage(tid: Int, index: Int, source: SourceId = SourceId.BILIBILI): FeedPage {
        ensureDeviceId()
        val json = raw(
            "https://api.bilibili.com/x/web-interface/region/feed/rcmd?display_id=$index&request_cnt=15" +
                "&from_region=$tid&device=web&plat=30&web_location=333.40138",
        )
        return BiliZones.regionPage(json, index, source)
    }

    /**
     * Video posts from followed uploaders (requires login), about 20 per page. [offset] is the cursor
     * Bilibili returned with the previous page; the result carries the next one (null when there are no more).
     */
    suspend fun following(offset: String? = null, page: Int = 1): FeedPage {
        if (!cookies.loggedIn.value) throw UserFacingException(I18n.s.needBiliLogin)
        val url = "https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=video&page=$page" +
            (offset?.let { "&offset=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        val d = data(url)
        val next = d.optString("offset").takeIf { d.optBoolean("has_more") && it.isNotEmpty() && it != offset }
        return FeedPage(parseFollowing(d.optJSONArray("items")), next)
    }

    private fun parseFollowing(items: org.json.JSONArray?): List<VideoItem> {
        return items.objects().mapNotNull { post ->
            val modules = post.optJSONObject("modules") ?: return@mapNotNull null
            val archive = modules.optJSONObject("module_dynamic")?.optJSONObject("major")?.optJSONObject("archive")
                ?: return@mapNotNull null
            VideoItem(
                source = SourceId.BILIBILI,
                id = archive.optString("bvid").ifEmpty { return@mapNotNull null },
                title = archive.optString("title"),
                author = modules.optJSONObject("module_author")?.optString("name"),
                authorId = modules.optJSONObject("module_author")?.optLong("mid")?.takeIf { it > 0 }?.toString(),
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
            if (StreamPolicy.audioOnly) StreamSelector.pickAudio(audios, StreamPolicy.audioMaxBps)?.let {
                return PlayableStream(it.url, headers = headers, subtitles = subtitles(bvid, cid), audioOnly = true)
            }
            val v = StreamSelector.pickVideo(videos, StreamPolicy.maxHeight, DeviceCodecs::canPlay)
            if (v != null) return PlayableStream(v.url, StreamSelector.pickAudio(audios, StreamPolicy.audioMaxBps)?.url, headers, subtitles = subtitles(bvid, cid))
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

    /** An uploader's latest videos (space page). */
    suspend fun uploaderVideos(mid: String, source: SourceId): List<VideoItem> = uploaderPage(mid, 1, source).items

    /**
     * One page (30, newest first) of an uploader's videos; the cursor is the next page number while
     * `page.count` says there are more. The dm_* fields are what the web page sends; without them Bilibili answers -352.
     */
    suspend fun uploaderPage(mid: String, page: Int, source: SourceId): FeedPage {
        val d = wbiData(
            "https://api.bilibili.com/x/space/wbi/arc/search",
            mapOf(
                "mid" to mid, "ps" to "$UPLOADER_PAGE_SIZE", "pn" to "$page", "order" to "pubdate", "platform" to "web",
                "dm_img_list" to "[]", "dm_img_str" to "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ",
                "dm_cover_img_str" to "QU5HTEUgKEdvb2dsZSwgVnVsa2FuIDEuMy4wIChTd2lmdFNoYWRlciBEZXZpY2UgKFN1Ymdhem9yb2...",
            ),
        )
        return BiliZones.uploaderPage(d, mid, page, UPLOADER_PAGE_SIZE, source)
    }

    /**
     * A public favourites folder (收藏夹) for playlist import: (folder name, every video), 20 per request.
     * Private folders answer an error, shown to the user.
     */
    suspend fun favFolder(mediaId: Long, source: SourceId, limit: Int = 1000): Pair<String, List<VideoItem>> {
        val out = mutableListOf<VideoItem>()
        var name = ""
        var page = 1
        while (out.size < limit) {
            val d = data("https://api.bilibili.com/x/v3/fav/resource/list?media_id=$mediaId&pn=$page&ps=20&platform=web")
            name = d.optJSONObject("info")?.optString("title").orEmpty().ifEmpty { name }
            d.optJSONArray("medias").objects().forEach { m ->
                val bvid = m.optString("bvid").ifEmpty { return@forEach }
                out += VideoItem(
                    source = source, id = bvid, title = BiliText.clean(m.optString("title")),
                    author = m.optJSONObject("upper")?.optString("name"),
                    authorId = m.optJSONObject("upper")?.optLong("mid")?.takeIf { it > 0 }?.toString(),
                    thumbnailUrl = BiliText.thumb(m.optString("cover")),
                    durationSec = m.optLong("duration").takeIf { it > 0 },
                )
            }
            if (!d.optBoolean("has_more")) break
            page++
        }
        return name to out
    }

    /** An uploader's collection (合集) for playlist import: (collection name, every video), 30 per request. */
    suspend fun season(mid: Long, seasonId: Long, source: SourceId, limit: Int = 1000): Pair<String, List<VideoItem>> {
        val out = mutableListOf<VideoItem>()
        var name = ""
        var page = 1
        while (out.size < limit) {
            val d = data("https://api.bilibili.com/x/polymer/web-space/seasons_archives_list?mid=$mid&season_id=$seasonId&page_num=$page&page_size=30")
            name = d.optJSONObject("meta")?.optString("name").orEmpty().ifEmpty { name }
            val got = d.optJSONArray("archives").objects()
            got.forEach { out += archiveToItem(it, source) }
            val total = d.optJSONObject("page")?.optInt("total") ?: 0
            if (got.isEmpty() || out.size >= total) break
            page++
        }
        return name to out
    }

    /** Music charts: the first page of the music center's comprehensive chart. */
    suspend fun musicRanking(): List<VideoItem> = musicRankingPage(1).items

    /**
     * The music center's comprehensive chart (综合榜, music.bilibili.com), 20 songs per page, a few hundred
     * deep; the cursor is the next page number until a page comes back empty. (ranking/v2?rid=3 has been
     * frozen since April 2025.)
     */
    suspend fun musicRankingPage(page: Int): FeedPage {
        ensureDeviceId()
        val d = data("$MUSIC_CENTER/music/comprehensive/web/rank?pn=$page&ps=20")
        val items = BiliZones.musicCenterItems(d.optJSONArray("list"), SourceId.BILI_MUSIC)
        return FeedPage(items, (page + 1).toString().takeIf { items.isNotEmpty() })
    }

    /** Music center hot songs (热歌榜, ~100) or new releases (新歌, ~30): one page each. */
    suspend fun musicCenterList(path: String): List<VideoItem> {
        ensureDeviceId()
        return BiliZones.musicCenterItems(data("$MUSIC_CENTER/$path").optJSONArray("list"), SourceId.BILI_MUSIC)
    }

    /** Best audio-only stream of a video: Hi-Res FLAC / Dolby when offered (needs login / VIP), else AAC. */
    suspend fun audioUrl(bvid: String, cid: Long): PlayableStream {
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
            ?: throw UserFacingException("No audio stream for $bvid (it may need a Bilibili login or be region-locked)")
        val plain = dash.optJSONArray("audio").objects()
        if (StreamPolicy.metered) {
            // Data saver: skip lossless / Dolby and take the best normal track within the mobile cap.
            val tracks = plain.map { StreamSelector.AudioTrack(url(it), it.optLong("bandwidth")) }
            StreamSelector.pickAudio(tracks, StreamPolicy.audioMaxBps)?.let { return PlayableStream(it.url, headers = headers) }
        }
        val flac = dash.optJSONObject("flac")?.optJSONObject("audio")
        val dolby = dash.optJSONObject("dolby")?.optJSONArray("audio").objects().firstOrNull()
        val best = flac ?: dolby ?: plain.maxByOrNull { it.optLong("bandwidth") }
            ?: throw UserFacingException("No audio stream for $bvid")
        return PlayableStream(url(best), headers = headers)
    }

    private fun archiveToItem(o: JSONObject, source: SourceId = SourceId.BILIBILI) = VideoItem(
        source = source,
        id = o.getString("bvid"),
        title = BiliText.clean(o.optString("title")),
        author = o.optJSONObject("owner")?.optString("name"),
        authorId = o.optJSONObject("owner")?.optLong("mid")?.takeIf { it > 0 }?.toString(),
        thumbnailUrl = BiliText.thumb(o.optString("pic")),
        durationSec = o.optLong("duration").takeIf { it > 0 },
        extra = o.optLong("cid").takeIf { it > 0 }?.toString(),
    )

    companion object {
        private const val UPLOADER_PAGE_SIZE = 30
        private const val MUSIC_CENTER = "https://api.bilibili.com/x/centralization/interface"
        const val MUSIC_HOT = "music/hot/rank?plat=2"
        const val MUSIC_NEW = "new/music?plat=2"
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Pure parsing for zones, zone feeds, search / uploader pages and suggestions (no Android types; unit-tested). */
object BiliZones {
    data class Zone(val tid: Int, val name: String, val route: String, val subs: List<Zone> = emptyList())

    /** What the zone feed answers once it has nothing more to give. */
    const val NO_MORE = 62013

    /**
     * The kv-frontend namespace map (`data.data`): "channel_list.sort" holds the zone order and
     * "channel_list.<route>" each zone, both as JSON strings. Channels without a zone id (番剧, 电影, 公益 ...)
     * are left out: they have no zone feed.
     */
    fun parseZones(ns: JSONObject?): List<Zone> {
        if (ns == null) return emptyList()
        val order = runCatching { JSONArray(ns.optString("channel_list.sort")) }.getOrNull().strings()
        val routes = order.ifEmpty {
            ns.keys().asSequence().filter { it.startsWith("channel_list.") }.map { it.removePrefix("channel_list.") }.toList()
        }
        return routes.mapNotNull { route ->
            val o = runCatching { JSONObject(ns.optString("channel_list.$route")) }.getOrNull() ?: return@mapNotNull null
            val tid = o.optInt("tid").takeIf { it > 0 } ?: return@mapNotNull null
            val subs = o.optJSONArray("sub").objects().mapNotNull { s ->
                s.optInt("tid").takeIf { it > 0 }?.let { Zone(it, s.optString("name"), s.optString("route")) }
            }
            Zone(tid, o.optString("name").ifEmpty { route }, o.optString("route").ifEmpty { route }, subs)
        }
    }

    /** region/feed/rcmd answer: `data.archives`; code 62013 ends the feed. */
    fun regionPage(json: JSONObject, index: Int, source: SourceId): FeedPage {
        val code = json.optInt("code")
        if (code == NO_MORE) return FeedPage(emptyList())
        if (code != 0) throw BiliException(code, json.optString("message"))
        val items = json.optJSONObject("data")?.optJSONArray("archives").objects().mapNotNull { o ->
            val bvid = o.optString("bvid").ifEmpty { return@mapNotNull null }
            val author = o.optJSONObject("author")
            VideoItem(
                source = source,
                id = bvid,
                title = BiliText.clean(o.optString("title")),
                author = author?.optString("name"),
                authorId = author?.optLong("mid")?.takeIf { it > 0 }?.toString(),
                thumbnailUrl = BiliText.thumb(o.optString("cover")),
                durationSec = o.optLong("duration").takeIf { it > 0 },
                extra = o.optLong("cid").takeIf { it > 0 }?.toString(),
            )
        }
        return FeedPage(items, (index + 1).toString().takeIf { items.isNotEmpty() })
    }

    /** wbi/search/type (search_type=video) `data`: 20 per page, `numPages` of them (at most 50). */
    fun searchPage(d: JSONObject, page: Int, source: SourceId): FeedPage {
        val items = d.optJSONArray("result").objects().filter { it.optString("bvid").isNotEmpty() }.map {
            VideoItem(
                source = source,
                id = it.getString("bvid"),
                title = BiliText.clean(it.optString("title")),
                author = it.optString("author"),
                authorId = it.optLong("mid").takeIf { m -> m > 0 }?.toString(),
                thumbnailUrl = BiliText.thumb(it.optString("pic")),
                durationSec = parseClock(it.optString("duration")),
            )
        }
        return FeedPage(items, (page + 1).toString().takeIf { items.isNotEmpty() && page < d.optInt("numPages") })
    }

    /** s.search.bilibili.com/main/suggest: `result.tag[].value` (`result` is empty when nothing matches). */
    fun suggestions(json: JSONObject): List<String> =
        json.optJSONObject("result")?.optJSONArray("tag").objects()
            .map { it.optString("value").trim() }.filter { it.isNotEmpty() }.distinct()

    /** space/wbi/arc/search `data`: `list.vlist` plus `page.count` (total videos). */
    fun uploaderPage(d: JSONObject, mid: String, page: Int, pageSize: Int, source: SourceId): FeedPage {
        val items = d.optJSONObject("list")?.optJSONArray("vlist").objects().filter { it.optString("bvid").isNotEmpty() }.map {
            VideoItem(
                source = source,
                id = it.getString("bvid"),
                title = BiliText.clean(it.optString("title")),
                author = it.optString("author"),
                thumbnailUrl = BiliText.thumb(it.optString("pic")),
                durationSec = parseClock(it.optString("length")),
                authorId = mid,
            )
        }
        val count = d.optJSONObject("page")?.optInt("count") ?: 0
        return FeedPage(items, (page + 1).toString().takeIf { items.isNotEmpty() && page.toLong() * pageSize < count })
    }

    /** Music center lists (comprehensive / hot / new): each song points at a video (bvid, cid). */
    fun musicCenterItems(list: JSONArray?, source: SourceId): List<VideoItem> = list.objects().mapNotNull { o ->
        val bvid = o.optString("bvid").ifEmpty { return@mapNotNull null }
        VideoItem(
            source = source,
            id = bvid,
            title = BiliText.clean(o.optString("music_title")),
            author = o.optString("author").ifEmpty { null },
            thumbnailUrl = BiliText.thumb(o.optString("cover")),
            extra = o.optString("cid").toLongOrNull()?.takeIf { it > 0 }?.toString(),
        )
    }
}
