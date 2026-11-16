package app.pitstop.video.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Loads thumbnails for both remote items (HTTP) and local items (MediaStore), shared by phone + car UI. */
class ThumbnailLoader(private val context: Context, private val http: OkHttpClient) {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val gate = Semaphore(4)

    fun cached(item: VideoItem): Bitmap? = cache.get(key(item))

    suspend fun load(item: VideoItem, width: Int = 320): Bitmap? {
        val key = key(item)
        cache.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (item.source == SourceId.LOCAL_MUSIC) {
                        // Album art embedded in / attached to the song.
                        if (Build.VERSION.SDK_INT >= 29) {
                            context.contentResolver.loadThumbnail(Uri.parse(item.id), Size(width, width), null)
                        } else {
                            MediaMetadataRetriever().run {
                                try {
                                    setDataSource(context, Uri.parse(item.id))
                                    embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                                } finally {
                                    release()
                                }
                            }
                        }
                    } else if (item.source == SourceId.LOCAL) {
                        if (Build.VERSION.SDK_INT >= 29) {
                            context.contentResolver.loadThumbnail(Uri.parse(item.id), Size(width, width * 9 / 16), null)
                        } else {
                            MediaMetadataRetriever().run {
                                try {
                                    setDataSource(context, Uri.parse(item.id))
                                    getFrameAtTime(1_000_000)?.let { f ->
                                        Bitmap.createScaledBitmap(f, width, f.height * width / f.width.coerceAtLeast(1), true)
                                    }
                                } finally {
                                    release()
                                }
                            }
                        }
                    } else {
                        val original = item.thumbnailUrl ?: return@runCatching null
                        // Data saver: on mobile data ask the image server for a picture near the size shown.
                        val url = if (StreamPolicy.metered) ThumbUrls.smaller(original, width) else original
                        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                            val bytes = resp.body?.bytes() ?: return@use null
                            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                            if (raw.width > width * 2) {
                                Bitmap.createScaledBitmap(raw, width, raw.height * width / raw.width, true)
                            } else raw
                        }
                    }
                }.getOrNull()?.also { cache.put(key, it) }
            }
        }
    }

    private fun key(item: VideoItem) = item.thumbnailUrl ?: item.id
}
