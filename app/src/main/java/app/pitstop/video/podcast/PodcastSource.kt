package app.pitstop.video.podcast

import app.pitstop.video.core.AppLanguage
import app.pitstop.video.core.Category
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.Strings
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.categoryKey
import app.pitstop.video.core.getString
import app.pitstop.video.podcast.PodcastParser.SHOW_PREFIX
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Podcasts: Apple's top charts (overall and per genre) and iTunes search for shows, episodes straight
 * from each show's RSS feed. A show is a "creator" (authorId = its feed URL), so saved shows become feeds.
 */
class PodcastSource(private val http: OkHttpClient) : VideoSource {
    override val id = SourceId.PODCASTS
    override val displayName get() = I18n.s.podcasts
    override val supportsCreators get() = true

    /** Recently parsed feeds (feed URL → episodes), so reopening a show is instant. */
    private val cache = object : LinkedHashMap<String, Pair<Long, List<VideoItem>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<VideoItem>>>) = size > 8
    }

    private val country get() = if (I18n.language.value == AppLanguage.ZH) "cn" else "us"

    override fun feeds(): List<Feed> = listOf(Feed(FEED_TOP, I18n.s.topCharts))

    override suspend fun load(feed: Feed): List<VideoItem> {
        val c = country
        return withFeedUrls(PodcastParser.chartShows(get("https://rss.marketingtools.apple.com/api/v2/$c/podcasts/top/100/podcasts.json")), c)
    }

    /** Apple's top-level podcast genres. */
    override suspend fun categories(): List<Category> =
        PodcastParser.GENRES.map { Category(it.toString(), I18n.s.podcastGenre(it)) }

    /**
     * A genre: its top 200 chart first (cursor null), then one more page (cursor [CURSOR_SEARCH]) of
     * iTunes search results for the genre's name that are filed under that genre.
     */
    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        val genre = feed.categoryKey()?.toIntOrNull()
            ?: return if (cursor == null) FeedPage(load(feed)) else FeedPage(emptyList())
        val c = country
        if (cursor == null) {
            val chart = PodcastParser.legacyChartShows(get("https://itunes.apple.com/$c/rss/toppodcasts/limit=200/genre=$genre/json"))
            if (chart.isNotEmpty()) return FeedPage(withFeedUrls(chart, c), CURSOR_SEARCH)
        } else if (cursor != CURSOR_SEARCH) return FeedPage(emptyList())
        // The search term is the English genre name (the iTunes index matches it in every store).
        val json = get(url("https://itunes.apple.com/search", "media" to "podcast", "entity" to "podcast",
            "term" to Strings(AppLanguage.EN).podcastGenre(genre), "country" to c, "limit" to "$SEARCH_LIMIT"))
        return FeedPage(PodcastParser.itunesShows(json, genreId = genre).values.toList())
    }

    /** Chart rows with feed URLs from one batch lookup (so rows can be saved as creators). */
    private suspend fun withFeedUrls(chart: List<VideoItem>, c: String): List<VideoItem> {
        if (chart.isEmpty()) return chart
        val ids = chart.joinToString(",") { it.id.removePrefix(SHOW_PREFIX) }
        val found = runCatching {
            PodcastParser.itunesShows(get(url("https://itunes.apple.com/lookup", "id" to ids, "country" to c)))
        }.getOrDefault(emptyMap())
        return PodcastParser.withFeedUrls(chart, found)
    }

    /** iTunes podcast search ignores `offset`, so search is one page of up to [SEARCH_LIMIT] shows. */
    override suspend fun search(query: String): List<VideoItem> {
        val json = get(url("https://itunes.apple.com/search", "media" to "podcast", "entity" to "podcast",
            "term" to query, "country" to country, "limit" to "$SEARCH_LIMIT"))
        return PodcastParser.itunesShows(json).values.toList()
    }

    override fun isFolder(item: VideoItem) = item.id.startsWith(SHOW_PREFIX)

    override suspend fun browse(item: VideoItem): List<VideoItem> =
        episodes(item.id.removePrefix(SHOW_PREFIX), item.title).take(PodcastParser.MAX_EPISODES)

    override suspend fun creatorVideos(authorId: String): List<VideoItem> =
        episodes(authorId, null).take(PodcastParser.MAX_EPISODES)

    /** A show's episodes, newest first, [EPISODE_PAGE] at a time (cursor = offset; the feed is cached). */
    override suspend fun creatorPage(authorId: String, cursor: String?): FeedPage =
        PodcastParser.episodePage(episodes(authorId, null), cursor?.toIntOrNull() ?: 0, EPISODE_PAGE)

    override suspend fun resolve(item: VideoItem) = PlayableStream(PodcastParser.https(item.id) ?: item.id)

    /** [key] is a feed URL, or an Apple id (digits) that is looked up first. */
    private suspend fun episodes(key: String, showName: String?): List<VideoItem> {
        val feedUrl = if (key.all { it.isDigit() }) feedUrlFor(key) else PodcastParser.https(key) ?: key
        synchronized(cache) { cache[feedUrl] }?.let { (at, items) ->
            if (System.currentTimeMillis() - at < CACHE_MS) return items
        }
        val feed = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(feedUrl).header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for podcast feed")
                val body = resp.body ?: throw IOException("Empty body")
                PodcastParser.parseRss(body.byteStream(), maxItems = PodcastParser.MAX_ALL_EPISODES)
            }
        }
        val items = PodcastParser.episodeItems(feed, feedUrl, cap = PodcastParser.MAX_ALL_EPISODES, fallbackName = showName)
        synchronized(cache) { cache[feedUrl] = System.currentTimeMillis() to items }
        return items
    }

    private suspend fun feedUrlFor(appleId: String): String {
        val json = get(url("https://itunes.apple.com/lookup", "id" to appleId, "country" to country))
        return PodcastParser.itunesShows(json).values.firstOrNull()?.authorId
            ?: throw UserFacingException(I18n.s.podcastNoFeed)
    }

    private fun url(base: String, vararg params: Pair<String, String>): String =
        base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()

    private suspend fun get(url: String) =
        http.getString(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())

    companion object {
        const val FEED_TOP = "top"
        const val USER_AGENT = "PitstopVideo/0.5 (Android)"
        private const val CACHE_MS = 10 * 60_000L
        private const val CURSOR_SEARCH = "search"
        /** iTunes search's largest `limit` (podcast searches give about 100 at most) and no paging. */
        private const val SEARCH_LIMIT = 200
        private const val EPISODE_PAGE = 50
    }
}
