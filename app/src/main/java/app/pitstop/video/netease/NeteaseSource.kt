package app.pitstop.video.netease

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Category
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.categoryKey

/**
 * NetEase Cloud Music: charts, playlist categories and search for everyone; daily picks and own
 * playlists when signed in. Playlists are folder rows; artists are creators.
 */
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

    override suspend fun load(feed: Feed): List<VideoItem> = loadPage(feed, null).items

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        feed.categoryKey()?.let { key ->
            return when (val ref = NeteaseParse.categoryRef(key)) {
                is NeteaseParse.Chart -> api.playlistTracksPage(ref.id, cursor)
                is NeteaseParse.Tag -> api.tagPlaylists(ref.name, cursor)
                null -> FeedPage(emptyList())
            }
        }
        return when (feed.key) {
            FEED_DAILY -> if (cursor == null) FeedPage(api.dailySongs()) else FeedPage(emptyList())
            FEED_MINE -> api.myPlaylistsPage(cursor)
            FEED_NEW -> api.playlistTracksPage(NeteaseApi.TOPLIST_NEW, cursor)
            FEED_RISING -> api.playlistTracksPage(NeteaseApi.TOPLIST_RISING, cursor)
            // Personalised picks first, then every hot playlist, endlessly.
            FEED_PLAYLISTS -> if (cursor == null) FeedPage(api.recommendedPlaylists(), "0")
            else api.tagPlaylists(NeteaseParse.ALL, cursor)
            else -> api.playlistTracksPage(NeteaseApi.TOPLIST_HOT, cursor)
        }
    }

    override suspend fun categories(): List<Category> = api.categories()

    override suspend fun search(query: String) = api.search(query)

    /** Songs, endlessly; the first page starts with the best matching playlists (folders). */
    override suspend fun searchPage(query: String, cursor: String?): FeedPage {
        val songs = api.searchSongs(query, cursor)
        if (cursor != null) return songs
        val playlists = runCatching { api.searchPlaylists(query) }.getOrDefault(emptyList())
        return FeedPage(playlists + songs.items, songs.next)
    }

    override suspend fun suggestions(query: String): List<String> =
        if (query.isBlank()) emptyList() else api.suggestions(query.trim())

    override fun isFolder(item: VideoItem) = item.id.startsWith(COLLECTION_PREFIX)

    /** A playlist's tracks. */
    override suspend fun browse(item: VideoItem): List<VideoItem> =
        playlistId(item)?.let { api.playlistTracks(it) } ?: emptyList()

    /** A playlist row in the queue plays its tracks. */
    override suspend fun expand(item: VideoItem): List<VideoItem>? = playlistId(item)?.let { api.playlistTracks(it) }

    private fun playlistId(item: VideoItem) =
        item.id.takeIf { it.startsWith(COLLECTION_PREFIX) }?.removePrefix(COLLECTION_PREFIX)?.toLongOrNull()

    /** Songs carry their (first) artist's id. */
    override val supportsCreators get() = true

    override suspend fun creatorVideos(authorId: String): List<VideoItem> = creatorPage(authorId, null).items

    override suspend fun creatorPage(authorId: String, cursor: String?): FeedPage =
        authorId.toLongOrNull()?.let { api.artistSongs(it, cursor) } ?: FeedPage(emptyList())

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
