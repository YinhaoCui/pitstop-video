package app.pitstop.video.gdrive

import android.content.Context
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Read-only Google Drive access (separate consent from YouTube). */
class DriveAccount(context: Context) :
    GoogleScopedAccount(context, "gdrive", "https://www.googleapis.com/auth/drive.readonly", { I18n.s.finishDriveSignIn })

/** Music files in the user's Google Drive: everything, or folder by folder. Streams with the user's token. */
class DriveSource(private val account: DriveAccount, private val http: OkHttpClient) : VideoSource {
    override val id = SourceId.GDRIVE
    override val displayName get() = I18n.s.googleDrive

    override fun feeds() = listOf(Feed(FEED_ALL, I18n.s.allMusic), Feed(FEED_FOLDERS, I18n.s.folders))

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        FEED_FOLDERS -> files("mimeType = 'application/vnd.google-apps.folder' and trashed = false", "name").map { o ->
            VideoItem(SourceId.GDRIVE, COLLECTION_PREFIX + o.getString("id"), o.optString("name"), author = I18n.s.folder)
        }
        else -> audio(null)
    }

    override suspend fun search(query: String) = audio("name contains '${query.replace("\\", "\\\\").replace("'", "\\'")}'")

    /** A folder row plays the audio files directly inside it, in name order. */
    override suspend fun expand(item: VideoItem): List<VideoItem>? {
        if (!item.id.startsWith(COLLECTION_PREFIX)) return null
        val folder = item.id.removePrefix(COLLECTION_PREFIX)
        return audio("'$folder' in parents", order = "name").ifEmpty { throw UserFacingException(I18n.s.noVideos) }
    }

    override suspend fun resolve(item: VideoItem) = PlayableStream(
        "https://www.googleapis.com/drive/v3/files/${item.id}?alt=media&acknowledgeAbuse=true",
        headers = mapOf("Authorization" to "Bearer ${token()}"),
    )

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

    companion object {
        const val FEED_ALL = "all"
        const val FEED_FOLDERS = "folders"
    }
}
