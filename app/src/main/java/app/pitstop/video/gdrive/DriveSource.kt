package app.pitstop.video.gdrive

import app.pitstop.video.BuildConfig
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.pitstop.video.core.AudioFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.pitstop.video.core.PlaylistStore
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Feed
import app.pitstop.video.core.GoogleScopedAccount
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PickedFiles
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
    private val _trees = MutableStateFlow(readTrees())
    /** Folders added with the system folder picker ("Add a folder"). */
    val trees: StateFlow<List<VideoItem>> = _trees

    /** Whole-Drive access (restricted drive.readonly scope): personal builds only, once signed in. */
    private val apiSignedIn get() = BuildConfig.DRIVE_FULL_ACCESS && account.signedIn.value

    override fun feeds(): List<Feed> = buildList {
        if (apiSignedIn) { add(Feed(FEED_ALL, I18n.s.allMusic)); add(Feed(FEED_FOLDERS, I18n.s.folders)) }
        if (_trees.value.isNotEmpty()) add(Feed(FEED_TREES, I18n.s.driveFolders))
        if (picker.files.value.isNotEmpty()) add(Feed(FEED_GPICKER, I18n.s.driveGooglePicked))
        if (!apiSignedIn || _picked.value.isNotEmpty()) add(Feed(FEED_PICKED, I18n.s.drivePicked))
    }

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        // Songs whose file grant Android dropped (over its 512-file limit) say so instead of failing to play.
        FEED_PICKED -> withoutAccess().toSet().let { lost ->
            _picked.value.map { if (it.id in lost) it.copy(author = I18n.s.fileNoAccess) else it }
        }
        FEED_TREES -> _trees.value
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
        PickedFiles.parseTreeFolder(item.id)?.let { (tree, doc) ->
            val (dirs, songs) = treeChildren(tree, doc)
            return dirs + songs
        }
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
        PickedFiles.parseTreeFolder(item.id)?.let { (tree, doc) ->
            return treeChildren(tree, doc).second.ifEmpty { throw UserFacingException(I18n.s.nothingHere) }
        }
        val folder = item.id.removePrefix(COLLECTION_PREFIX)
        if (folder.startsWith(GP)) {
            return picker.children(folder.removePrefix(GP)).filter { !it.isFolder }.map(::gpItem).ifEmpty { throw UserFacingException(I18n.s.drivePickerFolderEmpty) }
        }
        return audio("'$folder' in parents", order = "name").ifEmpty { throw UserFacingException(I18n.s.nothingHere) }
    }

    override suspend fun resolve(item: VideoItem) =
        if (item.id.startsWith("content://")) {
            // A song added with "Add from files" whose grant Android dropped: play the same file from an
            // added folder instead (the queue and playlists keep pointing at the old copy).
            val use = if (hasAccess(item.id)) item.id else replacement(item)?.id ?: item.id
            PlayableStream(use)
        } else if (item.id.startsWith(GP)) {
            val id = item.id.removePrefix(GP)
            PlayableStream(picker.mediaUrl(id), headers = mapOf("Authorization" to "Bearer ${picker.token()}"))
        } else PlayableStream(
            // supportsAllDrives: listings include shared drives, whose files answer 404 without it.
            "https://www.googleapis.com/drive/v3/files/${item.id}?alt=media&acknowledgeAbuse=true&supportsAllDrives=true",
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

    /** Picked files Android no longer lets the app read (see [PickedFiles]). */
    fun withoutAccess(): List<String> =
        PickedFiles.withoutAccess(_picked.value.filter { it.id.startsWith("content://") }.map { it.id }, granted())

    private fun granted(): Set<String> = runCatching {
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() }
    }.getOrDefault(emptySet())

    private fun hasAccess(uri: String) = PickedFiles.hasAccess(uri, granted())

    // ---- Songs from added folders, by file name (to stand in for picked songs that lost access) ----

    private val indexLock = Mutex()
    @Volatile private var folderIndex: Pair<Long, Map<String, VideoItem>>? = null

    /** Every song in the added folders and their subfolders, by [PickedFiles.matchKey]; cached 10 minutes. */
    private suspend fun folderSongs(): Map<String, VideoItem> = indexLock.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        folderIndex?.let { (at, map) -> if (now - at < 10 * 60_000L) return@withLock map }
        val out = HashMap<String, VideoItem>()
        val pending = ArrayDeque(_trees.value.mapNotNull { PickedFiles.parseTreeFolder(it.id) })
        var folders = 0
        // At most 20 s: this runs while a song waits to play. A scan cut short isn't cached.
        val complete = withTimeoutOrNull(20_000) {
            while (pending.isNotEmpty() && folders++ < 300) {
                val (tree, doc) = pending.removeFirst()
                val (dirs, songs) = try {
                    treeChildren(tree, doc)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
                songs.forEach { s -> out.putIfAbsent(PickedFiles.matchKey(s.extra ?: s.title), s) }
                dirs.forEach { d -> PickedFiles.parseTreeFolder(d.id)?.let(pending::addLast) }
            }
            true
        } == true
        if (complete) folderIndex = now to out
        out
    }

    private fun keyOf(item: VideoItem) = PickedFiles.matchKey(PickedFiles.fileName(item.title, item.author, item.extra))

    /** The same song (same file name) in one of the added folders, if any. */
    suspend fun replacement(item: VideoItem): VideoItem? =
        if (_trees.value.isEmpty()) null else folderSongs()[keyOf(item)]

    /**
     * Points playlists at the folder copies of songs that lost access, and drops those songs from the
     * picked list (the folder has them). Returns how many playlist entries were fixed.
     */
    suspend fun repairWithFolders(playlists: PlaylistStore): Int {
        val songs = folderSongs()
        if (songs.isEmpty()) return 0
        val granted = granted()
        fun fix(item: VideoItem): VideoItem? =
            if (item.source == SourceId.GDRIVE && item.id.startsWith("content://") && !PickedFiles.hasAccess(item.id, granted)) songs[keyOf(item)]
            else null
        val fixed = playlists.replaceItems(::fix)
        val keep = _picked.value.filter { fix(it) == null }
        if (keep.size != _picked.value.size) savePicked(keep)
        return fixed
    }

    /** Drops the picked songs that can't be read any more, so they can be added again (or picked from Google Drive). */
    fun removeWithoutAccess() {
        val lost = withoutAccess().toSet()
        if (lost.isNotEmpty()) savePicked(_picked.value.filterNot { it.id in lost })
    }

    /**
     * Adds files returned by the picker; newest picks go first, duplicates move to the top.
     * Returns how many picked songs can't be read afterwards (Android keeps at most 512 file grants).
     */
    fun addPicked(uris: List<Uri>): Int {
        val added = uris.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            pickedItem(uri.toString(), name)
        }
        savePicked(added + _picked.value.filter { old -> added.none { it.id == old.id } })
        // Android drops the oldest grants past 512: take the folder grants again so they stay the newest.
        _trees.value.forEach { t ->
            PickedFiles.parseTreeFolder(t.id)?.let { (tree, _) ->
                runCatching { context.contentResolver.takePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        return withoutAccess().size
    }

    /** A folder chosen with the system folder picker: one read grant covers all its files and subfolders. */
    suspend fun addTree(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val doc = DocumentsContract.getTreeDocumentId(uri)
        val name = runCatching {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(uri, doc), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: I18n.s.folder
        val item = VideoItem(SourceId.GDRIVE, PickedFiles.treeFolderId(uri.toString(), doc), name, author = I18n.s.folder)
        saveTrees(listOf(item) + _trees.value.filter { it.id != item.id })
        folderIndex = null
    }

    /** Subfolders and songs directly inside a tree folder, each sorted by name. */
    private suspend fun treeChildren(tree: String, doc: String): Pair<List<VideoItem>, List<VideoItem>> = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, doc)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val dirs = mutableListOf<VideoItem>()
        val songs = mutableListOf<VideoItem>()
        // The Drive app answers from its cache first and flags "still loading"; ask again until it's done.
        for (attempt in 0 until 20) {
            dirs.clear(); songs.clear()
            val loading = context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                            dirs += VideoItem(SourceId.GDRIVE, PickedFiles.treeFolderId(tree, id), name, author = I18n.s.folder)
                        AudioFiles.isAudio(name, mime) ->
                            songs += pickedItem(DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(), name)
                    }
                }
                c.extras?.getBoolean(DocumentsContract.EXTRA_LOADING) == true
            } ?: throw UserFacingException(I18n.s.fileAccessLost)
            if (!loading) break
            delay(750)
        }
        dirs.sortedBy { it.title.lowercase() } to songs.sortedBy { it.title.lowercase() }
    }

    private fun saveTrees(list: List<VideoItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.title)) }
        prefs.edit().putString(KEY_TREES, arr.toString()).apply()
        _trees.value = list
    }

    private fun readTrees(): List<VideoItem> {
        val arr = runCatching { JSONArray(prefs.getString(KEY_TREES, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { VideoItem(SourceId.GDRIVE, it.getString("id"), it.optString("name"), author = I18n.s.folder) }
    }

    fun clearPicked() {
        _trees.value.forEach { item ->
            PickedFiles.parseTreeFolder(item.id)?.let { (tree, _) ->
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        saveTrees(emptyList())
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
        const val FEED_TREES = "trees"
        private const val KEY_TREES = "trees"
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
