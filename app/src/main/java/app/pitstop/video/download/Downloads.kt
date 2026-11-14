package app.pitstop.video.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.pitstop.video.BuildConfig
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoQuality
import app.pitstop.video.core.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** A folder chosen for downloads: a phone folder's tree URI or a Drive folder id, and its name. */
data class DownloadFolder(val id: String, val name: String)

/** One download: where it goes ([toPhone] / [toDrive]) and how far it got. */
data class DownloadTask(
    val id: Long,
    val item: VideoItem,
    val quality: VideoQuality,
    val toPhone: Boolean,
    val toDrive: Boolean,
    val stage: Stage = Stage.QUEUED,
    /** 0..1 within the stage; negative while the size is unknown. */
    val progress: Float = -1f,
    val error: String? = null,
    /** The saved file on the phone (content:// or file://). */
    val savedUri: String? = null,
    val fileName: String? = null,
) {
    enum class Stage { QUEUED, PREPARING, DOWNLOADING, MERGING, SAVING, UPLOADING, DONE, FAILED, CANCELLED }
    val finished get() = stage == Stage.DONE || stage == Stage.FAILED || stage == Stage.CANCELLED
}

/**
 * Downloads videos and songs, one at a time: resolve the stream (quality chosen for the download,
 * AVC + AAC), fetch it in ranged chunks, merge split video/audio into one MP4 without re-encoding,
 * then save it to Download/Pitstop on the phone and/or upload it to Google Drive.
 * A foreground service ([DownloadService]) keeps the app alive while anything is pending.
 */
class Downloads(
    private val context: Context,
    private val http: OkHttpClient,
    private val source: (SourceId) -> VideoSource,
    val drive: DriveUploader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()
    private val queue = Channel<Long>(Channel.UNLIMITED)
    @Volatile private var running: Pair<Long, Job>? = null
    private var nextId = System.currentTimeMillis()
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    /** The phone folder the user picked (system folder picker); null = Download/Pitstop. */
    private val _phoneFolder = MutableStateFlow(
        prefs.getString(KEY_TREE, null)?.let { DownloadFolder(it, prefs.getString(KEY_TREE_NAME, null) ?: it) }
    )
    val phoneFolder: StateFlow<DownloadFolder?> = _phoneFolder.asStateFlow()

    /** Keeps write access to a folder from the system picker ([tree] null = back to Download/Pitstop). */
    fun setPhoneFolder(tree: Uri?) {
        val resolver = context.contentResolver
        _phoneFolder.value?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old.id), RW) }
        }
        val folder = tree?.let {
            resolver.takePersistableUriPermission(it, RW)
            DownloadFolder(it.toString(), folderName(it))
        }
        prefs.edit().putString(KEY_TREE, folder?.id).putString(KEY_TREE_NAME, folder?.name).apply()
        _phoneFolder.value = folder
    }

    /** "Music/Car" for a tree on the phone's storage, else the folder's own name. */
    private fun folderName(tree: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        if (docId.startsWith("primary:")) return docId.removePrefix("primary:").ifEmpty { "/" }
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return runCatching {
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: docId.substringAfter(':')
    }

    init {
        scope.launch {
            for (id in queue) {
                val task = _tasks.value.firstOrNull { it.id == id } ?: continue
                if (task.stage != DownloadTask.Stage.QUEUED) continue
                val job = scope.launch { run(task) }
                running = id to job
                job.join()
                running = null
            }
        }
    }

    /**
     * True when [item] can be downloaded at all (not live, not a whole playlist / folder). YouTube and
     * YouTube Music are streaming-only unless the build opts in ([BuildConfig.YOUTUBE_DOWNLOADS]).
     */
    fun canDownload(item: VideoItem): Boolean =
        !item.source.live && item.source != SourceId.RADIO && !item.id.startsWith(COLLECTION_PREFIX) &&
            (BuildConfig.YOUTUBE_DOWNLOADS || item.source !in STREAM_ONLY) &&
            !source(item.source).isFolder(item)

    fun enqueue(item: VideoItem, quality: VideoQuality, toPhone: Boolean, toDrive: Boolean) {
        if (!canDownload(item)) return
        val task = DownloadTask(nextId++, item, quality, toPhone, toDrive)
        _tasks.update { it + task }
        queue.trySend(task.id)
        DownloadService.start(context)
    }

    fun cancel(id: Long) {
        running?.takeIf { it.first == id }?.second?.cancel()
        set(id) { if (it.finished) it else it.copy(stage = DownloadTask.Stage.CANCELLED) }
    }

    fun retry(id: Long) {
        set(id) { it.copy(stage = DownloadTask.Stage.QUEUED, progress = -1f, error = null) }
        queue.trySend(id)
        DownloadService.start(context)
    }

    fun clearFinished() = _tasks.update { list -> list.filter { !it.finished } }

    val busy: Boolean get() = _tasks.value.any { !it.finished }

    private fun set(id: Long, change: (DownloadTask) -> DownloadTask) =
        _tasks.update { list -> list.map { if (it.id == id) change(it) else it } }

    private fun stage(id: Long, stage: DownloadTask.Stage, progress: Float = -1f) =
        set(id) { it.copy(stage = stage, progress = progress) }

    private suspend fun run(task: DownloadTask) {
        val id = task.id
        val dir = File(context.cacheDir, "downloads/$id").apply { deleteRecursively(); mkdirs() }
        try {
            stage(id, DownloadTask.Stage.PREPARING)
            val src = source(task.item.source)
            val stream = withContext(StreamPolicy.forDownload(task.quality)) { src.resolve(task.item) }
            if (stream.live || stream.kind != PlayableStream.Kind.PROGRESSIVE) throw UserFacingException(I18n.s.cantDownloadStream)
            val urls = listOfNotNull(stream.videoUri, stream.audioUri)

            // Sizes first, so one progress bar covers both parts of a split video.
            val sizes = urls.map { size(it, stream.headers) }
            val total = sizes.takeIf { s -> s.all { it != null } }?.sumOf { it!! }
            var done = 0L
            stage(id, DownloadTask.Stage.DOWNLOADING, if (total != null) 0f else -1f)
            val parts = urls.mapIndexed { i, url ->
                fetch(url, stream.headers, File(dir, "part$i")) { n ->
                    done += n
                    if (total != null) set(id) { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }

            stage(id, DownloadTask.Stage.MERGING)
            val base = DownloadNames.baseName(task.item.title, task.item.author)
            val files = parts.map { it.first }
            val probe = Remuxer.probe(files)
            val out: File
            val ext: String
            if (probe.container != null) {
                ext = if (probe.hasVideo == true) probe.container.ext else probe.container.audioExt
                out = File(dir, "$base.$ext")
                Remuxer.mux(files, out, probe.container) { p -> set(id) { it.copy(progress = p) } }
            } else if (files.size == 1) {
                val type = parts[0].second
                ext = DownloadNames.extensionFor(type) ?: DownloadNames.extensionFromUrl(urls[0])
                    ?: if (probe.hasVideo == true) "mp4" else if (task.item.source.music || stream.audioOnly) "m4a" else "mp4"
                out = File(dir, "$base.$ext")
                if (!files[0].renameTo(out)) files[0].copyTo(out, overwrite = true)
            } else {
                throw UserFacingException(I18n.s.cantMerge)
            }
            val mime = DownloadNames.mimeFor(ext)
            set(id) { it.copy(fileName = out.name) }

            if (task.toPhone) {
                stage(id, DownloadTask.Stage.SAVING)
                val uri = saveToPhone(out, mime)
                set(id) { it.copy(savedUri = uri.toString()) }
            }
            if (task.toDrive) {
                stage(id, DownloadTask.Stage.UPLOADING, 0f)
                drive.upload(out, out.name, mime) { p -> set(id) { it.copy(progress = p) } }
            }
            stage(id, DownloadTask.Stage.DONE, 1f)
        } catch (e: CancellationException) {
            set(id) { it.copy(stage = DownloadTask.Stage.CANCELLED) }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "download ${task.item.id} failed", e)
            set(id) { it.copy(stage = DownloadTask.Stage.FAILED, error = e.message ?: e.javaClass.simpleName) }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Total size from a 1-byte range request; null when the server doesn't say. */
    private suspend fun size(url: String, headers: Map<String, String>): Long? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext null
        runCatching {
            http.newCall(request(url, headers, "bytes=0-0")).execute().use { r ->
                when {
                    r.code == 206 -> DownloadNames.totalFromContentRange(r.header("Content-Range"))
                    r.isSuccessful -> r.body?.contentLength()?.takeIf { it > 0 }
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun request(url: String, headers: Map<String, String>, range: String) = Request.Builder().url(url)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .header("Range", range)
        .build()

    /**
     * Downloads [url] into [dest] in ranged chunks (YouTube slows down one long request), retrying a
     * failed chunk a few times. Returns the file and its Content-Type.
     */
    private suspend fun fetch(url: String, headers: Map<String, String>, dest: File, onBytes: (Long) -> Unit): Pair<File, String?> =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("http")) {
                val uri = Uri.parse(url)
                val input = if (uri.scheme == "file") File(uri.path!!).inputStream() else context.contentResolver.openInputStream(uri)
                input?.use { copy(it, dest, append = false, onBytes) } ?: throw IOException("Can't open $url")
                return@withContext dest to context.contentResolver.getType(uri)
            }
            var pos = 0L
            var total: Long? = null
            var type: String? = null
            dest.delete()
            while (total == null || pos < total) {
                var attempt = 0
                var got = 0L
                var ranged = false
                while (true) {
                    coroutineContext.ensureActive()
                    try {
                        http.newCall(request(url, headers, "bytes=$pos-${pos + CHUNK - 1}")).execute().use { r ->
                            if (r.code == 416 && pos > 0) { got = 0; ranged = true; return@use }
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                            type = type ?: r.header("Content-Type")
                            ranged = r.code == 206
                            if (ranged) total = DownloadNames.totalFromContentRange(r.header("Content-Range")) ?: total
                            else if (pos > 0) throw IOException("Server stopped answering ranges")
                            got = copy(r.body!!.byteStream(), dest, append = true, onBytes)
                        }
                        break
                    } catch (e: IOException) {
                        if (++attempt >= 4) throw e
                        // Drop the partial chunk and try it again.
                        if (dest.length() > pos) java.io.RandomAccessFile(dest, "rw").use { it.setLength(pos) }
                        kotlinx.coroutines.delay(1500L * attempt)
                    }
                }
                pos += got
                if (!ranged) break                         // whole file in one response
                if (got == 0L) break                       // past the end
                if (total == null && got < CHUNK) break    // unknown size: a short chunk was the last one
            }
            dest to type
        }

    private suspend fun copy(input: InputStream, dest: File, append: Boolean, onBytes: (Long) -> Unit): Long {
        var n = 0L
        java.io.FileOutputStream(dest, append).use { out ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                n += r
                onBytes(r.toLong())
            }
        }
        return n
    }

    /** Download/Pitstop on Android 10+ (MediaStore, no permission needed); the app's own folder before that. */
    private fun saveToPhone(file: File, mime: String): Uri {
        val resolver = context.contentResolver
        _phoneFolder.value?.let { folder ->
            val tree = Uri.parse(folder.id)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = try {
                DocumentsContract.createDocument(resolver, parent, mime, file.name)
            } catch (e: Exception) {
                // Folder deleted or access revoked: ask for it again rather than saving somewhere unexpected.
                setPhoneFolder(null)
                throw UserFacingException(I18n.s.downloadFolderLost(folder.name))
            } ?: throw IOException("Couldn't create the file in ${folder.name}")
            try {
                resolver.openOutputStream(doc)!!.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, doc) }
                throw e
            }
            return doc
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pitstop")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Couldn't create the file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = File(ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_DOWNLOADS).first(), "Pitstop").apply { mkdirs() }
        var target = File(dir, file.name)
        var n = 1
        while (target.exists()) target = File(dir, "${file.nameWithoutExtension} (${n++}).${file.extension}")
        file.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
        return Uri.fromFile(target)
    }

    private companion object {
        /** Sources that are never saved to the device in public builds (streaming terms). */
        val STREAM_ONLY = setOf(SourceId.YOUTUBE, SourceId.YT_MUSIC)
        const val TAG = "Downloads"
        const val CHUNK = 8L * 1024 * 1024
        const val KEY_TREE = "phone_tree"
        const val KEY_TREE_NAME = "phone_tree_name"
        const val RW = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
