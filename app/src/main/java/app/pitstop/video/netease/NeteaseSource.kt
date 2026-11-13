package app.pitstop.video.netease

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Feed
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource

/** NetEase Cloud Music: charts and playlists for everyone; daily picks and own playlists when signed in. */
class NeteaseSource(private val api: NeteaseApi, private val cookies: NeteaseCookieJar) : VideoSource {
    override val id = SourceId.NETEASE
    override val displayName get() = I18n.s.netease

    override fun feeds(): List<Feed> = buildList {
        if (cookies.signedIn.value) {
            add(Feed(FEED_DAILY, I18n.s.dailySongs))
            add(Feed(FEED_MINE, I18n.s.myPlaylists))
        }
        add(Feed(FEED_HOT, I18n.s.musicHot))
        add(Feed(FEED_NEW, I18n.s.musicNew))
        add(Feed(FEED_RISING, I18n.s.musicRising))
        add(Feed(FEED_PLAYLISTS, I18n.s.musicPlaylists))
    }

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        FEED_DAILY -> api.dailySongs()
        FEED_MINE -> api.myPlaylists()
        FEED_NEW -> api.playlistTracks(NeteaseApi.TOPLIST_NEW)
        FEED_RISING -> api.playlistTracks(NeteaseApi.TOPLIST_RISING)
        FEED_PLAYLISTS -> api.recommendedPlaylists()
        else -> api.playlistTracks(NeteaseApi.TOPLIST_HOT)
    }

    override suspend fun search(query: String) = api.search(query)

    /** A playlist row plays its tracks. */
    override suspend fun expand(item: VideoItem): List<VideoItem>? =
        item.id.removePrefix(COLLECTION_PREFIX).takeIf { item.id.startsWith(COLLECTION_PREFIX) }
            ?.toLongOrNull()?.let { api.playlistTracks(it) }

    override suspend fun resolve(item: VideoItem) = PlayableStream(api.songUrl(item.id.toLong()))

    companion object {
        const val FEED_DAILY = "daily"
        const val FEED_MINE = "mine"
        const val FEED_HOT = "hot"
        const val FEED_NEW = "new"
        const val FEED_RISING = "rising"
        const val FEED_PLAYLISTS = "playlists"
    }
}
