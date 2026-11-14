package app.pitstop.video.gdrive

import app.pitstop.video.BuildConfig
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
    val picker: DrivePicker,
) : VideoSource {
    override val id = SourceId.GDRIVE
    override val displayName get() = I18n.s.googleDrive

    private val prefs = context.getSharedPreferences("gdrive_files", Context.MODE_PRIVATE)
    private val _picked = MutableStateFlow(readPicked())
    val picked: StateFlow<List<VideoItem>> = _picked

    /** Whole-Drive access (restricted drive.readonly scope): personal builds only, once signed in. */
    private val apiSignedIn get() = BuildConfig.DRIVE_FULL_ACCESS && account.signedIn.value

    override fun feeds(): List<Feed> = buildList {
        if (apiSignedIn) { add(Feed(FEED_ALL, I18n.s.allMusic)); add(Feed(FEED_FOLDERS, I18n.s.folders)) }
        if (picker.files.value.isNotEmpty()) add(Feed(FEED_GPICKER, I18n.s.driveGooglePicked))
        if (!apiSignedIn || _picked.value.isNotEmpty()) add(Feed(FEED_PICKED, I18n.s.drivePicked))
    }

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        FEED_PICKED -> _picked.value
        FEED_GPICKER -> picker.files.value.map { f ->
            if (f.isFolder) gpFolder(f) else gpItem(f)
        }
        // Top level of My Drive only; deeper folders are reached by opening their parent.
        FEED_FOLDERS -> folders("'root' in parents")
        else -> audio(null)
    }

    override suspend fun search(query: String): List<VideoItem> {
        val local = (_picked.value + picker.files.value.filter { !it.isFolder }.map(::gpItem))
            .filter { it.title.contains(query, ignoreCase = true) }
        if (!apiSignedIn) return local
        return audio("name contains '${query.replace("\\", "\\\\").replace("'", "\\'")}'") + local
    }

    override fun isFolder(item: VideoItem) = item.id.startsWith(COLLECTION_PREFIX)

    override suspend fun browse(item: VideoItem): List<VideoItem> {
        val folder = item.id.removePrefix(COLLECTION_PREFIX)
        if (folder.startsWith(GP)) {
            val (dirs, songs) = picker.children(folder.removePrefix(GP)).partition { it.isFolder }
            return dirs.map(::gpFolder) + songs.map(::gpItem)
        }
        // One request for subfolders + songs; folders listed first.
        val q = "'$folder' in parents and trashed = false and (mimeType = '$FOLDER_MIME' or mimeType contains 'audio/')"
        val (dirs, songs) = files(q, "folder,name", cache = true).partition { it.optString("mimeType") == FOLDER_MIME }
        return dirs.map(::folderItem) + songs.map(::audioItem)
    }

    private suspend fun folders(where: String) =
        files("mimeType = '$FOLDER_MIME' and trashed = false and $where", "name").map(::folderItem)

    private fun folderItem(o: JSONObject) =
        VideoItem(SourceId.GDRIVE, COLLECTION_PREFIX + o.getString("id"), o.optString("name"), author = I18n.s.folder)

    private fun audioItem(o: JSONObject) = VideoItem(
        source = SourceId.GDRIVE,
        id = o.getString("id"),
        title = o.optString("name").substringBeforeLast('.'),
        author = o.optString("name").substringAfterLast('.', "").uppercase().ifEmpty { null },
        thumbnailUrl = o.optString("thumbnailLink").ifEmpty { null },
    )

    /** A folder added to a playlist plays the audio files directly inside it, in name order. */
    override suspend fun expand(item: VideoItem): List<VideoItem>? {
        if (!item.id.startsWith(COLLECTION_PREFIX)) return null
        val folder = item.id.removePrefix(COLLECTION_PREFIX)
        if (folder.startsWith(GP)) {
            return picker.children(folder.removePrefix(GP)).filter { !it.isFolder }.map(::gpItem).ifEmpty { throw UserFacingException(I18n.s.drivePickerFolderEmpty) }
        }
        return audio("'$folder' in parents", order = "name").ifEmpty { throw UserFacingException(I18n.s.noVideos) }
    }

    override suspend fun resolve(item: VideoItem) =
        if (item.id.startsWith("content://")) PlayableStream(item.id)
        else if (item.id.startsWith(GP)) {
            val id = item.id.removePrefix(GP)
            PlayableStream(picker.mediaUrl(id), headers = mapOf("Authorization" to "Bearer ${picker.token()}"))
        } else PlayableStream(
            "https://www.googleapis.com/drive/v3/files/${item.id}?alt=media&acknowledgeAbuse=true",
            headers = mapOf("Authorization" to "Bearer ${token()}"),
        )

    // ---- Signed-in: Drive API ----

    private suspend fun audio(extra: String?, order: String = "modifiedTime desc"): List<VideoItem> =
        files(listOfNotNull("mimeType contains 'audio/'", "trashed = false", extra).joinToString(" and "), order).map(::audioItem)

    /** Recent listings, so going back into a folder is instant. Key: query + order. */
    private val listCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<JSONObject>>>()

    /** Up to [MAX_ITEMS] matches, following Drive's page tokens. Folder contents are cached for [CACHE_MS]. */
    private suspend fun files(q: String, orderBy: String, cache: Boolean = false): List<JSONObject> {
        if (!cache) return fetchFiles(q, orderBy)
        val key = "$q|$orderBy"
        val now = android.os.SystemClock.elapsedRealtime()
        listCache[key]?.let { (at, list) -> if (now - at < CACHE_MS) return list }
        return fetchFiles(q, orderBy).also { listCache[key] = now to it }
    }

    private suspend fun fetchFiles(q: String, orderBy: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var pageToken: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("orderBy", orderBy)
                .addQueryParameter("pageSize", (MAX_ITEMS - out.size).coerceAtMost(PAGE_SIZE).toString())
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,thumbnailLink)")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = JSONObject(http.getString(Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build()))
            val arr = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null && out.size < MAX_ITEMS)
        return out
    }

    private suspend fun token(): String {
        if (!apiSignedIn) throw UserFacingException(I18n.s.needDriveLogin)
        return account.token()
    }

    private fun gpFolder(f: PickedDriveFile) = VideoItem(SourceId.GDRIVE, COLLECTION_PREFIX + GP + f.id, f.name, author = I18n.s.folder)

    private fun gpItem(f: PickedDriveFile) = VideoItem(
        source = SourceId.GDRIVE,
        id = GP + f.id,
        title = f.name.substringBeforeLast('.'),
        author = f.name.substringAfterLast('.', "").uppercase().ifEmpty { null },
    )

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
        const val FEED_GPICKER = "gpicker"
        /** Most files listed for one feed / folder / search (Drive pages hold up to 1000). */
        const val MAX_ITEMS = 1000
        private const val PAGE_SIZE = 1000
        private const val CACHE_MS = 5 * 60_000L
        /** Id prefix for files shared through Google's Drive picker (drive.file scope). */
        private const val GP = "gp:"
        private const val KEY_PICKED = "files"
    }
}
