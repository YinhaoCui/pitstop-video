package app.pitstop.video.radio

import app.pitstop.video.core.AppLanguage
import app.pitstop.video.core.Category
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.Option
import app.pitstop.video.core.OptionGroup
import app.pitstop.video.core.Picked
import app.pitstop.video.core.pick
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.categoryKey
import app.pitstop.video.core.getString
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Internet radio from the community radio-browser.info directory. Only stations that passed the
 * directory's last check and stream over https (cleartext is blocked on Android) are listed.
 */
class RadioSource(private val http: OkHttpClient) : VideoSource {
    override val id = SourceId.RADIO
    override val displayName get() = I18n.s.radio

    @Volatile private var server: String? = null

    override fun feeds(): List<Feed> = listOf(
        Feed(FEED_TOP, I18n.s.radioTop),
        Feed(FEED_LOCAL, I18n.s.radioLocal),
        Feed(FEED_CHINESE, I18n.s.radioChinese),
    )

    override suspend fun load(feed: Feed) = loadPage(feed, null).items

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        val filter = feed.categoryKey()?.let { RadioParser.categoryFilter(it) ?: return FeedPage(emptyList()) }
            ?: when (feed.key) {
                FEED_LOCAL -> listOf("countrycode" to localCountry())
                FEED_CHINESE -> listOf("language" to "chinese")
                else -> emptyList()
            }
        return page(filter, cursor)
    }

    /** Stations matching [filter], most voted first (or by [order]), [PAGE] at a time (cursor = offset). */
    private suspend fun page(filter: List<Pair<String, String>>, cursor: String?, order: String = "votes"): FeedPage {
        val offset = cursor?.toIntOrNull() ?: 0
        // Names read A-Z; every other order is "most first".
        val json = search(filter + listOf("order" to order, "reverse" to "${order != "name"}", "offset" to "$offset", "limit" to "$PAGE"))
        val count = JSONArray(json).length()
        return FeedPage(RadioParser.stations(json), next = if (count >= PAGE) "${offset + PAGE}" else null)
    }

    override suspend fun search(query: String): List<VideoItem> = searchPage(query, null).items

    override suspend fun searchPage(query: String, cursor: String?): FeedPage = page(listOf("name" to query), cursor)

    override fun searchOptions(): List<OptionGroup> {
        val s = I18n.s
        return listOf(OptionGroup(SORT, s.optSort, listOf(
            Option("votes", s.optTopVoted), Option("clickcount", s.optMostPlayed),
            Option("name", s.optName), Option("lastchangetime", s.optRecentlyUpdated),
        )))
    }

    override suspend fun searchPage(query: String, cursor: String?, picked: Picked): FeedPage =
        page(listOf("name" to query), cursor, picked.pick(SORT, "votes"))

    /**
     * Genres (tags), countries and languages, each with enough working stations to be worth a list;
     * the names are the directory's own. A list that fails to load is left out.
     */
    override suspend fun categories(): List<Category> = coroutineScope {
        val s = I18n.s
        fun list(path: String) = async {
            runCatching { api(path, listOf("order" to "stationcount", "reverse" to "true", "hidebroken" to "true")) }.getOrNull()
        }
        val tags = list("/json/tags")
        val countries = list("/json/countries")
        val languages = list("/json/languages")
        tags.await()?.let { RadioParser.tagCategories(it, s.radioGenres) }.orEmpty() +
            countries.await()?.let { RadioParser.countryCategories(it, s.radioCountries) }.orEmpty() +
            languages.await()?.let { RadioParser.languageCategories(it, s.radioLanguages) }.orEmpty()
    }

    override suspend fun resolve(item: VideoItem): PlayableStream {
        // Fresh details: stations move their stream URLs now and then (saved items may be old).
        val fresh = runCatching {
            RadioParser.stations(api("/json/stations/byuuid/${item.id}", emptyList())).firstOrNull()
        }.getOrNull()
        val url = RadioParser.streamUrl(fresh ?: item) ?: throw UserFacingException(I18n.s.radioNoStream)
        return PlayableStream(
            url,
            kind = if (RadioParser.isHls(fresh ?: item)) PlayableStream.Kind.HLS else PlayableStream.Kind.PROGRESSIVE,
            live = true,
        )
    }

    private fun localCountry(): String =
        Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase()
            ?: if (I18n.language.value == AppLanguage.ZH) "CN" else "US"

    private suspend fun search(params: List<Pair<String, String>>) = api(
        "/json/stations/search",
        params + listOfNotNull(("reverse" to "true").takeIf { params.none { it.first == "reverse" } }, "hidebroken" to "true", "is_https" to "true"),
    )

    /** Calls one API server; on failure picks another from the server list and retries once. */
    private suspend fun api(path: String, params: List<Pair<String, String>>): String {
        val first = server ?: pickServer()
        return try {
            get(first, path, params)
        } catch (e: IOException) {
            val other = runCatching { pickServer(avoid = first) }.getOrNull()
            if (other == null || other == first) throw e
            get(other, path, params)
        }
    }

    private suspend fun get(host: String, path: String, params: List<Pair<String, String>>): String {
        val url = "https://$host$path".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return http.getString(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
    }

    /** The directory's documented way to find a mirror: the server list, picked at random. */
    private suspend fun pickServer(avoid: String? = null): String {
        val list = runCatching {
            RadioParser.servers(http.getString(Request.Builder().url("https://all.api.radio-browser.info/json/servers")
                .header("User-Agent", USER_AGENT).build()))
        }.getOrDefault(emptyList())
        val pick = (list - setOfNotNull(avoid)).randomOrNull() ?: list.randomOrNull() ?: FALLBACK_SERVER
        server = pick
        return pick
    }

    companion object {
        const val FEED_TOP = "top"
        private const val SORT = "order"
        const val FEED_LOCAL = "local"
        const val FEED_CHINESE = "chinese"
        const val PAGE = 50
        const val USER_AGENT = "PitstopVideo/0.5 (Android)"
        private const val FALLBACK_SERVER = "de1.api.radio-browser.info"
    }
}

/** Pure parsing of radio-browser.info JSON (no Android types, so it runs in JVM unit tests). */
object RadioParser {
    private const val HLS_MARK = "hls|"

    /** Station rows. [VideoItem.extra] keeps the stream URL (prefixed "hls|" for HLS streams). */
    fun stations(json: String): List<VideoItem> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val uuid = o.optString("stationuuid").ifBlank { null } ?: return@mapNotNull null
            if (o.optInt("lastcheckok", 1) != 1) return@mapNotNull null
            val stream = listOf(o.optString("url_resolved"), o.optString("url"))
                .map { it.trim() }.firstOrNull { it.startsWith("https://") } ?: return@mapNotNull null
            val hls = o.optInt("hls") == 1 || stream.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            val tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
            val author = (listOf(o.optString("country").trim()).filter { it.isNotEmpty() } + tags)
                .joinToString(" · ").ifBlank { null }
            VideoItem(
                source = SourceId.RADIO,
                id = uuid,
                title = o.optString("name").trim().ifBlank { stream },
                author = author,
                thumbnailUrl = o.optString("favicon").trim().takeIf { it.startsWith("https://") },
                durationSec = null,
                extra = if (hls) HLS_MARK + stream else stream,
            )
        }.distinctBy { it.id }
    }

    /** Least stations a tag / country / language needs to be listed as a category. */
    const val MIN_TAG_STATIONS = 100
    const val MIN_COUNTRY_STATIONS = 10
    const val MIN_LANGUAGE_STATIONS = 20
    /** Category keys: `tag:<name>`, `country:<ISO code>`, `lang:<name>`. */
    private const val TAG = "tag:"
    private const val COUNTRY = "country:"
    private const val LANG = "lang:"

    /** `/json/tags` → genre categories (key `tag:<name>`) with at least [min] stations. */
    fun tagCategories(json: String, group: String, min: Int = MIN_TAG_STATIONS): List<Category> =
        named(json, min) { o -> o.optString("name").trim().ifEmpty { null }?.let { Category(TAG + it, it, group) } }

    /** `/json/countries` → country categories (key `country:<ISO 3166-1 code>`, title = `name`). */
    fun countryCategories(json: String, group: String, min: Int = MIN_COUNTRY_STATIONS): List<Category> =
        named(json, min) { o ->
            val code = o.optString("iso_3166_1").trim().uppercase().takeIf { it.length == 2 } ?: return@named null
            Category(COUNTRY + code, o.optString("name").trim().ifEmpty { code }, group)
        }

    /** `/json/languages` → language categories (key `lang:<name>`). */
    fun languageCategories(json: String, group: String, min: Int = MIN_LANGUAGE_STATIONS): List<Category> =
        named(json, min) { o -> o.optString("name").trim().ifEmpty { null }?.let { Category(LANG + it, it, group) } }

    private fun named(json: String, min: Int, make: (JSONObject) -> Category?): List<Category> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optInt("stationcount") < min) return@mapNotNull null
            make(o)
        }.distinctBy { it.key }
    }

    /** Station-search parameters for a category key from the functions above; null for an unknown key. */
    fun categoryFilter(key: String): List<Pair<String, String>>? = when {
        key.startsWith(TAG) -> listOf("tag" to key.removePrefix(TAG), "tagExact" to "true")
        key.startsWith(COUNTRY) -> listOf("countrycode" to key.removePrefix(COUNTRY))
        key.startsWith(LANG) -> listOf("language" to key.removePrefix(LANG), "languageExact" to "true")
        else -> null
    }.takeIf { it?.first()?.second?.isNotBlank() == true }

    fun streamUrl(item: VideoItem): String? = item.extra?.removePrefix(HLS_MARK)?.ifBlank { null }

    fun isHls(item: VideoItem): Boolean = item.extra?.startsWith(HLS_MARK) == true

    /** `/json/servers`: host names, without duplicates (each host is listed for IPv4 and IPv6). */
    fun servers(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.ifBlank { null } }.distinct()
    }
}
