package app.pitstop.video.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import app.pitstop.video.core.Feed
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Videos stored on the phone (MediaStore). Works fully offline. */
class LocalSource(private val context: Context) : VideoSource {
    override val id = SourceId.LOCAL
    override val displayName = "On this device"

    override fun feeds() = listOf(Feed(FEED_ALL, "All videos"))

    /** Android 13+ has a video-only permission (plus "selected videos" on 14+); older versions use storage. */
    val requiredPermissions: Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasPermission(): Boolean =
        requiredPermissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun load(feed: Feed): List<VideoItem> = query(null)

    override suspend fun search(query: String): List<VideoItem> = query(query)

    override suspend fun resolve(item: VideoItem) = PlayableStream(item.id)

    private suspend fun query(nameFilter: String?): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw UserFacingException("Allow video access in Pitstop Video (On this device tab)")
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val selection = nameFilter?.let { "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?" }
        val args = nameFilter?.let { arrayOf("%$it%") }
        val out = mutableListOf<VideoItem>()
        context.contentResolver.query(
            collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                out += VideoItem(
                    source = SourceId.LOCAL,
                    id = uri.toString(),
                    title = c.getString(nameCol).orEmpty(),
                    author = c.getString(folderCol),
                    durationSec = c.getLong(durCol).takeIf { it > 0 }?.div(1000),
                )
            }
        }
        out
    }

    companion object {
        const val FEED_ALL = "all"
    }
}
