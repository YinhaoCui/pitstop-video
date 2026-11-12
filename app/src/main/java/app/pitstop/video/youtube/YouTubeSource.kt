package app.pitstop.video.youtube

import app.pitstop.video.core.Feed
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
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * YouTube without an API key: listing/search/stream URLs come from NewPipeExtractor.
 * When signed in, [YouTubeAccount] adds personal feeds through the official Data API.
 */
class YouTubeSource(private val account: YouTubeAccount) : VideoSource {
    override val id = SourceId.YOUTUBE
    override val displayName = "YouTube"

    override fun feeds(): List<Feed> = buildList {
        add(Feed(FEED_POPULAR, "Popular"))
        if (account.signedIn.value) {
            add(Feed(FEED_SUBSCRIPTIONS, "Subscriptions"))
            add(Feed(FEED_LIKED, "Liked videos"))
        }
    }

    override suspend fun load(feed: Feed): List<VideoItem> = when (feed.key) {
        FEED_SUBSCRIPTIONS -> account.subscriptionFeed()
        FEED_LIKED -> account.likedVideos()
        else -> withContext(Dispatchers.IO) {
            val kiosk = ServiceList.YouTube.kioskList.defaultKioskExtractor
            kiosk.fetchPage()
            kiosk.initialPage.items.filterIsInstance<StreamInfoItem>().map(::toItem)
        }
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""
        )
        extractor.fetchPage()
        extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map(::toItem)
    }

    override suspend fun resolve(item: VideoItem): PlayableStream = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.YouTube, item.id)
        val headers = mapOf("User-Agent" to NewPipeDownloader.USER_AGENT)

        if ((info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) &&
            info.hlsUrl.isNotEmpty()
        ) {
            return@withContext PlayableStream(info.hlsUrl, headers = headers, kind = PlayableStream.Kind.HLS)
        }

        val videoOnly = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { StreamSelector.VideoTrack(it.content, it.height, it.codec.orEmpty(), it.bitrate.toLong()) }
        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { StreamSelector.AudioTrack(it.content, it.averageBitrate.toLong(), it.codec.orEmpty()) }

        val v = StreamSelector.pickVideo(videoOnly)
        val a = StreamSelector.pickAudio(audio)
        if (v != null && a != null) return@withContext PlayableStream(v.url, a.url, headers)

        val muxed = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { StreamSelector.VideoTrack(it.content, it.height, it.codec.orEmpty()) }
        StreamSelector.pickVideo(muxed)?.let { return@withContext PlayableStream(it.url, headers = headers) }

        if (info.hlsUrl.isNotEmpty()) return@withContext PlayableStream(info.hlsUrl, headers = headers, kind = PlayableStream.Kind.HLS)
        throw UserFacingException("No playable stream found for this YouTube video")
    }

    private fun toItem(s: StreamInfoItem) = VideoItem(
        source = SourceId.YOUTUBE,
        id = s.url,
        title = s.name,
        author = s.uploaderName,
        thumbnailUrl = bestThumb(s.thumbnails),
        durationSec = s.duration.takeIf { it > 0 },
    )

    private fun bestThumb(images: List<Image>): String? =
        images.sortedBy { it.width }.firstOrNull { it.width >= 320 }?.url ?: images.lastOrNull()?.url

    companion object {
        const val FEED_POPULAR = "popular"
        const val FEED_SUBSCRIPTIONS = "subscriptions"
        const val FEED_LIKED = "liked"
        fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
    }
}
