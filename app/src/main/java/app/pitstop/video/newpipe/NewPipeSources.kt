package app.pitstop.video.newpipe

import app.pitstop.video.core.AppLanguage
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Category
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.Strings
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.categoryKey
import app.pitstop.video.player.DeviceCodecs
import app.pitstop.video.player.StreamSelector
import app.pitstop.video.youtube.NewPipeDownloader
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.peertube.PeertubeService
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Cursor -> value, bounded (least recently used dropped first). Cursors stay valid after being read,
 * so a page that failed to load can be asked for again.
 */
class PageStore<T>(private val max: Int = 256) {
    private val map = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > max
    }

    @Synchronized fun keep(value: T): String = UUID.randomUUID().toString().also { map[it] = value }
    @Synchronized operator fun get(key: String): T? = map[key]
    @get:Synchronized val size: Int get() = map.size
}

/**
 * Endless feeds over NewPipe list extractors. NewPipe's next-page handles aren't strings, so each
 * continuation ("load the page after this one") is kept in a [PageStore] and the cursor is its key.
 * Everything here blocks: call it on an IO dispatcher.
 */
class NewPipePager {
    private val store = PageStore<() -> FeedPage>()

    fun keep(next: () -> FeedPage): String = store.keep(next)

    /** The page a cursor points at; empty (the end) for an unknown or expired cursor. */
    fun resume(cursor: String): FeedPage = store[cursor]?.invoke() ?: FeedPage(emptyList())

    /**
     * One page of [extractor] ([page] null = its first page); the cursor continues with its next page,
     * then with [after] once it runs out. A first page that fails also falls through to [after].
     */
    fun pages(
        extractor: ListExtractor<out InfoItem>,
        page: Page?,
        map: (InfoItem) -> VideoItem?,
        after: (() -> FeedPage)? = null,
    ): FeedPage {
        val p = try {
            if (page == null) { extractor.fetchPage(); extractor.initialPage } else extractor.getPage(page)
        } catch (e: Exception) {
            if (page == null && after != null) return after() else throw e
        }
        val next = p.nextPage
        val cont: (() -> FeedPage)? = if (p.hasNextPage() && next != null) {
            { pages(extractor, next, map, after) }
        } else after
        return FeedPage(p.items.mapNotNull(map), cont?.let(::keep))
    }
}

/** Hard-coded category lists and key shapes (titles come from I18n; pure logic, tested). */
object CategoryKeys {
    /** YouTube category keys: a trending kiosk, or a topic implemented as a search. */
    const val KIOSK = "k:"
    const val TOPIC = "q:"

    val YT_TOPICS = listOf(
        "news", "sports", "music", "gaming", "comedy", "education", "science", "travel", "cooking",
        "cars", "kids", "documentary", "fitness", "animals", "diy", "fashion",
    )

    /** Default kiosk first, the rest alphabetically (stable order across launches). */
    fun kioskOrder(available: Collection<String>, default: String): List<String> =
        listOf(default) + available.filter { it != default }.sorted()

    /** What a YouTube trending list continues with once it runs out (a topic key for I18n.ytTopicQuery). */
    fun ytKioskFollowUp(kioskId: String): String = when (kioskId) {
        "trending_music" -> "music"
        "trending_gaming" -> "gaming"
        "trending_movies_and_shows" -> "trailers"
        "trending_podcasts_episodes" -> "podcasts"
        "live" -> "live"
        else -> "popular"
    }

    val MUSIC_GENRES = listOf(
        "pop", "rock", "hiphop", "rnb", "jazz", "classical", "electronic", "lofi", "kpop", "cpop", "jpop",
        "mandopop", "cantopop", "country", "metal", "soundtrack", "folk", "indie", "blues", "reggae", "latin",
        "punk", "ambient", "dance",
    )
    val MUSIC_MOODS = listOf("workout", "chill", "focus", "sleep", "party", "romance", "driving", "happy", "sad")

    val SOUNDCLOUD_GENRES = listOf(
        "hiphop", "pop", "electronic", "house", "techno", "dance", "rnb", "rock", "indie", "alternative",
        "lofi", "ambient", "jazz", "classical", "metal", "country", "folk", "reggae", "latin", "soundtrack",
        "chill", "workout", "sleep", "party",
    )

    /** Bandcamp discover tags (tag_norm_names, verified against the discover API) -> genre key for the title. */
    val BANDCAMP_TAGS = listOf(
        "electronic" to "electronic", "rock" to "rock", "metal" to "metal", "alternative" to "alternative",
        "hip-hop-rap" to "hiphop", "experimental" to "experimental", "punk" to "punk", "folk" to "folk",
        "pop" to "pop", "ambient" to "ambient", "soundtrack" to "soundtrack", "world" to "world",
        "jazz" to "jazz", "acoustic" to "acoustic", "funk" to "funk", "r-b-soul" to "rnb",
        "classical" to "classical", "reggae" to "reggae", "country" to "country", "blues" to "blues",
        "latin" to "latin", "lo-fi" to "lofi", "kids" to "kids",
    )

    /** Categories for [keys]; keys without a title are dropped (never shown as raw keys). */
    fun named(keys: List<String>, title: (String) -> String?, group: String? = null, keyOf: (String) -> String = { it }) =
        keys.mapNotNull { k -> title(k)?.let { Category(keyOf(k), it, group) } }
}

/** PeerTube's REST API: video categories of an instance and the videos in one (pure parsing, tested). */
object PeerTubeApi {
    const val PAGE = 24

    fun categoriesUrl(base: String) = "${base.trimEnd('/')}/api/v1/videos/categories"

    fun videosUrl(base: String, categoryId: String, start: Int, count: Int = PAGE) =
        "${base.trimEnd('/')}/api/v1/videos?categoryOneOf=$categoryId&start=$start&count=$count&sort=-publishedAt&nsfw=false"

    /** `{"1":"Music","2":"Films",...}` -> categories in id order; labels are the instance's own (content). */
    fun parseCategories(json: String): List<Category> {
        val o = JSONObject(json)
        return o.keys().asSequence().toList()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { k -> o.optString(k).ifBlank { null }?.let { Category(k, it) } }
    }

    /** One page of `/api/v1/videos`: the items and the total count. Watch URLs point at [base] (it federates them). */
    fun parseVideos(json: String, base: String, source: SourceId = SourceId.PEERTUBE): Pair<List<VideoItem>, Int> {
        val root = JSONObject(json)
        val b = base.trimEnd('/')
        val data = root.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { i ->
            val v = data.optJSONObject(i) ?: return@mapNotNull null
            val uuid = v.optString("uuid").ifEmpty { return@mapNotNull null }
            val account = v.optJSONObject("account")
            val accName = account?.optString("name").orEmpty()
            val accHost = account?.optString("host").orEmpty()
            VideoItem(
                source = source,
                id = "$b/videos/watch/$uuid",
                title = v.optString("name"),
                author = account?.optString("displayName")?.ifEmpty { null } ?: accName.ifEmpty { null },
                authorId = if (accName.isNotEmpty() && accHost.isNotEmpty()) "$b/accounts/$accName@$accHost" else null,
                thumbnailUrl = thumbnail(v, b),
                durationSec = v.optLong("duration").takeIf { it > 0 },
            )
        }
        return items to root.optInt("total", 0)
    }

    private fun thumbnail(v: JSONObject, base: String): String? {
        val list = v.optJSONArray("thumbnails")
        if (list != null) {
            val thumbs = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { it.optString("fileUrl").isNotEmpty() }
                .sortedBy { it.optInt("width") }
            (thumbs.firstOrNull { it.optInt("width") >= 300 } ?: thumbs.lastOrNull())?.let { return it.optString("fileUrl") }
        }
        return v.optString("thumbnailPath").ifEmpty { null }?.let { if (it.startsWith("http")) it else base + it }
    }

    /** The next page's start offset, or null at the end. */
    fun nextCursor(start: Int, got: Int, total: Int): String? = (start + got).takeIf { got > 0 && it < total }?.toString()
}

/** Bandcamp's discover API (works without an account): top albums for a tag (pure parsing, tested). */
object BandcampDiscover {
    const val URL = "https://bandcamp.com/api/discover/1/discover_web"
    const val PAGE = 24

    fun body(tag: String, cursor: String?, size: Int = PAGE): String = JSONObject()
        .put("category_id", 0)
        .put("tag_norm_names", JSONArray().put(tag))
        .put("geoname_id", 0)
        .put("slice", "top")
        .put("cursor", cursor ?: "*")
        .put("size", size)
        // Only "a" (albums) is accepted with tags; adding "t" makes the API throw.
        .put("include_result_types", JSONArray().put("a"))
        .toString()

    /** Albums open as folders, tracks play directly. Next cursor null when nothing came back. */
    fun parse(json: String): FeedPage {
        val root = JSONObject(json)
        val results = root.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val url = r.optString("itemUrl").substringBefore('?').ifEmpty { return@mapNotNull null }
            val image = r.optJSONObject("primaryImage")
            val imageId = image?.optLong("imageId") ?: 0L
            val art = if (imageId <= 0) null
                else if (image?.optBoolean("isArt", true) != false) "https://f4.bcbits.com/img/a${imageId}_2.jpg"
                else "https://f4.bcbits.com/img/${imageId}_2.jpg"
            val album = !url.contains("/track/")
            VideoItem(
                source = SourceId.BANDCAMP,
                id = if (album) COLLECTION_PREFIX + url else url,
                title = r.optString("title"),
                author = r.optString("albumArtist").takeIf { it.isNotEmpty() && it != "null" } ?: r.optString("bandName").ifEmpty { null },
                authorId = r.optString("bandUrl").substringBefore('?').ifEmpty { null },
                thumbnailUrl = art,
                durationSec = if (album) null else r.optDouble("duration").takeIf { !it.isNaN() && it > 0 }?.toLong(),
            )
        }
        val next = root.optString("cursor").takeIf { items.isNotEmpty() && it.isNotEmpty() && it != "null" }
        return FeedPage(items, next)
    }
}

/**
 * A source backed by one NewPipeExtractor service (SoundCloud, Bandcamp, PeerTube). Feeds are the
 * service's own kiosks (charts / featured lists) with endless paging; albums and playlists open as
 * folders; uploaders work as creators. Search and creator pages are endless too.
 */
abstract class NewPipeServiceSource(final override val id: SourceId) : VideoSource {
    protected abstract val service: StreamingService
    override val displayName get() = I18n.s.sourceName(id)
    override val supportsCreators get() = true

    protected val pager = NewPipePager()

    /** Feed title for a kiosk id; unknown ids keep the service's own name. */
    protected open fun kioskTitle(kioskId: String): String = when (kioskId) {
        "Trending" -> I18n.s.popular
        "Top 50" -> I18n.s.topCharts
        "New & hot" -> I18n.s.newAndHot
        "Featured" -> I18n.s.featured
        "Radio" -> I18n.s.bandcampRadio
        "Most liked" -> I18n.s.mostLiked
        "Recently added" -> I18n.s.newReleases
        "Local" -> I18n.s.peertubeLocal
        else -> kioskId
    }

    override fun feeds(): List<Feed> = runCatching {
        val list = service.kioskList
        CategoryKeys.kioskOrder(list.availableKiosks, list.defaultKioskId).map { Feed(it, kioskTitle(it)) }
    }.getOrDefault(emptyList())

    override suspend fun load(feed: Feed): List<VideoItem> = loadPage(feed, null).items

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        feed.categoryKey()?.let { return categoryPage(it, cursor) }
        return withContext(Dispatchers.IO) {
            if (cursor != null) pager.resume(cursor)
            else pager.pages(service.kioskList.getExtractorById(feed.key, null), null, ::toItem)
        }
    }

    /** One page of a picked category (key from [categories]). */
    protected open suspend fun categoryPage(key: String, cursor: String?): FeedPage = FeedPage(emptyList())

    /** Content filters for searches ("" / empty = the service's default, everything). */
    protected open val searchFilters: List<String> get() = emptyList()

    override suspend fun search(query: String): List<VideoItem> = searchPage(query, null).items

    override suspend fun searchPage(query: String, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        if (cursor != null) pager.resume(cursor) else searchFirst(query, searchFilters)
    }

    /** First page of a search; its cursor continues through the search's own pages. Blocking. */
    protected fun searchFirst(query: String, filters: List<String>): FeedPage =
        pager.pages(service.getSearchExtractor(query, filters, ""), null, ::toItem)

    override suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList()
        else runCatching { service.suggestionExtractor.suggestionList(query) }.getOrNull().orEmpty()
            .filter { it.isNotBlank() }.distinct().take(10)
    }

    override fun isFolder(item: VideoItem) = item.id.startsWith(COLLECTION_PREFIX)

    /** An album / playlist: its tracks (up to ~300, following its pages). */
    override suspend fun browse(item: VideoItem): List<VideoItem> = withContext(Dispatchers.IO) {
        val url = item.id.removePrefix(COLLECTION_PREFIX)
        val info = PlaylistInfo.getInfo(service, url)
        val out = info.relatedItems.mapNotNull(::toItem).toMutableList()
        var next = info.nextPage
        while (next != null && out.size < 300) {
            val more = PlaylistInfo.getMoreItems(service, url, next)
            out += more.items.mapNotNull(::toItem)
            next = more.nextPage
        }
        out
    }

    override suspend fun expand(item: VideoItem): List<VideoItem>? =
        if (isFolder(item)) browse(item) else null

    override suspend fun creatorVideos(authorId: String): List<VideoItem> = creatorPage(authorId, null).items

    /** A creator's videos / tracks / albums tab, endlessly. */
    override suspend fun creatorPage(authorId: String, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        if (cursor != null) return@withContext pager.resume(cursor)
        val info = ChannelInfo.getInfo(service, authorId)
        val tab = info.tabs.firstOrNull { it.contentFilters.any { f -> f == ChannelTabs.VIDEOS || f == ChannelTabs.TRACKS || f == ChannelTabs.ALBUMS } }
            ?: info.tabs.firstOrNull() ?: return@withContext FeedPage(emptyList())
        pager.pages(service.getChannelTabExtractor(tab), null, ::toItem)
    }

    protected fun toItem(i: InfoItem): VideoItem? = when (i) {
        is StreamInfoItem -> VideoItem(
            source = id,
            id = i.url,
            title = i.name,
            author = i.uploaderName,
            authorId = i.uploaderUrl?.ifEmpty { null },
            thumbnailUrl = bestThumb(i.thumbnails),
            durationSec = i.duration.takeIf { it > 0 },
        )
        is PlaylistInfoItem -> VideoItem(
            source = id,
            id = COLLECTION_PREFIX + i.url,
            title = i.name,
            author = i.uploaderName,
            authorId = i.uploaderUrl?.ifEmpty { null },
            thumbnailUrl = bestThumb(i.thumbnails),
        )
        else -> null
    }

    private fun bestThumb(images: List<Image>): String? =
        images.sortedBy { it.width }.firstOrNull { it.width >= 300 }?.url ?: images.lastOrNull()?.url

    protected val headers get() = mapOf("User-Agent" to NewPipeDownloader.USER_AGENT)

    /** Best audio: progressive first (within the data-saver bitrate cap), else the HLS one. */
    protected fun audioOf(info: StreamInfo): PlayableStream? {
        val progressive = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { StreamSelector.AudioTrack(it.content, it.averageBitrate.toLong() * 1000, it.codec.orEmpty()) }
        StreamSelector.pickAudio(progressive, StreamPolicy.audioMaxBps)?.let { return PlayableStream(it.url, headers = headers) }
        info.audioStreams.firstOrNull { it.isUrl && it.deliveryMethod == DeliveryMethod.HLS }
            ?.let { return PlayableStream(it.content, headers = headers, kind = PlayableStream.Kind.HLS) }
        return null
    }

    /** Plain GET through NewPipe's downloader (shared OkHttp client and user agent). Blocking. */
    protected fun httpGet(url: String): String {
        val r = NewPipe.getDownloader().get(url)
        if (r.responseCode() !in 200..299) throw java.io.IOException("HTTP ${r.responseCode()} for $url")
        return r.responseBody()
    }
}

/** SoundCloud: charts / new & hot, genres (track searches), search, playlists as folders. */
class SoundCloudSource : NewPipeServiceSource(SourceId.SOUNDCLOUD) {
    override val service: StreamingService get() = ServiceList.SoundCloud

    /**
     * SoundCloud's per-genre charts need its private api-v2 client id and were cut back in 2023, so a
     * genre is a track search for the genre's English name (SoundCloud's tags are English).
     */
    override suspend fun categories(): List<Category> {
        val s = I18n.s
        return CategoryKeys.named(CategoryKeys.SOUNDCLOUD_GENRES, s::musicGenre, s.catGenres)
    }

    override suspend fun categoryPage(key: String, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        if (cursor != null) pager.resume(cursor)
        else searchFirst(Strings(AppLanguage.EN).musicGenreQuery(key), listOf(SoundcloudSearchQueryHandlerFactory.TRACKS))
    }

    override suspend fun resolve(item: VideoItem): PlayableStream = withContext(Dispatchers.IO) {
        audioOf(StreamInfo.getInfo(service, item.id)) ?: throw UserFacingException(I18n.s.noAudioStream)
    }
}

/** Bandcamp: featured albums (folders), Bandcamp Radio shows, genres (discover API), search. */
class BandcampSource : NewPipeServiceSource(SourceId.BANDCAMP) {
    override val service: StreamingService get() = ServiceList.Bandcamp

    override suspend fun categories(): List<Category> {
        val s = I18n.s
        val tags = CategoryKeys.BANDCAMP_TAGS.toMap()
        return CategoryKeys.named(CategoryKeys.BANDCAMP_TAGS.map { it.first }, { s.musicGenre(tags.getValue(it)) }, s.catGenres)
    }

    /** Top albums for a tag from Bandcamp's discover API; a tag search if that API fails. */
    override suspend fun categoryPage(key: String, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        if (cursor != null && cursor.startsWith(SEARCH_CURSOR)) return@withContext pager.resume(cursor.removePrefix(SEARCH_CURSOR))
        try {
            val r = NewPipe.getDownloader().postWithContentTypeJson(
                BandcampDiscover.URL, emptyMap(), BandcampDiscover.body(key, cursor).toByteArray(),
            )
            if (r.responseCode() !in 200..299) throw java.io.IOException("HTTP ${r.responseCode()}")
            BandcampDiscover.parse(r.responseBody())
        } catch (e: Exception) {
            if (cursor != null) throw e
            val tags = CategoryKeys.BANDCAMP_TAGS.toMap()
            val page = searchFirst(Strings(AppLanguage.EN).musicGenre(tags[key] ?: key) ?: key, emptyList())
            page.copy(next = page.next?.let { SEARCH_CURSOR + it })
        }
    }

    override suspend fun resolve(item: VideoItem): PlayableStream = withContext(Dispatchers.IO) {
        audioOf(StreamInfo.getInfo(service, item.id)) ?: throw UserFacingException(I18n.s.noAudioStream)
    }

    private companion object {
        /** Cursors of the fallback tag search (discover cursors are Bandcamp's own strings). */
        const val SEARCH_CURSOR = "search:"
    }
}

/** PeerTube (the extractor's default instance): trending / most liked / recent videos, the instance's categories. */
class PeerTubeSource : NewPipeServiceSource(SourceId.PEERTUBE) {
    override val service: StreamingService get() = ServiceList.PeerTube

    private val base: String get() = (service as PeertubeService).instance.url

    /** The instance's own video categories (labels as the instance sends them). */
    override suspend fun categories(): List<Category> = withContext(Dispatchers.IO) {
        PeerTubeApi.parseCategories(httpGet(PeerTubeApi.categoriesUrl(base)))
    }

    /** Newest videos in a category; the cursor is the start offset. */
    override suspend fun categoryPage(key: String, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        val start = cursor?.toIntOrNull() ?: 0
        val (items, total) = PeerTubeApi.parseVideos(httpGet(PeerTubeApi.videosUrl(base, key, start)), base)
        FeedPage(items, PeerTubeApi.nextCursor(start, items.size, total))
    }

    override suspend fun resolve(item: VideoItem): PlayableStream = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, item.id)
        val live = info.streamType.name.contains("LIVE")
        if (StreamPolicy.audioOnly) audioOf(info)?.let { return@withContext it.copy(audioOnly = true, live = live) }
        val muxed = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { StreamSelector.VideoTrack(it.content, it.height, it.codec.orEmpty(), width = it.width) }
        StreamSelector.pickVideo(muxed, StreamPolicy.maxHeight, DeviceCodecs::canPlay)
            ?.let { return@withContext PlayableStream(it.url, headers = headers) }
        if (info.hlsUrl.isNotEmpty()) return@withContext PlayableStream(info.hlsUrl, headers = headers, kind = PlayableStream.Kind.HLS, live = live)
        throw UserFacingException(I18n.s.noVideoStream)
    }
}
