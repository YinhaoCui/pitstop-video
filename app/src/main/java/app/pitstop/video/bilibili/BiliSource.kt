package app.pitstop.video.bilibili

import app.pitstop.video.core.I18n
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource

class BiliSource(private val api: BiliApi, private val cookies: BiliCookieJar) : VideoSource {
    override val id = SourceId.BILIBILI
    override val displayName get() = I18n.s.bilibili

    override fun feeds(): List<Feed> = buildList {
        add(Feed(FEED_RECOMMENDED, I18n.s.recommended))
        add(Feed(FEED_POPULAR, I18n.s.popular))
        if (cookies.loggedIn.value) add(Feed(FEED_FOLLOWING, I18n.s.following))
    }

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        FEED_POPULAR -> api.popular()
        FEED_FOLLOWING -> api.following().items
        else -> api.recommended()
    }

    /** Following scrolls endlessly; the cursor is Bilibili's offset plus our page number ("offset|page"). */
    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        if (feed.key != FEED_FOLLOWING) return super.loadPage(feed, cursor)
        val (offset, page) = cursor?.split('|', limit = 2)?.let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 2) } ?: (null to 1)
        val p = api.following(offset, page)
        return p.copy(next = p.next?.let { "$it|${page + 1}" })
    }

    override suspend fun search(query: String) = api.search(query)

    override val supportsCreators get() = true

    override suspend fun creatorVideos(authorId: String) = api.uploaderVideos(authorId, SourceId.BILIBILI)

    /** 分P: a video with several parts becomes one queue entry per part. */
    override suspend fun expand(item: VideoItem): List<VideoItem>? {
        if (item.part != null) return null
        val v = api.view(item.id)
        if (v.pages.size <= 1) return null
        return v.pages.map { p ->
            VideoItem(
                source = SourceId.BILIBILI,
                id = item.id,
                title = BiliSubtitles.partTitle(v.title.ifEmpty { item.title }, p.page, p.name),
                author = item.author ?: v.author,
                thumbnailUrl = item.thumbnailUrl ?: v.cover,
                durationSec = p.durationSec.takeIf { it > 0 },
                extra = p.cid.toString(),
                part = p.page,
            )
        }
    }

    override suspend fun resolve(item: VideoItem): PlayableStream {
        val cid = item.extra?.toLongOrNull() ?: api.cidOf(item.id)
        return api.playUrl(item.id, cid)
    }

    companion object {
        const val FEED_RECOMMENDED = "recommended"
        const val FEED_POPULAR = "popular"
        const val FEED_FOLLOWING = "following"
    }
}
