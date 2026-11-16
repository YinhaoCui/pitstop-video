package app.pitstop.video.netease

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Category
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/** NetEase JSON → app models, and paging cursors. Pure JVM (unit-tested); no Android types. */
object NeteaseParse {
    /** Category keys: a playlist tag ("华语", "驾车", ...) or an official chart (toplist id). */
    const val TAG_PREFIX = "tag:"
    const val CHART_PREFIX = "chart:"

    sealed interface CategoryRef
    data class Tag(val name: String) : CategoryRef
    data class Chart(val id: Long) : CategoryRef

    fun categoryRef(key: String): CategoryRef? = when {
        key.startsWith(TAG_PREFIX) -> Tag(key.removePrefix(TAG_PREFIX)).takeIf { it.name.isNotEmpty() }
        key.startsWith(CHART_PREFIX) -> key.removePrefix(CHART_PREFIX).toLongOrNull()?.let(::Chart)
        else -> null
    }

    /**
     * Official charts first (under [chartsGroup]), then "全部歌单" and every playlist tag under its group
     * (语种 / 风格 / 场景 / 情感 / 主题), in NetEase's order. [catalogue] = playlist/catalogue, [toplists] = toplist.
     */
    fun categories(catalogue: JSONObject?, toplists: JSONObject?, chartsGroup: String): List<Category> = buildList {
        toplists?.optJSONArray("list").objects().forEach { t ->
            val id = t.optLong("id").takeIf { it > 0 } ?: return@forEach
            add(Category(CHART_PREFIX + id, t.optString("name"), chartsGroup, cover(t.optString("coverImgUrl"))))
        }
        if (catalogue == null) return@buildList
        catalogue.optJSONObject("all")?.optString("name")?.ifEmpty { null }?.let { add(Category(TAG_PREFIX + ALL, it)) }
        val groups = catalogue.optJSONObject("categories")
        val subs = catalogue.optJSONArray("sub").objects()
        val groupIds = subs.map { it.optInt("category") }.distinct().sorted()
        for (g in groupIds) {
            val heading = groups?.optString(g.toString())?.ifEmpty { null }
            subs.filter { it.optInt("category") == g }.forEach { s ->
                val name = s.optString("name").ifEmpty { return@forEach }
                add(Category(TAG_PREFIX + name, name, heading, cover(s.optString("imgUrl"))))
            }
        }
    }

    /** The tag that lists every playlist (NetEase's own name for it). */
    const val ALL = "全部"

    fun song(o: JSONObject): VideoItem {
        val artists = (o.optJSONArray("ar") ?: o.optJSONArray("artists")).objects()
        return VideoItem(
            source = SourceId.NETEASE,
            id = o.optLong("id").toString(),
            title = o.optString("name"),
            author = artists.joinToString(" / ") { it.optString("name") }.ifEmpty { null },
            thumbnailUrl = cover((o.optJSONObject("al") ?: o.optJSONObject("album"))?.optString("picUrl")),
            durationSec = (o.optLong("dt").takeIf { it > 0 } ?: o.optLong("duration").takeIf { it > 0 })?.div(1000),
            authorId = artists.firstOrNull()?.optLong("id")?.takeIf { it > 0 }?.toString(),
        )
    }

    fun songs(arr: JSONArray?): List<VideoItem> = arr.objects().filter { it.optLong("id") > 0 }.map(::song)

    /** A playlist as a folder row; [tracksLabel] formats "N tracks" (I18n, passed in to stay Android-free). */
    fun playlist(o: JSONObject, tracksLabel: (Int) -> String): VideoItem = VideoItem(
        source = SourceId.NETEASE,
        id = COLLECTION_PREFIX + o.optLong("id"),
        title = o.optString("name"),
        author = tracksLabel(o.optInt("trackCount")),
        thumbnailUrl = cover(o.optString("coverImgUrl").ifEmpty { o.optString("picUrl") }),
    )

    fun playlists(arr: JSONArray?, tracksLabel: (Int) -> String): List<VideoItem> =
        arr.objects().filter { it.optLong("id") > 0 }.map { playlist(it, tracksLabel) }

    /** Keywords from search/suggest/keyword. */
    fun suggestions(json: JSONObject?): List<String> =
        json?.optJSONObject("result")?.optJSONArray("allMatch").objects()
            .map { it.optString("keyword").trim() }.filter { it.isNotEmpty() }.distinct()

    /** Ids of a playlist's tracks in order (v6/playlist/detail → playlist.trackIds). */
    fun trackIds(playlist: JSONObject?): List<Long> =
        playlist?.optJSONArray("trackIds").objects().map { it.optLong("id") }.filter { it > 0 }

    /** Cursor → offset (null / garbage = start). */
    fun offset(cursor: String?): Int = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /**
     * Next cursor after a page fetched at [offset] that returned [got] items. Ends when the page is
     * empty, the server says there is no more ([more] = false), or [total] is reached.
     */
    fun next(offset: Int, got: Int, total: Int? = null, more: Boolean? = null): String? {
        if (got <= 0 || more == false) return null
        val n = offset + got
        if (total != null && n >= total) return null
        return n.toString()
    }

    /**
     * Cover image at thumbnail size. NetEase hands out http:// links (Android blocks cleartext; the CDN
     * serves https) and some carry their own query (watermarks), which is replaced.
     */
    fun cover(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val base = u.substringBefore('?')
        return (if (base.startsWith("http://")) "https://" + base.removePrefix("http://") else base) + "?param=300y300"
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
