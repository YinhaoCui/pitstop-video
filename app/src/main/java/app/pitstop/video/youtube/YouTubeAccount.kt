package app.pitstop.video.youtube

import app.pitstop.video.core.I18n
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.GoogleScopedAccount
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.getString
import app.pitstop.video.core.parseIsoDuration
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Optional Google sign-in (scope youtube.readonly) for personal feeds via the YouTube Data API v3.
 * Requires an Android OAuth client for this package + signing SHA-1 in Google Cloud (see README).
 */
class YouTubeAccount(context: Context, private val http: OkHttpClient) :
    GoogleScopedAccount(context, "youtube", SCOPE, { I18n.s.finishYoutubeSignIn }) {

    /**
     * One Data API call. On an auth error (401, or 403 insufficient scope) the cached token is thrown
     * away and the call is retried once with a fresh one; other errors show Google's own reason.
     */
    private suspend fun api(pathAndQuery: String, token: String): JSONObject {
        val first = call(pathAndQuery, token)
        if (first.ok) return first.json!!
        if (first.authError) {
            invalidateToken(token)
            val again = call(pathAndQuery, token())
            if (again.ok) return again.json!!
            if (again.authError) { signOut(); throw UserFacingException(I18n.s.youtubeSignInAgain) }
            throw again.exception()
        }
        throw first.exception()
    }

    private class ApiResult(val code: Int, val json: JSONObject?, val reason: String, val message: String) {
        val ok get() = code in 200..299
        val authError get() = code == 401 || reason in AUTH_REASONS
        fun exception(): Exception = when (reason) {
            "quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded" -> UserFacingException(I18n.s.youtubeQuota)
            "accessNotConfigured", "SERVICE_DISABLED" -> UserFacingException(I18n.s.youtubeApiDisabled)
            else -> java.io.IOException("YouTube HTTP $code: ${message.ifEmpty { reason }}")
        }
    }

    private suspend fun call(pathAndQuery: String, token: String): ApiResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.googleapis.com/youtube/v3/$pathAndQuery")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return@use ApiResult(resp.code, JSONObject(body), "", "")
            Log.w(TAG, "HTTP ${resp.code} for ${pathAndQuery.substringBefore('?')}: ${body.take(600)}")
            val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
            val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                .ifEmpty { err?.optJSONArray("details")?.optJSONObject(0)?.optString("reason").orEmpty() }
            ApiResult(resp.code, null, reason, err?.optString("message").orEmpty())
        }
    }

    /** `&pageToken=...` for a Data API cursor (cursors are the API's own page tokens). */
    private fun pageParam(pageToken: String?) =
        pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""

    /** Latest uploads from the user's subscribed channels. */
    suspend fun subscriptionFeed(): List<VideoItem> = subscriptionPage(null).items

    /**
     * Latest uploads from 25 subscribed channels per page (newest first within the page); the next
     * page covers the next 25 subscriptions. ~26 quota units per page, as before.
     */
    suspend fun subscriptionPage(pageToken: String?): FeedPage = coroutineScope {
        val token = token()
        val page = api("subscriptions?part=snippet&mine=true&maxResults=25&order=relevance" + pageParam(pageToken), token)
        val subs = page.optJSONArray("items") ?: return@coroutineScope FeedPage(emptyList())
        val channelIds = (0 until subs.length()).mapNotNull {
            subs.getJSONObject(it).optJSONObject("snippet")?.optJSONObject("resourceId")?.optString("channelId")
        }.filter { it.startsWith("UC") }

        val items = channelIds.map { ch ->
            async {
                runCatching {
                    val uploads = "UU" + ch.substring(2)
                    val items = api("playlistItems?part=snippet,contentDetails&maxResults=4&playlistId=$uploads", token)
                        .optJSONArray("items")
                    (0 until (items?.length() ?: 0)).mapNotNull { i ->
                        val o = items!!.getJSONObject(i)
                        val sn = o.optJSONObject("snippet") ?: return@mapNotNull null
                        val cd = o.optJSONObject("contentDetails") ?: return@mapNotNull null
                        val vid = cd.optString("videoId").ifEmpty { return@mapNotNull null }
                        cd.optString("videoPublishedAt") to VideoItem(
                            source = SourceId.YOUTUBE,
                            id = YouTubeSource.watchUrl(vid),
                            title = sn.optString("title"),
                            author = sn.optString("videoOwnerChannelTitle").ifEmpty { sn.optString("channelTitle") },
                            authorId = YouTubeSource.channelUrl(ch),
                            thumbnailUrl = thumb(sn),
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().sortedByDescending { it.first }.map { it.second }.take(60)
        FeedPage(items, page.optString("nextPageToken").ifEmpty { null })
    }

    suspend fun likedVideos(): List<VideoItem> = likedPage(null).items

    /** Liked videos, 50 per page (1 quota unit each). */
    suspend fun likedPage(pageToken: String?): FeedPage {
        val j = api("videos?part=snippet,contentDetails&myRating=like&maxResults=50" + pageParam(pageToken), token())
        val items = j.optJSONArray("items")
        val list = (0 until (items?.length() ?: 0)).map { i ->
            val o = items!!.getJSONObject(i)
            val sn = o.getJSONObject("snippet")
            VideoItem(
                source = SourceId.YOUTUBE,
                id = YouTubeSource.watchUrl(o.getString("id")),
                title = sn.optString("title"),
                author = sn.optString("channelTitle"),
                thumbnailUrl = thumb(sn),
                durationSec = parseIsoDuration(o.optJSONObject("contentDetails")?.optString("duration")),
                authorId = sn.optString("channelId").ifEmpty { null }?.let(YouTubeSource::channelUrl),
            )
        }
        return FeedPage(list, j.optString("nextPageToken").ifEmpty { null })
    }

    /** Liked videos in YouTube's Music category (what YouTube Music shows as liked songs). */
    suspend fun likedMusic(): List<VideoItem> = likedMusicPage(null).items

    /**
     * Liked music, endlessly. Most liked videos may be non-music, so one page reads up to 4 API pages
     * (50 each) until it has at least 10 songs, so the UI doesn't see a run of empty pages.
     */
    suspend fun likedMusicPage(pageToken: String?): FeedPage {
        val token = token()
        val out = mutableListOf<VideoItem>()
        var next = pageToken
        var reads = 0
        do {
            val j = api("videos?part=snippet,contentDetails&myRating=like&maxResults=50" + pageParam(next), token)
            val items = j.optJSONArray("items")
            out += (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it) }
                .filter { it.optJSONObject("snippet")?.optString("categoryId") == "10" }
                .map { o ->
                    val sn = o.getJSONObject("snippet")
                    VideoItem(
                        source = SourceId.YT_MUSIC,
                        id = YouTubeSource.watchUrl(o.getString("id")),
                        title = sn.optString("title"),
                        author = sn.optString("channelTitle").removeSuffix(" - Topic"),
                        thumbnailUrl = thumb(sn),
                        durationSec = parseIsoDuration(o.optJSONObject("contentDetails")?.optString("duration")),
                    )
                }
            next = j.optString("nextPageToken").ifEmpty { null }
            reads++
        } while (next != null && out.size < 10 && reads < 4)
        return FeedPage(out, next)
    }

    /** The user's own playlists (including ones made in YouTube Music), as collection rows. */
    suspend fun myPlaylists(source: SourceId): List<VideoItem> = playlistsPage(source, null).items

    suspend fun playlistsPage(source: SourceId, pageToken: String?): FeedPage {
        val j = api("playlists?part=snippet,contentDetails&mine=true&maxResults=50" + pageParam(pageToken), token())
        val items = j.optJSONArray("items")
        val list = (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it) }.map { o ->
            val sn = o.getJSONObject("snippet")
            VideoItem(
                source = source,
                id = COLLECTION_PREFIX + o.getString("id"),
                title = sn.optString("title"),
                author = I18n.s.tracks(o.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0),
                thumbnailUrl = thumb(sn),
            )
        }
        return FeedPage(list, j.optString("nextPageToken").ifEmpty { null })
    }

    suspend fun playlistItems(playlistId: String, source: SourceId): List<VideoItem> {
        val token = token()
        val out = mutableListOf<VideoItem>()
        var page: String? = null
        do {
            val j = api("playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlistId" +
                (page?.let { "&pageToken=$it" } ?: ""), token)
            val items = j.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val o = items!!.getJSONObject(i)
                val sn = o.optJSONObject("snippet") ?: continue
                val vid = o.optJSONObject("contentDetails")?.optString("videoId").orEmpty().ifEmpty { continue }
                out += VideoItem(
                    source = source,
                    id = YouTubeSource.watchUrl(vid),
                    title = sn.optString("title"),
                    author = sn.optString("videoOwnerChannelTitle").removeSuffix(" - Topic").ifEmpty { null },
                    thumbnailUrl = thumb(sn),
                )
            }
            page = j.optString("nextPageToken").ifEmpty { null }
        } while (page != null && out.size < 200)
        return out
    }

    private fun thumb(snippet: JSONObject): String? {
        val t = snippet.optJSONObject("thumbnails") ?: return null
        return (t.optJSONObject("medium") ?: t.optJSONObject("high") ?: t.optJSONObject("default"))?.optString("url")
    }

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        private const val TAG = "YouTubeAccount"
        private val AUTH_REASONS = setOf(
            "insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "authError",
        )
    }
}
