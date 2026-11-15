package app.pitstop.video.bilibili

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * The signed-in user's favourites (收藏夹) as folder rows, and the ids those rows carry
 * (pure; unit-tested). Created folders come from `fav/folder/created/list-all`, collected ones
 * (other people's folders and 合集) from `fav/folder/collected/list`.
 */
object BiliFavs {
    private const val FAV = COLLECTION_PREFIX + "fav:"
    private const val SEASON = COLLECTION_PREFIX + "season:"

    sealed interface Ref {
        data class Folder(val mediaId: Long) : Ref
        data class Season(val mid: Long, val seasonId: Long) : Ref
    }

    fun folderId(mediaId: Long) = "$FAV$mediaId"
    fun seasonId(mid: Long, seasonId: Long) = "$SEASON$mid:$seasonId"

    fun parse(id: String): Ref? = when {
        id.startsWith(FAV) -> id.removePrefix(FAV).toLongOrNull()?.let(Ref::Folder)
        id.startsWith(SEASON) -> id.removePrefix(SEASON).split(':').takeIf { it.size == 2 }?.let { (m, s) ->
            val mid = m.toLongOrNull() ?: return null
            s.toLongOrNull()?.let { Ref.Season(mid, it) }
        }
        else -> null
    }

    /** The user's own folders (`data` of created/list-all); [count] labels the row with its size. */
    fun created(data: JSONObject?, source: SourceId, count: (Int) -> String): List<VideoItem> =
        data?.optJSONArray("list").objects().mapNotNull { o ->
            val id = o.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            VideoItem(
                source = source,
                id = folderId(id),
                title = BiliText.clean(o.optString("title")),
                author = count(o.optInt("media_count")),
                thumbnailUrl = BiliText.thumb(o.optString("cover").ifEmpty { null }),
            )
        }

    /** Folders and 合集 the user collected (`data` of collected/list). Type 11 = folder, 21 = 合集. */
    fun collected(data: JSONObject?, source: SourceId, count: (Int) -> String): List<VideoItem> =
        data?.optJSONArray("list").objects().mapNotNull { o ->
            val id = o.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val upper = o.optJSONObject("upper")
            val rowId = when (o.optInt("type")) {
                11 -> folderId(id)
                21 -> upper?.optLong("mid")?.takeIf { it > 0 }?.let { seasonId(it, id) } ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            val n = count(o.optInt("media_count"))
            VideoItem(
                source = source,
                id = rowId,
                title = BiliText.clean(o.optString("title")),
                author = upper?.optString("name")?.ifEmpty { null }?.let { "$it · $n" } ?: n,
                thumbnailUrl = BiliText.thumb(o.optString("cover").ifEmpty { null }),
            )
        }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
