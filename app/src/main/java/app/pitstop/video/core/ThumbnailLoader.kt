package app.pitstop.video.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
                    if (item.source == SourceId.LOCAL) {
                        context.contentResolver.loadThumbnail(Uri.parse(item.id), Size(width, width * 9 / 16), null)
                    } else {
                        val url = item.thumbnailUrl ?: return@runCatching null
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
