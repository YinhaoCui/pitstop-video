package app.pitstop.video.youtube

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Feed
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
import org.schabi.newpipe.extractor.playlist.PlaylistInfo

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

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        FEED_LIKED -> account.likedMusic()
        FEED_PLAYLISTS -> account.myPlaylists(SourceId.YT_MUSIC)
        else -> runCatching { chart() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: songs(if (I18n.s.music == "音乐") "热门歌曲" else "top hits")
    }

    /** YouTube Music's public "Top 100 Songs Global" chart playlist. */
    private suspend fun chart(): List<VideoItem> = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/playlist?list=$GLOBAL_CHART")
        info.relatedItems.filterIsInstance<StreamInfoItem>().map { s ->
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
    }

    override suspend fun search(query: String) = songs(query)

    override val supportsCreators get() = true

    override suspend fun creatorVideos(authorId: String) = YouTubeSource.channelVideos(authorId, SourceId.YT_MUSIC)

    override suspend fun expand(item: VideoItem): List<VideoItem>? =
        if (item.id.startsWith(COLLECTION_PREFIX)) account.playlistItems(item.id.removePrefix(COLLECTION_PREFIX), SourceId.YT_MUSIC) else null

    private suspend fun songs(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        extractor.fetchPage()
        extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map { s ->
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
