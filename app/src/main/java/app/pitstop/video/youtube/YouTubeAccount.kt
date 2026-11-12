package app.pitstop.video.youtube

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.getString
import app.pitstop.video.core.parseIsoDuration
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
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
class YouTubeAccount(private val context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
    private val _signedIn = MutableStateFlow(prefs.getBoolean(KEY_SIGNED_IN, false))
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val client get() = Identity.getAuthorizationClient(context)
    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Returns a PendingIntent to show the consent UI, or null when access was granted silently. */
    suspend fun beginSignIn(): PendingIntent? {
        val result = client.authorize(request()).await()
        if (result.hasResolution()) return result.pendingIntent
        setSignedIn(result.accessToken != null)
        return null
    }

    fun completeSignIn(data: Intent?) {
        val result = client.getAuthorizationResultFromIntent(data)
        setSignedIn(result.accessToken != null)
    }

    fun signOut() = setSignedIn(false)

    private fun setSignedIn(value: Boolean) {
        prefs.edit().putBoolean(KEY_SIGNED_IN, value).apply()
        _signedIn.value = value
    }

    private suspend fun token(): String {
        val result = client.authorize(request()).await()
        if (result.hasResolution()) throw UserFacingException("Open Pitstop Video on the phone to finish YouTube sign-in")
        return result.accessToken ?: throw UserFacingException("YouTube sign-in failed")
    }

    private suspend fun api(pathAndQuery: String, token: String): JSONObject {
        val req = Request.Builder()
            .url("https://www.googleapis.com/youtube/v3/$pathAndQuery")
            .header("Authorization", "Bearer $token")
            .build()
        return JSONObject(http.getString(req))
    }

    /** Latest uploads from the user's subscribed channels. */
    suspend fun subscriptionFeed(): List<VideoItem> = coroutineScope {
        val token = token()
        val subs = api("subscriptions?part=snippet&mine=true&maxResults=25&order=relevance", token)
            .optJSONArray("items") ?: return@coroutineScope emptyList()
        val channelIds = (0 until subs.length()).mapNotNull {
            subs.getJSONObject(it).optJSONObject("snippet")?.optJSONObject("resourceId")?.optString("channelId")
        }.filter { it.startsWith("UC") }

        channelIds.map { ch ->
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
                            thumbnailUrl = thumb(sn),
                        )
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().sortedByDescending { it.first }.map { it.second }.take(60)
    }

    suspend fun likedVideos(): List<VideoItem> {
        val items = api("videos?part=snippet,contentDetails&myRating=like&maxResults=50", token()).optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            val sn = o.getJSONObject("snippet")
            VideoItem(
                source = SourceId.YOUTUBE,
                id = YouTubeSource.watchUrl(o.getString("id")),
                title = sn.optString("title"),
                author = sn.optString("channelTitle"),
                thumbnailUrl = thumb(sn),
                durationSec = parseIsoDuration(o.optJSONObject("contentDetails")?.optString("duration")),
            )
        }
    }

    private fun thumb(snippet: JSONObject): String? {
        val t = snippet.optJSONObject("thumbnails") ?: return null
        return (t.optJSONObject("medium") ?: t.optJSONObject("high") ?: t.optJSONObject("default"))?.optString("url")
    }

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        private const val KEY_SIGNED_IN = "signed_in"
    }
}
