package app.pitstop.video.live

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Twitch live channels through the public web GraphQL endpoint with the web player's public Client-ID
 * (the same approach as streamlink / yt-dlp). No account needed.
 *
 * Twitch refuses cursor paging (`after:`) on `streams` / `games` without a browser integrity token, so
 * feeds page without cursors: "Popular" page 0 is the top streams overall, page n the top streams of the
 * n-th most watched category; a category's page 0 is its top 100 streams, page n its top 100 streams in
 * the n-th of [TwitchParse.LANGUAGES]. Search pages with `searchFor`'s own cursor, which needs no token.
 */
class TwitchSource(private val http: OkHttpClient) : VideoSource {
    override val id = SourceId.TWITCH
    override val displayName get() = I18n.s.sourceName(SourceId.TWITCH)

    private val lock = Mutex()
    private var topGames: List<String> = emptyList()
    private var topGamesAt = 0L
    private val shown = mutableSetOf<String>()
    /** Channels already listed in the category being paged (language pages overlap the top page). */
    private var catShown: Pair<String, MutableSet<String>> = "" to mutableSetOf()

    override fun feeds(): List<Feed> = listOf(Feed(FEED_POPULAR, I18n.s.popular))

    override suspend fun load(feed: Feed): List<VideoItem> = loadPage(feed, null).items

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        feed.categoryKey()?.let { return categoryPage(it, cursor) }
        val page = cursor?.toIntOrNull() ?: 0
        val items: List<VideoItem>
        val games: List<String>
        if (page == 0) {
            items = TwitchParse.streams(gql(TwitchParse.topStreamsQuery()).optJSONObject("streams"), I18n.s::viewers)
            games = games()
            lock.withLock { shown.clear(); shown += items.map { it.id } }
        } else {
            games = games()
            val game = games.getOrNull(page - 1) ?: return FeedPage(emptyList())
            val all = TwitchParse.streams(
                gql(TwitchParse.gameStreamsQuery(game)).optJSONObject("game")?.optJSONObject("streams"),
                I18n.s::viewers,
            )
            // A channel already listed (e.g. top overall) is not repeated.
            items = lock.withLock { all.filter { shown.add(it.id) } }
        }
        return FeedPage(items, (page + 1).toString().takeIf { page < games.size })
    }

    /** The 100 most watched categories (Twitch gives no more without an integrity token). */
    override suspend fun categories(): List<Category> =
        TwitchParse.categories(gql(TwitchParse.categoriesQuery()).optJSONObject("games"))

    /**
     * One page of a category: page 0 = its top streams, page n = its top streams in language n.
     * Languages with nothing new are skipped (a few per call) so the list doesn't stall.
     */
    private suspend fun categoryPage(game: String, cursor: String?): FeedPage {
        var page = cursor?.toIntOrNull() ?: 0
        val seen = lock.withLock {
            if (page == 0 || catShown.first != game) catShown = game to mutableSetOf()
            catShown.second
        }
        var tries = 0
        while (true) {
            val lang = if (page == 0) null else TwitchParse.LANGUAGES.getOrNull(page - 1) ?: return FeedPage(emptyList())
            val all = TwitchParse.streams(
                gql(TwitchParse.gameStreamsQuery(game, lang, first = 100)).optJSONObject("game")?.optJSONObject("streams"),
                I18n.s::viewers,
            )
            val fresh = lock.withLock { all.filter { seen.add(it.id) } }
            val next = TwitchParse.nextCategoryPage(page, all.size)
            if (fresh.isNotEmpty() || next == null || ++tries >= 4) return FeedPage(fresh, next?.toString())
            page = next
        }
    }

    /** The most watched categories, cached for ten minutes. */
    private suspend fun games(): List<String> {
        lock.withLock { if (topGames.isNotEmpty() && System.currentTimeMillis() - topGamesAt < 600_000) return topGames }
        val list = runCatching { TwitchParse.gameNames(gql(TwitchParse.topGamesQuery()).optJSONObject("games")) }
            .getOrDefault(emptyList())
        lock.withLock {
            if (list.isNotEmpty()) { topGames = list; topGamesAt = System.currentTimeMillis() }
            return topGames
        }
    }

    override suspend fun search(query: String): List<VideoItem> = searchPage(query, null).items

    /** Live channels matching [query], 50 per page; offline channels are dropped from each page. */
    override suspend fun searchPage(query: String, cursor: String?): FeedPage {
        val result = gql(TwitchParse.searchQuery(query, cursor)).optJSONObject("searchFor")
        return FeedPage(TwitchParse.searchResults(result, I18n.s::viewers), TwitchParse.searchCursor(result))
    }

    override suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return TwitchParse.suggestions(gql(TwitchParse.suggestionsQuery(query.trim())).optJSONObject("searchSuggestions"))
    }

    override suspend fun resolve(item: VideoItem): PlayableStream {
        val login = item.id.lowercase()
        val d = try {
            gql(TwitchParse.accessTokenQuery(login))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UserFacingException(I18n.s.liveUnavailable)
        }
        if (d.optJSONObject("user")?.optJSONObject("stream") == null) throw UserFacingException(I18n.s.streamOffline)
        val token = d.optJSONObject("streamPlaybackAccessToken") ?: throw UserFacingException(I18n.s.liveUnavailable)
        val master = TwitchParse.usherUrl(
            login, token.optString("signature"), token.optString("value"), (Math.random() * 999_999).toInt(),
        )
        val headers = mapOf("User-Agent" to DESKTOP_UA)
        if (!StreamPolicy.audioOnly) {
            return PlayableStream(master, headers = headers, kind = PlayableStream.Kind.HLS, live = true)
        }
        val playlist = fetchMaster(master)
        val audio = TwitchParse.audioOnlyVariant(playlist)
            ?: return PlayableStream(master, headers = headers, kind = PlayableStream.Kind.HLS, live = true)
        return PlayableStream(audio, headers = headers, kind = PlayableStream.Kind.HLS, live = true, audioOnly = true)
    }

    /** The usher master playlist; 404 means the channel went offline meanwhile. */
    private suspend fun fetchMaster(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()).execute().use { resp ->
            when {
                resp.code == 404 -> throw UserFacingException(I18n.s.streamOffline)
                !resp.isSuccessful -> throw UserFacingException(I18n.s.liveUnavailable)
                else -> resp.body?.string() ?: throw UserFacingException(I18n.s.liveUnavailable)
            }
        }
    }

    /** Runs one GQL request and returns its `data`; GraphQL errors become exceptions. */
    private suspend fun gql(body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(GQL_URL)
            .header("Client-ID", CLIENT_ID)
            .header("User-Agent", DESKTOP_UA)
            .post(body.toString().toRequestBody("text/plain;charset=UTF-8".toMediaType()))
            .build()
        val json = JSONObject(http.getString(req))
        val errors = json.optJSONArray("errors")
        val data = json.optJSONObject("data")
        if (errors != null && errors.length() > 0 && data == null) {
            throw IllegalStateException("Twitch: " + errors.optJSONObject(0)?.optString("message"))
        }
        return data ?: JSONObject()
    }

    companion object {
        const val FEED_POPULAR = "popular"
        private const val GQL_URL = "https://gql.twitch.tv/gql"
        /** The Twitch web site's public client id (also used by streamlink and yt-dlp). */
        private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    }
}

/** Pure GQL query building / parsing for Twitch (no Android types; unit-tested). */
object TwitchParse {
    private const val STREAM_FIELDS =
        "title viewersCount previewImageURL(width:440,height:248) broadcaster{login displayName}"

    private fun query(text: String, variables: JSONObject = JSONObject()) =
        JSONObject().put("query", text).put("variables", variables)

    fun topStreamsQuery() = query("query{streams(first:30){edges{node{$STREAM_FIELDS}}}}")

    fun topGamesQuery() = query("query{games(first:30){edges{node{name}}}}")

    /**
     * Broadcaster languages a category is paged through after its top page (GQL `Language` enum
     * values, verified Nov 2026; an unknown value fails the whole request), roughly by stream count.
     */
    val LANGUAGES = listOf(
        "EN", "ES", "PT", "DE", "FR", "RU", "JA", "ZH", "IT", "AR", "KO", "PL", "UK", "TR",
        "NL", "SV", "DA", "CS", "NO", "HU", "TH", "FI", "EL", "ID", "VI", "OTHER",
    )

    /** Top categories with box art; `first` is capped at 100 and `after:` needs an integrity token. */
    fun categoriesQuery() =
        query("query{games(first:100){edges{node{name displayName boxArtURL(width:144,height:192)}}}}")

    /** Top streams of one category, optionally only in one broadcaster [language] (see [LANGUAGES]). */
    fun gameStreamsQuery(game: String, language: String? = null, first: Int = 30): JSONObject {
        val vars = JSONObject().put("name", game)
        if (language != null) vars.put("langs", JSONArray().put(language))
        return query(
            "query(\$name:String!,\$langs:[Language!]){game(name:\$name){streams(first:$first," +
                "options:{broadcasterLanguages:\$langs}){edges{node{$STREAM_FIELDS}}}}}",
            vars,
        )
    }

    /** Page after [page] of a category feed given how many streams it returned; null at the end. */
    fun nextCategoryPage(page: Int, returned: Int): Int? = when {
        // A short top page: the category has no more live streams than that.
        page == 0 && returned < 25 -> null
        page >= LANGUAGES.size -> null
        else -> page + 1
    }

    /** Channel search; [cursor] is the previous page's `channels.cursor` (an offset; no token needed). */
    fun searchQuery(text: String, cursor: String? = null, limit: Int = 50) = query(
        "query(\$q:String!,\$c:String!,\$n:Int!){searchFor(userQuery:\$q,platform:\"web\"," +
            "target:{index:CHANNEL,cursor:\$c,limit:\$n}){channels{cursor pageInfo{hasNextPage} edges{item{... on User{" +
            "login displayName stream{title viewersCount previewImageURL(width:440,height:248)}}}}}}}",
        JSONObject().put("q", text).put("c", cursor ?: "").put("n", limit),
    )

    fun suggestionsQuery(text: String) = query(
        "query(\$q:String!){searchSuggestions(queryFragment:\$q){edges{node{text}}}}",
        JSONObject().put("q", text),
    )

    /** Live check and playback token in one request. */
    fun accessTokenQuery(login: String) = query(
        "query(\$login:String!){user(login:\$login){stream{id}} streamPlaybackAccessToken(channelName:\$login," +
            "params:{platform:\"web\",playerBackend:\"mediaplayer\",playerType:\"site\"}){value signature}}",
        JSONObject().put("login", login),
    )

    /** `streams` connection (top streams or one game's streams) → items. */
    fun streams(connection: JSONObject?, viewers: (Long) -> String): List<VideoItem> =
        connection?.optJSONArray("edges").objects().mapNotNull { e ->
            val node = e.optJSONObject("node") ?: return@mapNotNull null
            val b = node.optJSONObject("broadcaster") ?: return@mapNotNull null
            item(b.optString("login"), b.optString("displayName"), node, viewers)
        }

    /** `searchFor` result → live channels only. */
    fun searchResults(searchFor: JSONObject?, viewers: (Long) -> String): List<VideoItem> =
        searchFor?.optJSONObject("channels")?.optJSONArray("edges").objects().mapNotNull { e ->
            val user = e.optJSONObject("item") ?: return@mapNotNull null
            val stream = user.optJSONObject("stream") ?: return@mapNotNull null
            item(user.optString("login"), user.optString("displayName"), stream, viewers)
        }

    /** `searchFor` → the next page's cursor, null when there is none. */
    fun searchCursor(searchFor: JSONObject?): String? {
        val ch = searchFor?.optJSONObject("channels") ?: return null
        if (ch.optJSONObject("pageInfo")?.optBoolean("hasNextPage", false) != true) return null
        if (ch.optJSONArray("edges").objects().isEmpty()) return null
        return ch.str("cursor")
    }

    /** `searchSuggestions` → distinct texts (case-insensitively). */
    fun suggestions(conn: JSONObject?): List<String> =
        conn?.optJSONArray("edges").objects()
            .mapNotNull { it.optJSONObject("node")?.str("text")?.trim()?.ifEmpty { null } }
            .distinctBy { it.lowercase() }

    /** `games` connection → categories; the key is the name `game(name:)` takes. */
    fun categories(games: JSONObject?): List<Category> =
        games?.optJSONArray("edges").objects().mapNotNull { e ->
            val node = e.optJSONObject("node") ?: return@mapNotNull null
            val name = node.str("name") ?: return@mapNotNull null
            Category(name, node.str("displayName") ?: name, thumbnailUrl = node.str("boxArtURL"))
        }

    fun gameNames(games: JSONObject?): List<String> =
        games?.optJSONArray("edges").objects().mapNotNull { it.optJSONObject("node")?.optString("name")?.ifEmpty { null } }

    private fun item(login: String, displayName: String, stream: JSONObject, viewers: (Long) -> String): VideoItem? {
        if (login.isEmpty()) return null
        val name = displayName.ifEmpty { login }
        val n = stream.optLong("viewersCount")
        return VideoItem(
            source = SourceId.TWITCH,
            id = login,
            title = stream.str("title") ?: name,
            author = if (n > 0) "$name · ${viewers(n)}" else name,
            thumbnailUrl = stream.str("previewImageURL"),
            durationSec = null,
            authorId = login,
        )
    }

    /** The usher master playlist URL for a live channel (parameters as streamlink sends them). */
    fun usherUrl(login: String, sig: String, token: String, random: Int): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val params = linkedMapOf(
            "platform" to "web",
            "p" to random.toString(),
            "allow_source" to "true",
            "allow_audio_only" to "true",
            "playlist_include_framerate" to "true",
            "multigroup_video" to "true",
            "supported_codecs" to "h264",
            "fast_bread" to "true",
            "sig" to sig,
            "token" to token,
        )
        return "https://usher.ttvnw.net/api/v2/channel/hls/${enc(login.lowercase())}.m3u8?" +
            params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /** URL of the `audio_only` variant in a Twitch master playlist, or null. */
    fun audioOnlyVariant(master: String): String? {
        val lines = master.lines()
        for ((i, line) in lines.withIndex()) {
            if (line.startsWith("#EXT-X-STREAM-INF") && "VIDEO=\"audio_only\"" in line) {
                return lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
            }
        }
        return null
    }

    /** Non-empty string field; JSON null gives null (Android's optString would return "null"). */
    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
