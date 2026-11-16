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
import java.security.MessageDigest

/**
 * Kugou public playlists (checked Nov 2026, all over https because Android blocks cleartext):
 * - songs: `pubsongscdn.kugou.com/v2/get_other_list_file` (what the m.kugou.com share page calls), signed like
 *   Kugou's own `infSign.min.js` H5 signer, up to 300 per page, by `global_collection_id` or `specialid`;
 * - `gcid_…` links: the m.kugou.com songlist page (mobile UA) embeds `window.$output` with the list name and
 *   its `global_collection_id` or `specialid`;
 * - names of official specials: `mobileservice.kugou.com/api/v3/special/info`.
 */
class KugouImporter(private val http: OkHttpClient) : PlaylistImporter {
    override val platform = "Kugou"

    override fun canHandle(url: String): Boolean = KugouParse.ref(url) != null

    override suspend fun fetch(url: String): ImportedList {
        val linked = KugouParse.ref(url) ?: throw UserFacingException(I18n.s.importBadLink)
        val head: KugouParse.SonglistPage = when (linked) {
            is KugouRef.Encoded -> {
                val html = get(
                    Request.Builder().url("https://m.kugou.com/songlist/${linked.gcid}/").header("User-Agent", MOBILE_UA).build(),
                )
                KugouParse.songlistPage(html) ?: throw UserFacingException(I18n.s.importNotFound)
            }
            is KugouRef.Special -> runCatching {
                val info = JSONObject(
                    get(request("https://mobileservice.kugou.com/api/v3/special/info?specialid=${linked.id}")),
                ).optJSONObject("data")
                KugouParse.SonglistPage(
                    linked,
                    info?.optString("specialname")?.takeIf { it.isNotBlank() },
                    info?.optString("imgurl")?.takeIf { it.isNotBlank() }?.let(KugouParse::image),
                )
            }.getOrElse { KugouParse.SonglistPage(linked, null, null) }
            is KugouRef.Collection -> KugouParse.SonglistPage(linked, null, null)
        }
        val ref = head.ref
        val tracks = mutableListOf<TrackMeta>()
        var page = 1
        while (page <= MAX_PAGES) {
            val now = System.currentTimeMillis().toString()
            val req = Request.Builder().url(KugouParse.songsUrl(ref, page, now.toLong()))
                .header("User-Agent", DESKTOP_UA)
                // Same extra headers the share page sends (fresh timestamps, nothing identifying).
                .header("clienttime", now).header("mid", now).header("uuid", now).header("dfid", "-")
                .build()
            val json = JSONObject(get(req))
            val p = KugouParse.songsPage(json)
            tracks += p.tracks
            if (p.rawCount == 0 || tracks.size >= p.total || p.rawCount < PAGE_SIZE) break
            page++
        }
        if (tracks.isEmpty()) throw UserFacingException(I18n.s.importEmpty)
        return ImportedList(head.name ?: I18n.s.importKugouList, tracks, head.cover)
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()

    private suspend fun get(req: Request): String = try {
        http.getString(req)
    } catch (e: IOException) {
        throw UserFacingException(I18n.s.importFailed)
    }

    companion object {
        const val PAGE_SIZE = 300
        private const val MAX_PAGES = 34 // ~10k songs, Kugou's own playlist limit
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}

/** Which Kugou list a link points at. */
sealed interface KugouRef {
    /** Official "special" (`yy/special/single/<id>.html`, `?specialid=`). */
    data class Special(val id: Long) : KugouRef
    /** User or official collection, `collection_3_<uid>_<listid>_0`. */
    data class Collection(val gcid: String) : KugouRef
    /** Opaque `gcid_…` code of the songlist pages; resolved via the m.kugou.com page. */
    data class Encoded(val gcid: String) : KugouRef
}

/** Pure Kugou parsing + signing (unit-tested). */
object KugouParse {
    private const val SIGN_KEY = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"

    data class SonglistPage(val ref: KugouRef, val name: String?, val cover: String?)
    data class SongsPage(val tracks: List<TrackMeta>, val total: Int, val rawCount: Int)

    fun ref(url: String): KugouRef? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (u.host != "kugou.com" && !u.host.endsWith(".kugou.com")) return null
        Regex("""(gcid_[0-9a-z]+)""").find(u.encodedPath)?.let { return KugouRef.Encoded(it.groupValues[1]) }
        u.queryParameter("global_collection_id")?.takeIf { it.startsWith("collection_") }?.let { return KugouRef.Collection(it) }
        val uid = u.queryParameter("uid")?.toLongOrNull()
        val listId = u.queryParameter("listid")?.toLongOrNull()
        if (u.encodedPath.contains("zlist") && uid != null && uid > 0 && listId != null) {
            return KugouRef.Collection("collection_3_${uid}_${listId}_0")
        }
        (u.queryParameter("specialid") ?: u.queryParameter("specialId"))?.toLongOrNull()?.takeIf { it > 0 }
            ?.let { return KugouRef.Special(it) }
        Regex("""/special/single/(\d+)""").find(u.encodedPath)?.let { return KugouRef.Special(it.groupValues[1].toLong()) }
        return null
    }

    /** Kugou H5 signature: md5(key + sorted "k=v" pairs concatenated + key), as in infSign.min.js. */
    fun sign(params: Map<String, String>): String {
        val s = SIGN_KEY + params.keys.sorted().joinToString("") { "$it=${params[it]}" } + SIGN_KEY
        return MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun songsUrl(ref: KugouRef, page: Int, nowMs: Long): HttpUrl {
        val t = nowMs.toString()
        val params = linkedMapOf(
            "srcappid" to "2919", "clientver" to "20000", "appid" to "1058", "type" to "0", "module" to "playlist",
            "page" to page.toString(), "pagesize" to KugouImporter.PAGE_SIZE.toString(),
            "mid" to t, "uuid" to t, "dfid" to "-",
        )
        when (ref) {
            is KugouRef.Collection -> params["global_collection_id"] = ref.gcid
            is KugouRef.Special -> params["specialid"] = ref.id.toString()
            is KugouRef.Encoded -> error("resolve gcid_ codes first")
        }
        params["signature"] = sign(params)
        val b = HttpUrl.Builder().scheme("https").host("pubsongscdn.kugou.com").addPathSegments("v2/get_other_list_file")
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    fun songsPage(json: JSONObject): SongsPage {
        val data = json.optJSONObject("data")
        if (json.optInt("status") != 1 || data == null || !data.has("info")) {
            throw UserFacingException(I18n.s.importNotFound)
        }
        val info = data.optJSONArray("info")
        val n = info?.length() ?: 0
        val tracks = (0 until n).mapNotNull { i -> info!!.optJSONObject(i)?.let(::track) }
        return SongsPage(tracks, data.optInt("count", tracks.size), n)
    }

    /** One song. `name` is "Artist1、Artist2 - Title"; `singerinfo` / `albuminfo` carry the clean values. */
    fun track(s: JSONObject): TrackMeta? {
        val (prefixArtists, title) = splitName(s.optString("name"))
        if (title.isEmpty()) return null
        val singers = s.optJSONArray("singerinfo")
        val artists = (0 until (singers?.length() ?: 0))
            .mapNotNull { singers!!.optJSONObject(it)?.optString("name")?.trim()?.takeIf { n -> n.isNotEmpty() } }
            .ifEmpty { prefixArtists }
        return TrackMeta(
            title = title,
            artists = artists,
            album = s.optJSONObject("albuminfo")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() },
            durationSec = s.optLong("timelen").takeIf { it > 0 }?.let { (it + 500) / 1000 },
        )
    }

    /** "A、B - Title (Live)" → ([A, B], "Title (Live)"); a name without " - " is all title. */
    fun splitName(name: String): Pair<List<String>, String> {
        val i = name.indexOf(" - ")
        if (i <= 0) return emptyList<String>() to name.trim()
        val artists = name.substring(0, i).split('、').map { it.trim() }.filter { it.isNotEmpty() }
        return artists to name.substring(i + 3).trim()
    }

    /** `window.$output = {...}` of m.kugou.com/songlist/gcid_…; null when missing or private. */
    fun songlistPage(html: String): SonglistPage? {
        val start = html.indexOf("window.\$output")
        if (start < 0) return null
        val brace = html.indexOf('{', start)
        if (brace < 0) return null
        val json = runCatching { JSONObject(org.json.JSONTokener(html.substring(brace))) }.getOrNull() ?: return null
        val li = json.optJSONObject("info")?.optJSONObject("listinfo") ?: return null
        if (li.optInt("is_pri") == 1) return null
        val gcid = li.optString("global_collection_id").ifBlank { json.optString("global_collection_id") }
        val ref = when {
            gcid.startsWith("collection_") -> KugouRef.Collection(gcid)
            li.optLong("specialid") > 0 -> KugouRef.Special(li.optLong("specialid"))
            else -> return null
        }
        return SonglistPage(ref, li.optString("name").takeIf { it.isNotBlank() }, li.optString("pic").takeIf { it.isNotBlank() }?.let(::image))
    }

    /** Kugou image templates: `http://imge.kugou.com/…/{size}/…` → https, 400 px. */
    fun image(url: String): String = url.replace("{size}", "400").replaceFirst("http://", "https://")
}
