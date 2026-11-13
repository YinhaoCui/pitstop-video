package app.pitstop.video.podcast

import app.pitstop.video.core.AppLanguage
import app.pitstop.video.core.Feed
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.getString
import app.pitstop.video.podcast.PodcastParser.SHOW_PREFIX
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Podcasts: Apple's top charts and iTunes search for shows, episodes straight from each show's RSS
 * feed. A show is a "creator" (authorId = its feed URL), so saved shows become feeds.
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
        val chart = PodcastParser.chartShows(get("https://rss.marketingtools.apple.com/api/v2/$c/podcasts/top/100/podcasts.json"))
        if (chart.isEmpty()) return chart
        // One batch lookup turns the Apple ids into feed URLs (so rows can be saved as creators).
        val ids = chart.joinToString(",") { it.id.removePrefix(SHOW_PREFIX) }
        val found = runCatching {
            PodcastParser.itunesShows(get(url("https://itunes.apple.com/lookup", "id" to ids, "country" to c)))
        }.getOrDefault(emptyMap())
        return PodcastParser.withFeedUrls(chart, found)
    }

    override suspend fun search(query: String): List<VideoItem> {
        val json = get(url("https://itunes.apple.com/search", "media" to "podcast", "entity" to "podcast",
            "term" to query, "country" to country, "limit" to "50"))
        return PodcastParser.itunesShows(json).values.toList()
    }

    override fun isFolder(item: VideoItem) = item.id.startsWith(SHOW_PREFIX)

    override suspend fun browse(item: VideoItem): List<VideoItem> =
        episodes(item.id.removePrefix(SHOW_PREFIX), item.title)

    override suspend fun creatorVideos(authorId: String): List<VideoItem> = episodes(authorId, null)

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
                PodcastParser.parseRss(body.byteStream())
            }
        }
        val items = PodcastParser.episodeItems(feed, feedUrl, fallbackName = showName)
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
    }
}
