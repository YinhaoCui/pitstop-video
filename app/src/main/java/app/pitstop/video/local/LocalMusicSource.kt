package app.pitstop.video.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import app.pitstop.video.core.Feed
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sources that need a runtime permission before they can list anything (phone storage). */
interface PermissionSource {
    val requiredPermissions: Array<String>
    fun hasPermission(): Boolean
}

/**
 * Songs stored on the phone (MediaStore audio), e.g. downloaded from Google Drive, a browser or a
 * music app. "Downloads" shows only the Download folder. Works offline.
 */
class LocalMusicSource(private val context: Context) : VideoSource, PermissionSource {
    override val id = SourceId.LOCAL_MUSIC
    override val displayName get() = I18n.s.musicOnDevice

    override fun feeds() = listOf(Feed(FEED_ALL, I18n.s.allSongs), Feed(FEED_DOWNLOADS, I18n.s.downloads))

    override val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    override fun hasPermission(): Boolean =
        requiredPermissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun load(feed: Feed) = query(null, downloadsOnly = feed.key == FEED_DOWNLOADS)

    override suspend fun search(query: String) = query(query, downloadsOnly = false)

    override suspend fun resolve(item: VideoItem) = PlayableStream(item.id)

    @Suppress("DEPRECATION") // DATA is the only folder hint before Android 10.
    private suspend fun query(nameFilter: String?, downloadsOnly: Boolean): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw UserFacingException(I18n.s.allowMusicHint)
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val folderCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!downloadsOnly) where += "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        if (downloadsOnly) { where += "$folderCol LIKE ?"; args += if (Build.VERSION.SDK_INT >= 29) "Download/%" else "%/Download/%" }
        nameFilter?.let {
            where += "(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)"
            args += "%$it%"; args += "%$it%"
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID,
        )
        val out = mutableListOf<VideoItem>()
        context.contentResolver.query(
            collection, projection, where.joinToString(" AND ").ifEmpty { null }, args.toTypedArray().ifEmpty { null },
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                out += VideoItem(
                    source = SourceId.LOCAL_MUSIC,
                    id = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString(),
                    title = c.getString(titleCol).orEmpty(),
                    author = c.getString(artistCol)?.takeIf { it != MediaStore.UNKNOWN_STRING },
                    durationSec = c.getLong(durCol).takeIf { it > 0 }?.div(1000),
                )
            }
        }
        out
    }

    companion object {
        const val FEED_ALL = "all"
        const val FEED_DOWNLOADS = "downloads"
    }
}
