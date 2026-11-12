package app.pitstop.video.bilibili

import app.pitstop.video.core.Feed
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource

class BiliSource(private val api: BiliApi, private val cookies: BiliCookieJar) : VideoSource {
    override val id = SourceId.BILIBILI
    override val displayName = "Bilibili"

    override fun feeds(): List<Feed> = buildList {
        add(Feed(FEED_RECOMMENDED, "Recommended"))
        add(Feed(FEED_POPULAR, "Popular"))
        if (cookies.loggedIn.value) add(Feed(FEED_FOLLOWING, "Following"))
    }

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        FEED_POPULAR -> api.popular()
        FEED_FOLLOWING -> api.following()
        else -> api.recommended()
    }

    override suspend fun search(query: String) = api.search(query)

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
