package app.pitstop.video.youtube

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Category
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.categoryKey
import app.pitstop.video.newpipe.CategoryKeys
import app.pitstop.video.newpipe.NewPipePager
import org.schabi.newpipe.extractor.InfoItem
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.player.StreamSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * YouTube Music: song search via NewPipeExtractor (no key), audio-only playback. With the shared
 * Google sign-in ([YouTubeAccount]) it adds liked music and the user's playlists.
 */
class YouTubeMusicSource(private val account: YouTubeAccount) : VideoSource {
    override val id = SourceId.YT_MUSIC
    override val displayName get() = I18n.s.ytMusic

    override fun feeds(): List<Feed> = buildList {
        if (account.signedIn.value) {
            add(Feed(FEED_LIKED, I18n.s.likedMusic))
            add(Feed(FEED_PLAYLISTS, I18n.s.myPlaylists))
        }
        add(Feed(FEED_TOP, I18n.s.topSongs))
    }

    override suspend fun load(feed: Feed): List<VideoItem> = loadPage(feed, null).items

    /** NewPipe's next-page handles aren't strings: the pager keeps them, cursors are keys into it. */
    private val pager = NewPipePager()

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        // Account feeds: the cursor is YouTube's own continuation token.
        when (feed.key) {
            FEED_LIKED -> return account.likedMusicPage(cursor)
            FEED_PLAYLISTS -> return account.playlistsPage(SourceId.YT_MUSIC, cursor)
        }
        val genre = feed.categoryKey()
        return withContext(Dispatchers.IO) {
            when {
                cursor != null -> pager.resume(cursor)
                genre != null -> songsFirst(I18n.s.musicGenreQuery(genre))
                // Top songs: the global chart playlist, then endless "top hits" song search.
                else -> {
                    val topHits = { songsFirst(I18n.s.musicGenreQuery("pop")) }
                    runCatching {
                        pager.pages(ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=$GLOBAL_CHART"), null, ::toSong, topHits)
                    }.getOrElse { topHits() }
                }
            }
        }
    }

    /** Genres and moods: each is an endless song search in the app language. */
    override suspend fun categories(): List<Category> {
        val s = I18n.s
        return CategoryKeys.named(CategoryKeys.MUSIC_GENRES, s::musicGenre, s.catGenres) +
            CategoryKeys.named(CategoryKeys.MUSIC_MOODS, s::musicGenre, s.catMoods)
    }

    override suspend fun search(query: String) = searchPage(query, null).items

    override suspend fun searchPage(query: String, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        if (cursor != null) pager.resume(cursor) else songsFirst(query)
    }

    /** NewPipe has no YouTube Music suggestion extractor; YouTube's completions work well for songs. */
    override suspend fun suggestions(query: String): List<String> = YouTubeSource.youtubeSuggestions(query)

    override val supportsCreators get() = true

    override suspend fun creatorVideos(authorId: String) = creatorPage(authorId, null).items

    override suspend fun creatorPage(authorId: String, cursor: String?): FeedPage =
        YouTubeSource.channelPage(pager, authorId, SourceId.YT_MUSIC, cursor)

    override suspend fun expand(item: VideoItem): List<VideoItem>? =
        if (item.id.startsWith(COLLECTION_PREFIX)) account.playlistItems(item.id.removePrefix(COLLECTION_PREFIX), SourceId.YT_MUSIC) else null

    /** First page of a YouTube Music song search; the cursor follows its pages. Blocking. */
    private fun songsFirst(query: String): FeedPage = pager.pages(
        ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""), null, ::toSong,
    )

    private fun toSong(i: InfoItem): VideoItem? = (i as? StreamInfoItem)?.let { s ->
        VideoItem(
            source = SourceId.YT_MUSIC,
            id = canonical(s.url),
            title = s.name,
            author = s.uploaderName?.removeSuffix(" - Topic"),
            authorId = s.uploaderUrl?.ifEmpty { null },
            thumbnailUrl = bestThumb(s.thumbnails),
            durationSec = s.duration.takeIf { it > 0 },
        )
    }

    override suspend fun resolve(item: VideoItem): PlayableStream = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, item.id)
        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            // averageBitrate is in kbps; StreamSelector works in bits/s.
            .map { StreamSelector.AudioTrack(it.content, it.averageBitrate.toLong() * 1000, it.codec.orEmpty()) }
        val a = StreamSelector.pickAudio(audio, app.pitstop.video.core.StreamPolicy.audioMaxBps) ?: throw UserFacingException("No audio stream for this song")
        PlayableStream(a.url, headers = mapOf("User-Agent" to NewPipeDownloader.USER_AGENT))
    }

    /** music.youtube.com links -> www.youtube.com/watch (same video id, what the stream extractor expects). */
    private fun canonical(url: String): String =
        Regex("[?&]v=([\\w-]{6,})").find(url)?.groupValues?.get(1)?.let(YouTubeSource::watchUrl) ?: url

    private fun bestThumb(images: List<Image>): String? =
        images.sortedBy { it.width }.firstOrNull { it.width >= 226 }?.url ?: images.lastOrNull()?.url

    companion object {
        const val FEED_TOP = "top"
        private const val GLOBAL_CHART = "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i"
        const val FEED_LIKED = "liked"
        const val FEED_PLAYLISTS = "playlists"
    }
}
