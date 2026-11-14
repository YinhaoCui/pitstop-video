package app.pitstop.video.importer

import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.I18n
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Apple Music public playlists (checked Nov 2026):
 * 1. the playlist page embeds `<script id="serialized-server-data">` with name, artwork, `trackCount` and the
 *    track lockups (title, separate artist links, album, duration) — all 249 of a 249-track playlist;
 * 2. for ISRCs (and any tracks the page left out) the amp-api is read with the public web-player token that sits
 *    in the page's `/assets/index~….js` bundle (JWT, kid "WebPlayKid"); amp-api only accepts it with
 *    `Origin: https://music.apple.com`. Step 2 is best effort: on any failure the page data is returned.
 */
class AppleMusicImporter(private val http: OkHttpClient) : PlaylistImporter {
    override val platform = "Apple Music"

    override fun canHandle(url: String): Boolean = AppleMusicParse.ref(url) != null

    override suspend fun fetch(url: String): ImportedList {
        val ref = AppleMusicParse.ref(url) ?: throw UserFacingException(I18n.s.importBadLink)
        val html = page("https://music.apple.com/${ref.storefront}/playlist/${ref.id}")
        val parsed = AppleMusicParse.page(html)
        val amp = runCatching { ampTracks(ref, AppleMusicParse.bundlePath(html)) }.getOrNull()
        val list = AppleMusicParse.merge(parsed, amp)
        if (list.tracks.isEmpty()) throw UserFacingException(I18n.s.importEmpty)
        return list
    }

    private suspend fun page(url: String): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()).execute().use { r ->
                if (r.code == 404) throw UserFacingException(I18n.s.importNotFound)
                if (!r.isSuccessful) throw UserFacingException(I18n.s.importFailed)
                r.body?.string() ?: throw UserFacingException(I18n.s.importFailed)
            }
        } catch (e: IOException) {
            throw UserFacingException(I18n.s.importFailed)
        }
    }

    private suspend fun ampTracks(ref: AppleMusicParse.Ref, bundlePath: String?): List<AppleMusicParse.AmpTrack>? {
        val token = cachedToken ?: run {
            val js = http.getString(Request.Builder().url("https://music.apple.com${bundlePath ?: return null}").header("User-Agent", DESKTOP_UA).build())
            AppleMusicParse.webToken(js)?.also { cachedToken = it }
        } ?: return null
        val out = mutableListOf<AppleMusicParse.AmpTrack>()
        var next: String? = "/v1/catalog/${ref.storefront}/playlists/${ref.id}/tracks?limit=100"
        var pages = 0
        while (next != null && pages++ < 100) {
            val req = Request.Builder().url("https://amp-api.music.apple.com$next")
                .header("User-Agent", DESKTOP_UA)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .build()
            val page = try {
                AppleMusicParse.ampPage(JSONObject(http.getString(req)))
            } catch (e: IOException) {
                cachedToken = null // expired or rotated: fetch it again next time
                throw e
            }
            out += page.tracks
            next = page.next?.let { if ("limit=" in it) it else "$it&limit=100" }
        }
        return out
    }

    private companion object {
        @Volatile var cachedToken: String? = null
    }
}

/** Pure Apple Music parsing (unit-tested). */
object AppleMusicParse {
    data class Ref(val storefront: String, val id: String)
    data class PageTrack(val songId: String?, val meta: TrackMeta)
    data class Page(val name: String, val cover: String?, val trackCount: Int, val tracks: List<PageTrack>)
    data class AmpTrack(val songId: String, val meta: TrackMeta)
    data class AmpPage(val tracks: List<AmpTrack>, val next: String?)

    /** `music.apple.com/<cc>/playlist/<slug>/pl.<id>` (slug and storefront optional; default storefront "us"). */
    fun ref(url: String): Ref? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (u.host != "music.apple.com" && u.host != "itunes.apple.com" && u.host != "geo.music.apple.com") return null
        val segs = u.pathSegments.filter { it.isNotEmpty() }
        val i = segs.indexOf("playlist").takeIf { it >= 0 } ?: return null
        val id = segs.drop(i + 1).firstOrNull { it.startsWith("pl.") } ?: return null
        val sf = segs.firstOrNull()?.takeIf { i > 0 && it.length == 2 }?.lowercase() ?: "us"
        return Ref(sf, id)
    }

    fun page(html: String): Page {
        val m = Regex("""<script type="application/json" id="serialized-server-data">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: throw UserFacingException(I18n.s.importFailed)
        val root = JSONObject(m.groupValues[1])
        val data = root.optJSONArray("data")?.optJSONObject(0)?.optJSONObject("data")
            ?: throw UserFacingException(I18n.s.importFailed)
        val sections = data.optJSONArray("sections") ?: throw UserFacingException(I18n.s.importFailed)
        var name = ""
        var cover: String? = null
        var count = 0
        val tracks = mutableListOf<PageTrack>()
        for (s in 0 until sections.length()) {
            val sec = sections.optJSONObject(s) ?: continue
            val items = sec.optJSONArray("items") ?: continue
            when (sec.optString("itemKind")) {
                "containerDetailHeaderLockup" -> items.optJSONObject(0)?.let { h ->
                    name = h.optString("title")
                    count = h.optInt("trackCount")
                    cover = h.optJSONObject("artwork")?.optJSONObject("dictionary")?.optString("url")
                        ?.takeIf { it.isNotBlank() }?.let(::artwork)
                }
                "trackLockup" -> for (i in 0 until items.length()) {
                    val t = items.optJSONObject(i) ?: continue
                    val title = t.optString("title").trim()
                    if (title.isEmpty()) continue
                    val links = t.optJSONArray("subtitleLinks")
                    val artists = (0 until (links?.length() ?: 0))
                        .mapNotNull { links!!.optJSONObject(it)?.optString("title")?.trim()?.takeIf { n -> n.isNotEmpty() } }
                        .ifEmpty { splitArtists(t.optString("artistName")) }
                    val album = t.optJSONArray("tertiaryLinks")?.optJSONObject(0)?.optString("title")?.trim()?.takeIf { it.isNotEmpty() }
                    val songId = t.optJSONObject("contentDescriptor")?.optJSONObject("identifiers")?.optString("storeAdamID")?.takeIf { it.isNotBlank() }
                    tracks += PageTrack(songId, TrackMeta(title, artists, album, t.optLong("duration").takeIf { it > 0 }?.let { (it + 500) / 1000 }))
                }
            }
        }
        return Page(name, cover, count, tracks)
    }

    /** Path of the main bundle (`/assets/index~xxxx.js`) that carries the web token. */
    fun bundlePath(html: String): String? =
        Regex("""<script[^>]*src="(/assets/index[^"]*\.js)"""").find(html)?.groupValues?.get(1)

    /** The public web-player developer token (ES256 JWT with kid "WebPlayKid") from the bundle. */
    fun webToken(js: String): String? =
        Regex("""eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""").find(js)?.value

    fun ampPage(json: JSONObject): AmpPage {
        val arr = json.optJSONArray("data")
        val tracks = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val a = o.optJSONObject("attributes") ?: return@mapNotNull null
            val title = a.optString("name").trim().ifEmpty { return@mapNotNull null }
            AmpTrack(
                o.optString("id"),
                TrackMeta(
                    title = title,
                    artists = splitArtists(a.optString("artistName")),
                    album = a.optString("albumName").trim().takeIf { it.isNotEmpty() },
                    durationSec = a.optLong("durationInMillis").takeIf { it > 0 }?.let { (it + 500) / 1000 },
                    isrc = a.optString("isrc").trim().takeIf { it.isNotEmpty() },
                ),
            )
        }
        return AmpPage(tracks, json.optString("next").takeIf { it.isNotBlank() })
    }

    /**
     * Page tracks (clean artist lists) + amp tracks (ISRC, complete). amp order wins when it has at least as many
     * tracks; page artists are kept for songs found in both.
     */
    fun merge(page: Page, amp: List<AmpTrack>?): ImportedList {
        val base = page.tracks.map { it.meta }
        val merged = if (amp == null || amp.size < page.tracks.size) {
            val isrc = amp?.associate { it.songId to it.meta.isrc }.orEmpty()
            page.tracks.map { t -> t.meta.copy(isrc = t.songId?.let { isrc[it] }) }
        } else {
            val byId = page.tracks.filter { it.songId != null }.associateBy { it.songId }
            amp.map { a ->
                val p = byId[a.songId]?.meta
                if (p == null) a.meta else p.copy(isrc = a.meta.isrc, album = p.album ?: a.meta.album)
            }
        }
        return ImportedList(page.name, merged.ifEmpty { base }, page.cover)
    }

    /** "A, B & C" → [A, B, C] (amp-api only gives one display string). */
    fun splitArtists(s: String): List<String> =
        s.split(", ", " & ").map { it.trim() }.filter { it.isNotEmpty() }

    /** Artwork template `…/{w}x{h}bb.{f}` (or `…SC.DN02.{f}`) → 600 px JPEG. */
    fun artwork(url: String): String = url.replace("{w}", "600").replace("{h}", "600").replace("{f}", "jpg")
}
