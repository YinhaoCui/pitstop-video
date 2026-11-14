package app.pitstop.video.bilibili

import app.pitstop.video.core.Category
import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.categoryKey
import app.pitstop.video.core.getString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bilibili Live (live.bilibili.com). All endpoints are the ones the web site uses; the shared client
 * sends the bilibili.com cookies (buvid3 device id, SESSDATA when signed in) for api.live.bilibili.com too.
 */
class BiliLiveSource(private val http: OkHttpClient, private val cookies: BiliCookieJar) : VideoSource {
    override val id = SourceId.BILI_LIVE
    override val displayName get() = I18n.s.biliLive

    private val keyLock = Mutex()
    private var mixinKey: String? = null
    private var mixinKeyAt = 0L

    override fun feeds(): List<Feed> = buildList {
        add(Feed(FEED_RECOMMENDED, I18n.s.recommended))
        add(Feed(FEED_POPULAR, I18n.s.popular))
        if (cookies.loggedIn.value) add(Feed(FEED_FOLLOWING, I18n.s.following))
    }

    override suspend fun load(feed: Feed): List<VideoItem> = loadPage(feed, null).items

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        val page = cursor?.toIntOrNull() ?: 1
        feed.categoryKey()?.let { key -> BiliLiveParse.areaKey(key)?.let { (parent, area) -> return areaRooms(parent, area, page) } }
        return when (feed.key) {
            FEED_POPULAR -> popular(page)
            FEED_FOLLOWING -> following(page)
            else -> recommended(page)
        }
    }

    /** The live home page's "more recommendations": a fresh batch of ~12 rooms on every call. */
    private suspend fun recommended(page: Int): FeedPage {
        ensureDeviceId()
        val d = data("$LIVE_API/xlive/web-interface/v1/webMain/getMoreRecList?platform=web")
        val items = BiliLiveParse.rooms(d.optJSONArray("recommend_room_list"), I18n.s::viewers)
        return FeedPage(items, (page + 1).toString().takeIf { items.isNotEmpty() && page < MAX_RECOMMENDED_PAGES })
    }

    /** All live rooms by viewers. Signed out, Bilibili stops after about ten pages (code -101). */
    private suspend fun popular(page: Int): FeedPage {
        ensureDeviceId()
        val json = raw(
            "$LIVE_API/xlive/web-interface/v1/second/getListByArea?" +
                Wbi.sign(
                    mapOf("platform" to "web", "sort" to "online", "page_size" to "30", "page" to "$page"),
                    wbiKey(), System.currentTimeMillis() / 1000,
                ),
        )
        // -101 ("not signed in") just means the anonymous page limit was reached.
        if (json.optInt("code") == -101 && page > 1) return FeedPage(emptyList())
        val d = checked(json)
        val items = BiliLiveParse.rooms(d.optJSONArray("list"), I18n.s::viewers)
        return FeedPage(items, (page + 1).toString().takeIf { items.isNotEmpty() && d.optInt("has_more") == 1 })
    }

    /**
     * Followed streamers, 10 per server page, live ones first but not strictly; a few server pages
     * are read per call so a page of the feed is rarely empty.
     */
    private suspend fun following(startPage: Int): FeedPage {
        if (!cookies.loggedIn.value) throw UserFacingException(I18n.s.needBiliLogin)
        val out = mutableListOf<VideoItem>()
        var page = startPage
        var total = page
        while (page <= total && page < startPage + FOLLOWING_PAGES_PER_CALL) {
            val d = data("$LIVE_API/xlive/web-ucenter/user/following?page=$page&page_size=10&ignoreRecord=1&hit_ab=true")
            total = d.optInt("totalPage", page)
            out += BiliLiveParse.followingLive(d.optJSONArray("list"))
            page++
        }
        return FeedPage(out, page.toString().takeIf { page <= total })
    }

    /**
     * Live areas: parent areas (网游, 手游, 娱乐 ...) as groups, their areas as categories, each parent
     * starting with its "all" entry (area id 0). The list is cached for the session.
     */
    override suspend fun categories(): List<Category> = areaLock.withLock {
        areaCache ?: BiliLiveParse.areas(dataArray("$LIVE_API/room/v1/Area/getList?need_entrance=1&parent_id=0"))
            .also { if (it.isNotEmpty()) areaCache = it }
    }

    private val areaLock = Mutex()
    private var areaCache: List<Category>? = null

    /** Rooms live in one area by viewers, 30 per page, until the area's `count` is reached. */
    private suspend fun areaRooms(parent: String, area: String, page: Int): FeedPage {
        ensureDeviceId()
        val d = data(
            "$LIVE_API/room/v3/area/getRoomList?platform=web&parent_area_id=$parent&area_id=$area" +
                "&page=$page&page_size=$AREA_PAGE_SIZE&sort_type=online",
        )
        return BiliLiveParse.areaPage(d, page, AREA_PAGE_SIZE, I18n.s::viewers)
    }

    override suspend fun search(query: String): List<VideoItem> = searchPage(query, null).items

    /** Live room search, 40 per page; the cursor is the next page number until `numPages`. */
    override suspend fun searchPage(query: String, cursor: String?): FeedPage {
        ensureDeviceId()
        val page = cursor?.toIntOrNull() ?: 1
        val d = data(
            "https://api.bilibili.com/x/web-interface/wbi/search/type?" +
                Wbi.sign(
                    mapOf("search_type" to "live_room", "keyword" to query, "page" to "$page"),
                    wbiKey(), System.currentTimeMillis() / 1000,
                ),
        )
        return BiliLiveParse.searchPage(d, page, I18n.s::viewers)
    }

    /** Bilibili has no live-only suggest service; the site-wide one is used. */
    override suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val url = "https://s.search.bilibili.com/main/suggest?main_ver=v1&term=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return BiliZones.suggestions(raw(url))
    }

    override suspend fun resolve(item: VideoItem): PlayableStream {
        ensureDeviceId()
        val audioOnly = StreamPolicy.audioOnly
        val maxHeight = StreamPolicy.maxHeight
        val wanted = BiliLiveParse.pickQn(BiliLiveParse.STANDARD_QNS, maxHeight, audioOnly)
        var choice = BiliLiveParse.chooseStream(playInfo(item.id, wanted))
            ?: throw UserFacingException(I18n.s.liveUnavailable)
        // The first answer tells which qualities this room (and this viewer) may get; ask again if a better fit exists.
        val fit = BiliLiveParse.pickQn(choice.acceptQn, maxHeight, audioOnly)
        if (fit != choice.qn && fit in choice.acceptQn) {
            runCatching { playInfo(item.id, fit) }.getOrNull()
                ?.let(BiliLiveParse::chooseStream)?.let { choice = it }
        }
        val url = BiliCdn.choose(choice.urls, overseas = false) ?: throw UserFacingException(I18n.s.liveUnavailable)
        return PlayableStream(
            videoUri = url,
            headers = PLAY_HEADERS,
            kind = if (choice.hls) PlayableStream.Kind.HLS else PlayableStream.Kind.PROGRESSIVE,
            audioOnly = audioOnly,
            live = true,
        )
    }

    private suspend fun playInfo(roomId: String, qn: Int): JSONObject {
        val json = raw(
            "$LIVE_API/xlive/web-room/v2/index/getRoomPlayInfo?room_id=$roomId" +
                "&protocol=0,1&format=0,1,2&codec=0,1&qn=$qn&platform=web&ptype=8",
        )
        if (json.optInt("code") != 0) throw UserFacingException(I18n.s.liveUnavailable)
        val d = json.optJSONObject("data") ?: throw UserFacingException(I18n.s.liveUnavailable)
        // live_status: 0 offline, 1 live, 2 replaying old videos (轮播), which we treat as offline.
        if (d.optInt("live_status") != 1 || d.optJSONObject("playurl_info") == null) {
            throw UserFacingException(I18n.s.streamOffline)
        }
        return d
    }

    private suspend fun raw(url: String): JSONObject = JSONObject(
        http.getString(Request.Builder().url(url).header("Referer", LIVE_REFERER).build()),
    )

    private fun checked(json: JSONObject): JSONObject {
        val code = json.optInt("code")
        if (code != 0) throw BiliException(code, json.optString("message"))
        return json.optJSONObject("data") ?: JSONObject()
    }

    private suspend fun data(url: String): JSONObject = checked(raw(url))

    /** For answers whose `data` is an array (Area/getList). */
    private suspend fun dataArray(url: String): JSONArray {
        val json = raw(url)
        val code = json.optInt("code")
        if (code != 0) throw BiliException(code, json.optString("message"))
        return json.optJSONArray("data") ?: JSONArray()
    }

    /** Anonymous device cookies; several live endpoints answer -352 / empty lists without them. */
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

    companion object {
        const val FEED_RECOMMENDED = "recommended"
        const val FEED_POPULAR = "popular"
        const val FEED_FOLLOWING = "following"
        private const val LIVE_API = "https://api.live.bilibili.com"
        private const val LIVE_REFERER = "https://live.bilibili.com/"
        private const val MAX_RECOMMENDED_PAGES = 30
        private const val FOLLOWING_PAGES_PER_CALL = 3
        private const val AREA_PAGE_SIZE = 30
        private val PLAY_HEADERS = mapOf("Referer" to LIVE_REFERER, "User-Agent" to DESKTOP_UA)
    }
}

/** Pure parsing / picking for Bilibili Live (no Android types; unit-tested). */
object BiliLiveParse {
    /** 原画, 蓝光, 超清, 高清, 流畅. */
    val STANDARD_QNS = listOf(10000, 400, 250, 150, 80)

    /** Rough picture height of a live quality number. */
    fun qnHeight(qn: Int): Int = when {
        qn >= 20000 -> 2160 // 4K, Dolby
        qn >= 15000 -> 1440 // 2K
        qn >= 400 -> 1080 // 原画 (usually the 1080p source), 蓝光
        qn >= 250 -> 720 // 超清
        qn >= 150 -> 480 // 高清
        else -> 360 // 流畅
    }

    /** Highest quality that fits [maxHeight]; the lowest one for audio-only or when nothing fits. */
    fun pickQn(accept: List<Int>, maxHeight: Int, audioOnly: Boolean): Int {
        if (accept.isEmpty()) return 10000
        if (audioOnly) return accept.min()
        return accept.filter { qnHeight(it) <= maxHeight }.maxOrNull() ?: accept.min()
    }

    data class StreamChoice(val hls: Boolean, val qn: Int, val acceptQn: List<Int>, val urls: List<String>)

    /**
     * Picks one stream from getRoomPlayInfo's `data`: HLS before FLV (ts before fmp4), AVC before HEVC
     * (car head units often lack HEVC decoders). Returns null when there is nothing playable.
     */
    fun chooseStream(data: JSONObject): StreamChoice? {
        val streams = data.optJSONObject("playurl_info")?.optJSONObject("playurl")?.optJSONArray("stream") ?: return null
        val candidates = mutableListOf<Pair<Int, StreamChoice>>()
        for (s in streams.objects()) {
            val protocol = s.optString("protocol_name")
            for (f in s.optJSONArray("format").objects()) {
                val format = f.optString("format_name")
                for (c in f.optJSONArray("codec").objects()) {
                    val base = c.optString("base_url")
                    if (base.isEmpty()) continue
                    val urls = c.optJSONArray("url_info").objects().mapNotNull { u ->
                        val host = u.optString("host").ifEmpty { return@mapNotNull null }
                        host + base + u.optString("extra")
                    }
                    if (urls.isEmpty()) continue
                    val hls = protocol == "http_hls"
                    val formatRank = when {
                        hls && format == "ts" -> 0
                        hls && format == "fmp4" -> 1
                        !hls && format == "flv" -> 2
                        else -> continue
                    }
                    val rank = formatRank + if (c.optString("codec_name") == "avc") 0 else 10
                    val accept = c.optJSONArray("accept_qn").ints()
                    candidates += rank to StreamChoice(hls, c.optInt("current_qn"), accept, urls)
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    /** Room cards from the recommend / by-area / search lists (all share roomid, uid, uname, title, online). */
    fun rooms(list: JSONArray?, viewers: (Long) -> String): List<VideoItem> = list.objects().mapNotNull { o ->
        val room = o.optLong("roomid").takeIf { it > 0 } ?: return@mapNotNull null
        if (o.has("live_status") && o.optInt("live_status") != 1) return@mapNotNull null
        val name = BiliText.clean(o.optString("uname"))
        val online = o.optLong("online")
        VideoItem(
            source = SourceId.BILI_LIVE,
            id = room.toString(),
            title = BiliText.clean(o.optString("title")),
            author = if (online > 0) "$name · ${viewers(online)}" else name,
            authorId = o.optLong("uid").takeIf { it > 0 }?.toString() ?: room.toString(),
            thumbnailUrl = BiliText.thumb(o.optString("user_cover").ifEmpty { o.optString("cover") }),
            durationSec = null,
        )
    }

    /** room/v1/Area/getList `data`: parent areas with their `list` of areas (ids are strings; `pic` is the icon). */
    fun areas(parents: JSONArray?): List<Category> = parents.objects().flatMap { p ->
        val parentId = p.optString("id").ifEmpty { return@flatMap emptyList() }
        val group = p.optString("name")
        p.optJSONArray("list").objects().mapNotNull { a ->
            val id = a.optString("id").ifEmpty { return@mapNotNull null }
            val name = a.optString("name").ifEmpty { return@mapNotNull null }
            Category("${a.optString("parent_id").ifEmpty { parentId }}:$id", name, group, iconUrl(a.optString("pic")))
        }
    }

    /** Area icons are square PNGs: only make the URL absolute https (no 16:9 thumbnail crop). */
    private fun iconUrl(pic: String): String? = when {
        pic.isBlank() -> null
        pic.startsWith("//") -> "https:$pic"
        pic.startsWith("http://") -> "https://" + pic.removePrefix("http://")
        else -> pic
    }

    /** "parent:area" category key -> (parent area id, area id). */
    fun areaKey(key: String): Pair<String, String>? {
        val parts = key.split(':')
        if (parts.size != 2 || parts.any { it.toIntOrNull() == null }) return null
        return parts[0] to parts[1]
    }

    /** room/v3/area/getRoomList `data`: `list` of rooms plus the area's total `count`. */
    fun areaPage(d: JSONObject, page: Int, pageSize: Int, viewers: (Long) -> String): FeedPage {
        val items = rooms(d.optJSONArray("list"), viewers)
        val more = items.isNotEmpty() && page.toLong() * pageSize < d.optLong("count")
        return FeedPage(items, (page + 1).toString().takeIf { more })
    }

    /** wbi/search/type (live_room) `data`: `result` rooms, `numPages` pages. */
    fun searchPage(d: JSONObject, page: Int, viewers: (Long) -> String): FeedPage {
        val items = rooms(d.optJSONArray("result"), viewers)
        return FeedPage(items, (page + 1).toString().takeIf { items.isNotEmpty() && page < d.optInt("numPages") })
    }

    /** Followed streamers who are live now (web-ucenter/user/following `list`). */
    fun followingLive(list: JSONArray?): List<VideoItem> = list.objects().mapNotNull { o ->
        if (o.optInt("live_status") != 1) return@mapNotNull null
        val room = o.optLong("roomid").takeIf { it > 0 } ?: return@mapNotNull null
        val name = o.optString("uname")
        val watching = o.optString("text_small").takeIf { it.isNotEmpty() && it != "0" }
        VideoItem(
            source = SourceId.BILI_LIVE,
            id = room.toString(),
            title = o.optString("title"),
            author = if (watching != null) "$name · $watching" else name,
            authorId = o.optLong("uid").takeIf { it > 0 }?.toString() ?: room.toString(),
            thumbnailUrl = BiliText.thumb(o.optString("room_cover")),
            durationSec = null,
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.ints(): List<Int> =
        if (this == null) emptyList() else (0 until length()).map { optInt(it) }.filter { it > 0 }
}
