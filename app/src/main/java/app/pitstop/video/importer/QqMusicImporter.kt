package app.pitstop.video.importer

import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.I18n
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.getString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * QQ Music public playlists (歌单). One anonymous call returns the whole list (checked Nov 2026 with a
 * 1297-track playlist): `c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg`. It needs
 * `Referer: https://y.qq.com/` (otherwise `{"subcode":1,"msg":"invalid referer"}`); a missing or private
 * playlist answers `code:-1` with an empty `cdlist`.
 */
class QqMusicImporter(private val http: OkHttpClient) : PlaylistImporter {
    override val platform = "QQ Music"

    override fun canHandle(url: String): Boolean = QqMusicParse.playlistId(url) != null

    override suspend fun fetch(url: String): ImportedList {
        val id = QqMusicParse.playlistId(url) ?: throw UserFacingException(I18n.s.importBadLink)
        val req = Request.Builder()
            .url(
                "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1" +
                    "&onlysong=0&new_format=1&format=json&disstid=$id",
            )
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://y.qq.com/")
            .build()
        val body = try {
            http.getString(req)
        } catch (e: java.io.IOException) {
            throw UserFacingException(I18n.s.importFailed)
        }
        return QqMusicParse.playlist(JSONObject(body))
    }
}

/** Pure QQ Music parsing (unit-tested). */
object QqMusicParse {
    /**
     * Playlist id from the known link shapes: `y.qq.com/n/ryqq/playlist/<id>`, `y.qq.com/n/yqq/playlist/<id>.html`,
     * `i.y.qq.com/n2/m/share/details/taoge.html?id=<id>`, `…/playlist/detail.html?id=<id>`, or `?disstid=<id>`.
     */
    fun playlistId(url: String): String? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (u.host != "y.qq.com" && !u.host.endsWith(".y.qq.com")) return null
        val path = u.encodedPath
        Regex("""/playlist/(\d+)""").find(path)?.let { return it.groupValues[1] }
        val isPlaylistPage = path.contains("taoge") || path.contains("playlist") || u.queryParameter("disstid") != null
        if (!isPlaylistPage) return null
        return (u.queryParameter("disstid") ?: u.queryParameter("id"))?.takeIf { it.matches(Regex("""\d+""")) }
    }

    fun playlist(json: JSONObject): ImportedList {
        val cd = json.optJSONArray("cdlist")?.optJSONObject(0)
        if (cd == null) {
            if (json.optInt("subcode") != 0) throw UserFacingException(I18n.s.importFailed)
            throw UserFacingException(I18n.s.importNotFound)
        }
        val songs = cd.optJSONArray("songlist")
        val tracks = buildList {
            for (i in 0 until (songs?.length() ?: 0)) {
                val s = songs!!.optJSONObject(i) ?: continue
                val title = s.optString("title").ifBlank { s.optString("name") }.trim()
                if (title.isEmpty()) continue
                val singers = s.optJSONArray("singer")
                val artists = (0 until (singers?.length() ?: 0))
                    .mapNotNull { singers!!.optJSONObject(it)?.optString("name")?.trim()?.takeIf { n -> n.isNotEmpty() } }
                add(
                    TrackMeta(
                        title = title,
                        artists = artists,
                        album = s.optJSONObject("album")?.optString("name")?.trim()?.takeIf { it.isNotEmpty() },
                        durationSec = s.optLong("interval").takeIf { it > 0 },
                    ),
                )
            }
        }
        if (tracks.isEmpty()) throw UserFacingException(I18n.s.importEmpty)
        return ImportedList(
            name = cd.optString("dissname").trim(),
            tracks = tracks,
            coverUrl = cd.optString("logo").takeIf { it.isNotBlank() }?.replaceFirst("http://", "https://"),
        )
    }
}
