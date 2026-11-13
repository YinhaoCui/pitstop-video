package app.pitstop.video.gdrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Feed
import app.pitstop.video.core.GoogleScopedAccount
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.getString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Read-only Google Drive access (separate consent from YouTube). */
class DriveAccount(context: Context) :
    GoogleScopedAccount(context, "gdrive", "https://www.googleapis.com/auth/drive.readonly", { I18n.s.finishDriveSignIn })

/**
 * Music in the user's Google Drive.
 * - Signed in: everything / folder by folder, streamed straight from the Drive API with the user's token.
 * - Without sign-in: songs picked with the system file picker (Drive shows up there); the Drive app
 *   serves those files, and we keep a persistable read grant for each one.
 */
class DriveSource(
    private val context: Context,
    private val account: DriveAccount,
    private val http: OkHttpClient,
) : VideoSource {
    override val id = SourceId.GDRIVE
    override val displayName get() = I18n.s.googleDrive

    private val prefs = context.getSharedPreferences("gdrive_files", Context.MODE_PRIVATE)
    private val _picked = MutableStateFlow(readPicked())
    val picked: StateFlow<List<VideoItem>> = _picked

    override fun feeds(): List<Feed> = buildList {
        if (account.signedIn.value) { add(Feed(FEED_ALL, I18n.s.allMusic)); add(Feed(FEED_FOLDERS, I18n.s.folders)) }
        if (!account.signedIn.value || _picked.value.isNotEmpty()) add(Feed(FEED_PICKED, I18n.s.drivePicked))
    }

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        FEED_PICKED -> _picked.value
        FEED_FOLDERS -> files("mimeType = 'application/vnd.google-apps.folder' and trashed = false", "name").map { o ->
            VideoItem(SourceId.GDRIVE, COLLECTION_PREFIX + o.getString("id"), o.optString("name"), author = I18n.s.folder)
        }
        else -> audio(null)
    }

    override suspend fun search(query: String): List<VideoItem> {
        val local = _picked.value.filter { it.title.contains(query, ignoreCase = true) }
        if (!account.signedIn.value) return local
        return audio("name contains '${query.replace("\\", "\\\\").replace("'", "\\'")}'") + local
    }

    /** A folder row plays the audio files directly inside it, in name order. */
    override suspend fun expand(item: VideoItem): List<VideoItem>? {
        if (!item.id.startsWith(COLLECTION_PREFIX)) return null
        val folder = item.id.removePrefix(COLLECTION_PREFIX)
        return audio("'$folder' in parents", order = "name").ifEmpty { throw UserFacingException(I18n.s.noVideos) }
    }

    override suspend fun resolve(item: VideoItem) =
        if (item.id.startsWith("content://")) PlayableStream(item.id)
        else PlayableStream(
            "https://www.googleapis.com/drive/v3/files/${item.id}?alt=media&acknowledgeAbuse=true",
            headers = mapOf("Authorization" to "Bearer ${token()}"),
        )

    // ---- Signed-in: Drive API ----

    private suspend fun audio(extra: String?, order: String = "modifiedTime desc"): List<VideoItem> =
        files(listOfNotNull("mimeType contains 'audio/'", "trashed = false", extra).joinToString(" and "), order).map { o ->
            VideoItem(
                source = SourceId.GDRIVE,
                id = o.getString("id"),
                title = o.optString("name").substringBeforeLast('.'),
                author = o.optString("name").substringAfterLast('.', "").uppercase().ifEmpty { null },
                thumbnailUrl = o.optString("thumbnailLink").ifEmpty { null },
            )
        }

    private suspend fun files(q: String, orderBy: String): List<JSONObject> {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("orderBy", orderBy)
            .addQueryParameter("pageSize", "200")
            .addQueryParameter("fields", "files(id,name,mimeType,thumbnailLink)")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("includeItemsFromAllDrives", "true")
            .build()
        val json = JSONObject(http.getString(Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build()))
        val arr = json.optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private suspend fun token(): String {
        if (!account.signedIn.value) throw UserFacingException(I18n.s.needDriveLogin)
        return account.token()
    }

    // ---- Without sign-in: files picked in the system picker ----

    /** Adds files returned by the picker; newest picks go first, duplicates move to the top. */
    fun addPicked(uris: List<Uri>) {
        val added = uris.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            pickedItem(uri.toString(), name)
        }
        savePicked(added + _picked.value.filter { old -> added.none { it.id == old.id } })
    }

    fun clearPicked() {
        _picked.value.forEach { item ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(item.id), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        savePicked(emptyList())
    }

    private fun pickedItem(uri: String, name: String) = VideoItem(
        source = SourceId.GDRIVE,
        id = uri,
        title = name.substringBeforeLast('.'),
        author = name.substringAfterLast('.', "").uppercase().ifEmpty { null },
        extra = name,
    )

    private fun savePicked(list: List<VideoItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.id).put("name", it.extra.orEmpty())) }
        prefs.edit().putString(KEY_PICKED, arr.toString()).apply()
        _picked.value = list
    }

    private fun readPicked(): List<VideoItem> {
        val arr = runCatching { JSONArray(prefs.getString(KEY_PICKED, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { pickedItem(it.getString("uri"), it.optString("name")) }
    }

    companion object {
        const val FEED_ALL = "all"
        const val FEED_FOLDERS = "folders"
        const val FEED_PICKED = "picked"
        private const val KEY_PICKED = "files"
    }
}
