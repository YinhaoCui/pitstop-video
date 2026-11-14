package app.pitstop.video.live

import android.content.Context
import app.pitstop.video.core.Category
import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.categoryKey
import app.pitstop.video.core.getString
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom

/**
 * Douyu (斗鱼) live rooms. Lists popular rooms and every category's rooms, searches rooms (endlessly),
 * and plays the web player's FLV stream.
 * The play URL is signed in pure Kotlin from the site's getEncryption endpoint (see [DouyuParse]); no JS engine.
 */
class DouyuSource(private val context: Context, private val http: OkHttpClient) : VideoSource {
    override val id = SourceId.DOUYU
    override val displayName get() = I18n.s.douyu

    /** Device id Douyu ties the signature to; any 32 hex chars work, kept for the app's lifetime. */
    private val did: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    override fun feeds() = listOf(Feed(FEED_POPULAR, I18n.s.popular))

    override suspend fun load(feed: Feed): List<VideoItem> = loadPage(feed, null).items

    override suspend fun categories(): List<Category> =
        DouyuParse.parseCategories(get("$MOBILE/api/cate/list", "$MOBILE/"), I18n.s::douyuAllOf)

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        val page = cursor?.toIntOrNull() ?: 1
        feed.categoryKey()?.let { key ->
            if (!DouyuParse.isCategoryKey(key)) return FeedPage(emptyList())
            val json = get(BASE + DouyuParse.directoryPath(key, page), "$BASE/")
            val rooms = DouyuParse.parseListPage(json)
            val next = DouyuParse.nextPage(page, rooms.size, DouyuParse.pageCount(json), MAX_PAGES)
            return FeedPage(rooms.map(::toItem), next?.toString())
        }
        val rooms = DouyuParse.parseListPage(get("$BASE/japi/weblist/apinc/allpage/6/$page", "$BASE/"))
        return FeedPage(rooms.map(::toItem), next = if (rooms.isEmpty() || page >= MAX_PAGES) null else (page + 1).toString())
    }

    override suspend fun search(query: String): List<VideoItem> = searchPage(query, null).items

    /** Live rooms matching [query], [SEARCH_PAGE] per page (cursor = page number from 1). */
    override suspend fun searchPage(query: String, cursor: String?): FeedPage {
        val page = cursor?.toIntOrNull() ?: 1
        val url = "$BASE/japi/search/api/searchShow".toHttpUrl().newBuilder()
            .addQueryParameter("kw", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", SEARCH_PAGE.toString())
            .build().toString()
        val json = get(url, "$BASE/search/", cookie = "dy_did=$did; acf_did=$did")
        val rooms = DouyuParse.parseSearch(json)
        val total = DouyuParse.searchTotal(json)
        val pages = if (total > 0) (total + SEARCH_PAGE - 1) / SEARCH_PAGE else 0
        return FeedPage(rooms.map(::toItem), DouyuParse.nextPage(page, rooms.size, pages, MAX_PAGES)?.toString())
    }

    override suspend fun resolve(item: VideoItem): PlayableStream {
        val rid = item.id
        val first = try {
            play(rid, "-1")
        } catch (e: UserFacingException) {
            throw e
        } catch (e: Exception) {
            throw UserFacingException(I18n.s.liveUnavailable)
        }
        val (signed, ok) = first
        val chosen = DouyuParse.chooseRate(ok.rates, StreamPolicy.maxHeight, StreamPolicy.audioOnly)
        val final = if (chosen == null || chosen.rate == ok.rate) ok else {
            // Same signature is accepted again for another rate.
            runCatching { requestPlay(rid, signed, chosen.rate.toString()) }.getOrNull()
                ?.let { DouyuParse.parsePlay(it) as? DouyuParse.PlayResult.Ok } ?: ok
        }
        return PlayableStream(
            videoUri = final.url,
            headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "$BASE/$rid"),
            kind = PlayableStream.Kind.PROGRESSIVE,
            live = true,
            audioOnly = StreamPolicy.audioOnly,
        )
    }

    private suspend fun play(rid: String, rate: String): Pair<DouyuSignParams, DouyuParse.PlayResult.Ok> {
        val tt = (System.currentTimeMillis() / 1000).toString()
        val enc = get("$BASE/wgapi/livenc/liveweb/websec/getEncryption?did=$did", "$BASE/")
        val signed = DouyuParse.signParams(enc, rid, did, tt)
        return when (val r = DouyuParse.parsePlay(requestPlay(rid, signed, rate))) {
            is DouyuParse.PlayResult.Ok -> signed to r
            DouyuParse.PlayResult.Offline -> throw UserFacingException(I18n.s.streamOffline)
            is DouyuParse.PlayResult.Failed -> throw UserFacingException(I18n.s.liveUnavailable)
        }
    }

    private suspend fun requestPlay(rid: String, p: DouyuSignParams, rate: String): String {
        val body = FormBody.Builder()
            .add("enc_data", p.encData)
            .add("tt", p.tt)
            .add("did", p.did)
            .add("auth", p.auth)
            .add("cdn", "")
            .add("rate", rate)
            .add("hevc", "0") // H.264 only: plays on every head unit
            .add("fa", "0")
            .add("ive", "0")
            .add("iar", "0")
            .build()
        val req = Request.Builder().url("$BASE/lapi/live/getH5PlayV1/$rid").post(body)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "$BASE/$rid")
            .build()
        return http.getString(req)
    }

    private suspend fun get(url: String, referer: String, cookie: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).header("Referer", referer)
        if (cookie != null) b.header("Cookie", cookie)
        return http.getString(b.build())
    }

    private fun toItem(r: DouyuRoom) = VideoItem(
        source = SourceId.DOUYU,
        id = r.rid,
        title = r.title.ifBlank { r.streamer },
        author = listOfNotNull(r.streamer.ifBlank { null }, r.viewers?.let { I18n.s.viewers(it) }).joinToString(" · ").ifBlank { null },
        thumbnailUrl = r.cover,
        durationSec = null,
        authorId = r.rid,
    )

    companion object {
        const val FEED_POPULAR = "popular"
        private const val BASE = "https://www.douyu.com"
        private const val MOBILE = "https://m.douyu.com"
        private const val SEARCH_PAGE = 20
        /** The all-rooms list ends with an empty page well before this; a guard against endless loops. */
        private const val MAX_PAGES = 150
    }
}
