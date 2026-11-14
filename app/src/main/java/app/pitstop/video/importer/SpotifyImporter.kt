package app.pitstop.video.importer

import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.I18n
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.getString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Spotify public playlists without an API key (checked Nov 2026):
 * 1. `open.spotify.com/embed/playlist/<id>` embeds `__NEXT_DATA__` with the name, the first 100 tracks
 *    (title, "A, B" artists, duration) and an anonymous access token;
 * 2. with that token the web player's GraphQL `fetchPlaylist` query (api-partner.spotify.com/pathfinder) pages
 *    through every track with album and separate artists. The public Web API (`api.spotify.com/v1`) answers
 *    429 QUOTA_EXCEEDED for that token, so it is not used. Neither source exposes ISRC.
 * If step 2 breaks (Spotify rotates the persisted-query hash) the first 100 tracks from step 1 are returned.
 */
class SpotifyImporter(private val http: OkHttpClient) : PlaylistImporter {
    override val platform = "Spotify"

    override fun canHandle(url: String): Boolean = SpotifyParse.playlistId(url) != null

    override suspend fun fetch(url: String): ImportedList {
        val id = SpotifyParse.playlistId(url) ?: throw UserFacingException(I18n.s.importBadLink)
        val html = try {
            http.getString(Request.Builder().url("https://open.spotify.com/embed/playlist/$id").header("User-Agent", DESKTOP_UA).build())
        } catch (e: IOException) {
            throw UserFacingException(I18n.s.importFailed)
        }
        val embed = SpotifyParse.embed(html)
        val full = embed.token?.let { token -> runCatching { fetchAll(id, token) }.getOrNull() }
        val result = if (full != null && full.tracks.size >= embed.list.tracks.size) {
            full.copy(name = full.name.ifBlank { embed.list.name }, coverUrl = full.coverUrl ?: embed.list.coverUrl)
        } else {
            embed.list
        }
        if (result.tracks.isEmpty()) throw UserFacingException(I18n.s.importEmpty)
        return result
    }

    private suspend fun fetchAll(id: String, token: String): ImportedList? {
        val tracks = mutableListOf<TrackMeta>()
        var name = ""
        var cover: String? = null
        var offset = 0
        while (offset < MAX_TRACKS) {
            val req = Request.Builder().url(SpotifyParse.pathfinderUrl(id, offset, PAGE))
                .header("User-Agent", DESKTOP_UA)
                .header("Authorization", "Bearer $token")
                .build()
            val page = SpotifyParse.pathfinderPage(JSONObject(http.getString(req))) ?: return null
            if (offset == 0) {
                name = page.name
                cover = page.cover
            }
            tracks += page.tracks
            offset += page.rawCount
            if (page.rawCount == 0 || offset >= page.total) break
        }
        return ImportedList(name, tracks, cover)
    }

    private companion object {
        const val PAGE = 100
        const val MAX_TRACKS = 10_000
    }
}

/** Pure Spotify parsing (unit-tested). */
object SpotifyParse {
    /** Persisted-query hash of `fetchPlaylist` in the web player bundle (web-player.*.js, Nov 2026). */
    const val FETCH_PLAYLIST_HASH = "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf"

    data class Embed(val list: ImportedList, val token: String?)
    data class Page(val name: String, val cover: String?, val tracks: List<TrackMeta>, val total: Int, val rawCount: Int)

    /** `open.spotify.com[/intl-xx]/playlist/<id>`, `open.spotify.com/embed/playlist/<id>`, or `spotify:playlist:<id>`. */
    fun playlistId(url: String): String? {
        val s = url.trim()
        Regex("""^spotify:(?:user:[^:]+:)?playlist:([A-Za-z0-9]{22})$""").find(s)?.let { return it.groupValues[1] }
        val u = s.toHttpUrlOrNull() ?: return null
        if (u.host != "open.spotify.com" && u.host != "play.spotify.com") return null
        return Regex("""/playlist/([A-Za-z0-9]{22})(?:/|$)""").find(u.encodedPath)?.groupValues?.get(1)
    }

    fun embed(html: String): Embed {
        val m = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: throw UserFacingException(I18n.s.importFailed)
        val state = JSONObject(m.groupValues[1]).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
        val entity = state?.optJSONObject("data")?.optJSONObject("entity")
            ?: throw UserFacingException(I18n.s.importNotFound) // 404 page: pageProps.status = 404
        val list = entity.optJSONArray("trackList")
        val tracks = (0 until (list?.length() ?: 0)).mapNotNull { i ->
            val t = list!!.optJSONObject(i) ?: return@mapNotNull null
            if (t.optString("entityType", "track") != "track") return@mapNotNull null
            val title = t.optString("title").trim().ifEmpty { return@mapNotNull null }
            TrackMeta(
                title = title,
                // Only a display string here; names containing ", " get split (the GraphQL path has them separately).
                artists = t.optString("subtitle").split(", ").map { it.trim() }.filter { it.isNotEmpty() },
                durationSec = t.optLong("duration").takeIf { it > 0 }?.let { (it + 500) / 1000 },
            )
        }
        val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
        val token = state.optJSONObject("settings")?.optJSONObject("session")?.optString("accessToken")?.takeIf { it.isNotBlank() }
        return Embed(
            ImportedList(entity.optString("name").ifBlank { entity.optString("title") }, tracks, cover?.takeIf { it.isNotBlank() }),
            token,
        )
    }

    fun pathfinderUrl(id: String, offset: Int, limit: Int): HttpUrl = HttpUrl.Builder()
        .scheme("https").host("api-partner.spotify.com").addPathSegments("pathfinder/v1/query")
        .addQueryParameter("operationName", "fetchPlaylist")
        .addQueryParameter(
            "variables",
            JSONObject().put("uri", "spotify:playlist:$id").put("offset", offset).put("limit", limit)
                .put("enableWatchFeedEntrypoint", false).toString(),
        )
        .addQueryParameter(
            "extensions",
            JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", FETCH_PLAYLIST_HASH)).toString(),
        )
        .build()

    /** One `fetchPlaylist` page; null when the response is not a playlist (unknown hash, errors, NotFound). */
    fun pathfinderPage(json: JSONObject): Page? {
        val p = json.optJSONObject("data")?.optJSONObject("playlistV2") ?: return null
        if (p.optString("__typename") != "Playlist") return null
        val content = p.optJSONObject("content") ?: return null
        val items = content.optJSONArray("items") ?: return null
        val tracks = (0 until items.length()).mapNotNull { i ->
            val d = items.optJSONObject(i)?.optJSONObject("itemV2")?.optJSONObject("data") ?: return@mapNotNull null
            if (d.optString("__typename") == "Episode") return@mapNotNull null
            val title = d.optString("name").trim().ifEmpty { return@mapNotNull null }
            val artistItems = d.optJSONObject("artists")?.optJSONArray("items")
            TrackMeta(
                title = title,
                artists = (0 until (artistItems?.length() ?: 0)).mapNotNull {
                    artistItems!!.optJSONObject(it)?.optJSONObject("profile")?.optString("name")?.trim()?.takeIf { n -> n.isNotEmpty() }
                },
                album = d.optJSONObject("albumOfTrack")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() },
                durationSec = d.optJSONObject("trackDuration")?.optLong("totalMilliseconds")?.takeIf { it > 0 }?.let { (it + 500) / 1000 },
            )
        }
        val cover = p.optJSONObject("images")?.optJSONArray("items")?.optJSONObject(0)
            ?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
        return Page(p.optString("name"), cover, tracks, content.optInt("totalCount", tracks.size), items.length())
    }
}
