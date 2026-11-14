package app.pitstop.video.podcast

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** One episode as read from a podcast RSS feed. */
data class RssEpisode(
    val title: String,
    val audioUrl: String,
    val imageUrl: String? = null,
    val durationSec: Long? = null,
    val publishedMs: Long? = null,
)

/** A podcast RSS feed: the show's name / author / cover and its episodes in document order. */
data class RssFeed(
    val title: String,
    val author: String? = null,
    val imageUrl: String? = null,
    val episodes: List<RssEpisode> = emptyList(),
)

/**
 * Pure parsing for podcasts (no Android types, so it runs in JVM unit tests): RSS 2.0 + iTunes
 * namespace via SAX (javax.xml.parsers exists on both Android and the JVM, and streams, so a 10 MB
 * feed is never held in memory as a string), and the Apple charts / iTunes search JSON.
 */
object PodcastParser {
    /** Show rows (folders) have ids like `list:show:<feed url or Apple id>`. */
    const val SHOW_PREFIX = COLLECTION_PREFIX + "show:"
    const val MAX_EPISODES = 300

    /** Most episodes kept of one feed (a show's endless episode list, see [episodePage]). */
    const val MAX_ALL_EPISODES = 3000

    /**
     * Apple's top-level podcast genres (ids from `itunes.apple.com/WebObjects/MZStoreServices.woa/ws/genres?id=26`),
     * in Apple's order. Names come from I18n.
     */
    val GENRES = listOf(
        1301, 1321, 1303, 1304, 1483, 1511, 1512, 1487, 1305, 1502,
        1310, 1489, 1314, 1533, 1324, 1545, 1309, 1318, 1488,
    )

    /** One page of a show's episodes: [size] rows from [offset]; the cursor is the next offset. */
    fun episodePage(all: List<VideoItem>, offset: Int, size: Int = 50): FeedPage {
        val from = offset.coerceIn(0, all.size)
        val to = minOf(all.size, from + size)
        return FeedPage(all.subList(from, to).toList(), next = if (to < all.size) to.toString() else null)
    }

    /**
     * The legacy iTunes RSS chart (`itunes.apple.com/{cc}/rss/toppodcasts/limit=200/genre={id}/json`,
     * which, unlike the new charts API, takes a genre): show rows keyed by Apple id. `feed.entry` is an
     * object instead of an array when there is a single show.
     */
    fun legacyChartShows(json: String): List<VideoItem> {
        val feed = JSONObject(json).optJSONObject("feed") ?: return emptyList()
        val entries = feed.optJSONArray("entry") ?: feed.optJSONObject("entry")?.let { JSONArray().put(it) } ?: return emptyList()
        return (0 until entries.length()).mapNotNull { i ->
            val o = entries.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.ifBlank { null }
                ?: return@mapNotNull null
            val images = o.optJSONArray("im:image")
            val image = images?.optJSONObject(images.length() - 1)?.optString("label")
            VideoItem(
                source = SourceId.PODCASTS,
                id = SHOW_PREFIX + id,
                title = o.optJSONObject("im:name")?.optString("label").orEmpty(),
                author = o.optJSONObject("im:artist")?.optString("label")?.ifBlank { null },
                thumbnailUrl = https(image?.ifBlank { null })?.replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb."),
            )
        }
    }

    /**
     * Parses an RSS feed, stopping after [maxItems] items. A malformed feed returns what was read
     * before the error (many feeds have stray HTML entities near the end).
     */
    fun parseRss(input: InputStream, maxItems: Int = 3000): RssFeed {
        val handler = RssHandler(maxItems)
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        for (f in listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
        )) runCatching { factory.setFeature(f, false) }
        try {
            factory.newSAXParser().parse(InputSource(input), handler)
        } catch (_: StopParsing) {
        } catch (e: SAXException) {
            if (handler.episodes.isEmpty() && handler.channelTitle.isBlank()) throw e
        }
        return RssFeed(
            title = handler.channelTitle.trim(),
            author = handler.channelAuthor?.trim()?.ifBlank { null },
            imageUrl = handler.channelImage ?: handler.channelImageUrl,
            episodes = handler.episodes,
        )
    }

    /** Episode rows, newest first, at most [cap]. [feedUrl] becomes each row's authorId. */
    fun episodeItems(feed: RssFeed, feedUrl: String, cap: Int = MAX_EPISODES, fallbackName: String? = null): List<VideoItem> {
        val eps = if (feed.episodes.all { it.publishedMs != null }) feed.episodes.sortedByDescending { it.publishedMs }
        else feed.episodes
        val show = feed.title.ifBlank { fallbackName ?: "" }.ifBlank { null }
        return eps.asSequence().distinctBy { it.audioUrl }.take(cap).map {
            VideoItem(
                source = SourceId.PODCASTS,
                id = it.audioUrl,
                title = it.title.ifBlank { show ?: it.audioUrl },
                author = show,
                thumbnailUrl = https(it.imageUrl ?: feed.imageUrl),
                durationSec = it.durationSec,
                authorId = feedUrl,
            )
        }.toList()
    }

    /** `itunes:duration`: plain seconds ("1843", "1843.5") or "mm:ss" / "hh:mm:ss". */
    fun parseDuration(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return it.toLong().takeIf { s -> s > 0 } }
        val parts = t.split(":")
        if (parts.size > 3) return null
        var total = 0L
        for (p in parts) total = total * 60 + (p.trim().toDoubleOrNull()?.toLong() ?: return null)
        return total.takeIf { it > 0 }
    }

    private val DATE_FORMATS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss z", "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss z", "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    /** RFC 822 pubDate ("Fri, 13 Nov 2026 10:45:00 +0000", "... GMT", "... PST"), epoch ms. */
    fun parseDate(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        for (pattern in DATE_FORMATS) {
            val pos = ParsePosition(0)
            val d = runCatching { SimpleDateFormat(pattern, Locale.US).parse(t, pos) }.getOrNull()
            if (d != null && pos.index > 0) return d.time
        }
        return null
    }

    /** Apple charts JSON (`feed.results`): show rows keyed by Apple id. */
    fun chartShows(json: String): List<VideoItem> {
        val results = JSONObject(json).optJSONObject("feed")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VideoItem(
                source = SourceId.PODCASTS,
                id = SHOW_PREFIX + id,
                title = o.optString("name"),
                author = o.optString("artistName").ifBlank { null },
                thumbnailUrl = bigArtwork(o.optString("artworkUrl100")),
            )
        }
    }

    /**
     * iTunes search / lookup JSON (`results`): Apple id → show row keyed by its RSS feed URL.
     * Results without a feed URL are left out.
     */
    fun itunesShows(json: String, genreId: Int? = null): Map<String, VideoItem> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyMap()
        val out = LinkedHashMap<String, VideoItem>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            if (genreId != null && !o.inGenre(genreId)) continue
            val feedUrl = https(o.optString("feedUrl").ifBlank { null }) ?: continue
            val name = o.optString("collectionName").ifBlank { o.optString("trackName") }
            out[o.optLong("collectionId").toString()] = VideoItem(
                source = SourceId.PODCASTS,
                id = SHOW_PREFIX + feedUrl,
                title = name,
                // The show is the "creator", so saving it lists its episodes.
                author = name,
                thumbnailUrl = https(o.optString("artworkUrl600").ifBlank { null } ?: bigArtwork(o.optString("artworkUrl100"))),
                authorId = feedUrl,
            )
        }
        return out
    }

    /** True when an iTunes result lists [genreId] among its `genreIds` (strings in the JSON). */
    private fun JSONObject.inGenre(genreId: Int): Boolean {
        val ids = optJSONArray("genreIds") ?: return false
        return (0 until ids.length()).any { ids.optString(it) == genreId.toString() }
    }

    /** Chart rows with their Apple ids swapped for feed URLs where the lookup found one. */
    fun withFeedUrls(chart: List<VideoItem>, lookedUp: Map<String, VideoItem>): List<VideoItem> = chart.map { row ->
        val found = lookedUp[row.id.removePrefix(SHOW_PREFIX)] ?: return@map row
        found.copy(title = row.title.ifBlank { found.title }, thumbnailUrl = row.thumbnailUrl ?: found.thumbnailUrl)
    }

    /** Cleartext is blocked on Android, so ask for https (most podcast hosts serve both). */
    fun https(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    private fun bigArtwork(url: String?): String? =
        https(url?.ifBlank { null })?.replace("/100x100bb.", "/600x600bb.")

    private class StopParsing : SAXException("enough items")

    private class RssHandler(private val maxItems: Int) : DefaultHandler() {
        val episodes = mutableListOf<RssEpisode>()
        var channelTitle = ""
        var channelAuthor: String? = null
        var channelImage: String? = null
        var channelImageUrl: String? = null

        private val path = ArrayList<String>()
        private val text = StringBuilder()
        private var inItem = false
        private var title = ""
        private var audio: String? = null
        private var image: String? = null
        private var duration: Long? = null
        private var date: Long? = null

        private fun key(uri: String?, localName: String?, qName: String?): String {
            val local = localName?.ifEmpty { null } ?: qName?.substringAfter(':').orEmpty()
            val u = uri.orEmpty().lowercase()
            return when {
                u.contains("itunes.com") -> "itunes:$local"
                u.isNotEmpty() -> "ns:$local"
                // Not namespace-aware: fall back to the prefixed name.
                localName.isNullOrEmpty() && qName?.contains(':') == true -> qName
                else -> local
            }
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
            val k = key(uri, localName, qName)
            path += k
            text.setLength(0)
            when {
                k == "item" && !inItem -> {
                    inItem = true; title = ""; audio = null; image = null; duration = null; date = null
                }
                k == "enclosure" && inItem -> {
                    val url = attrs.getValue("url")?.trim()
                    val type = attrs.getValue("type").orEmpty()
                    if (!url.isNullOrEmpty() && audio == null && !type.startsWith("image")) audio = url
                }
                k == "itunes:image" -> {
                    val href = attrs.getValue("href")?.trim()?.ifEmpty { null }
                    if (inItem) image = image ?: href else channelImage = channelImage ?: href
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (text.length < 4000) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val k = path.removeLastOrNull() ?: return
            val parent = path.lastOrNull()
            val value = text.toString().trim()
            if (inItem) {
                when (k) {
                    "title" -> if (parent == "item") title = value
                    "itunes:duration" -> duration = parseDuration(value)
                    "pubDate" -> date = parseDate(value)
                    "item" -> {
                        inItem = false
                        audio?.let { episodes += RssEpisode(title, it, image, duration, date) }
                        if (episodes.size >= maxItems) throw StopParsing()
                    }
                }
            } else when (k) {
                "title" -> if (parent == "channel" && channelTitle.isEmpty()) channelTitle = value
                "itunes:author" -> if (parent == "channel" && channelAuthor == null) channelAuthor = value
                "url" -> if (parent == "image" && channelImageUrl == null) channelImageUrl = value.ifEmpty { null }
            }
            text.setLength(0)
        }
    }
}
