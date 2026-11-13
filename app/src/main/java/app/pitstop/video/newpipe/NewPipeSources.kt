package app.pitstop.video.newpipe

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.player.DeviceCodecs
import app.pitstop.video.player.StreamSelector
import app.pitstop.video.youtube.NewPipeDownloader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * A source backed by one NewPipeExtractor service (SoundCloud, Bandcamp, PeerTube). Feeds are the
 * service's own kiosks (charts / featured lists) with endless paging; albums and playlists open as
 * folders; uploaders work as creators.
 */
abstract class NewPipeServiceSource(final override val id: SourceId) : VideoSource {
    protected abstract val service: StreamingService
    override val displayName get() = I18n.s.sourceName(id)
    override val supportsCreators get() = true

    /** Feed title for a kiosk id; unknown ids keep the service's own name. */
    protected open fun kioskTitle(kioskId: String): String = when (kioskId) {
        "Trending" -> I18n.s.popular
        "Top 50" -> I18n.s.topCharts
        "New & hot" -> I18n.s.newAndHot
        "Featured" -> I18n.s.featured
        "Radio" -> I18n.s.bandcampRadio
        "Most liked" -> I18n.s.mostLiked
        "Recently added" -> I18n.s.newReleases
        "Local" -> I18n.s.peertubeLocal
        else -> kioskId
    }

    override fun feeds(): List<Feed> = runCatching {
        val list = service.kioskList
        val default = list.defaultKioskId
        (listOf(default) + list.availableKiosks.filter { it != default }.sorted()).map { Feed(it, kioskTitle(it)) }
    }.getOrDefault(emptyList())

    override suspend fun load(feed: Feed): List<VideoItem> = loadPage(feed, null).items

    /** NewPipe's next-page handles aren't strings: kept here, the cursor is a key into this map. */
    private val pages = ConcurrentHashMap<String, Page>()

    private fun keep(next: Page?): String? = next?.let { p -> UUID.randomUUID().toString().also { pages[it] = p } }

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        val kiosk = service.kioskList.getExtractorById(feed.key, null)
        val page: ListExtractor.InfoItemsPage<out InfoItem> = if (cursor == null) {
            kiosk.fetchPage(); kiosk.initialPage
        } else kiosk.getPage(pages.remove(cursor) ?: return@withContext FeedPage(emptyList()))
        FeedPage(page.items.mapNotNull(::toItem), keep(page.nextPage))
    }

    override suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val extractor = service.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull(::toItem)
    }

    override fun isFolder(item: VideoItem) = item.id.startsWith(COLLECTION_PREFIX)

    /** An album / playlist: its tracks (up to ~300, following its pages). */
    override suspend fun browse(item: VideoItem): List<VideoItem> = withContext(Dispatchers.IO) {
        val url = item.id.removePrefix(COLLECTION_PREFIX)
        val info = PlaylistInfo.getInfo(service, url)
        val out = info.relatedItems.mapNotNull(::toItem).toMutableList()
        var next = info.nextPage
        while (next != null && out.size < 300) {
            val more = PlaylistInfo.getMoreItems(service, url, next)
            out += more.items.mapNotNull(::toItem)
            next = more.nextPage
        }
        out
    }

    override suspend fun expand(item: VideoItem): List<VideoItem>? =
        if (isFolder(item)) browse(item) else null

    override suspend fun creatorVideos(authorId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(service, authorId)
        val tab = info.tabs.firstOrNull { it.contentFilters.any { f -> f == ChannelTabs.VIDEOS || f == ChannelTabs.TRACKS || f == ChannelTabs.ALBUMS } }
            ?: info.tabs.firstOrNull() ?: return@withContext emptyList()
        ChannelTabInfo.getInfo(service, tab).relatedItems.mapNotNull(::toItem)
    }

    protected fun toItem(i: InfoItem): VideoItem? = when (i) {
        is StreamInfoItem -> VideoItem(
            source = id,
            id = i.url,
            title = i.name,
            author = i.uploaderName,
            authorId = i.uploaderUrl?.ifEmpty { null },
            thumbnailUrl = bestThumb(i.thumbnails),
            durationSec = i.duration.takeIf { it > 0 },
        )
        is PlaylistInfoItem -> VideoItem(
            source = id,
            id = COLLECTION_PREFIX + i.url,
            title = i.name,
            author = i.uploaderName,
            authorId = i.uploaderUrl?.ifEmpty { null },
            thumbnailUrl = bestThumb(i.thumbnails),
        )
        else -> null
    }

    private fun bestThumb(images: List<Image>): String? =
        images.sortedBy { it.width }.firstOrNull { it.width >= 300 }?.url ?: images.lastOrNull()?.url

    protected val headers get() = mapOf("User-Agent" to NewPipeDownloader.USER_AGENT)

    /** Best audio: progressive first (within the data-saver bitrate cap), else the HLS one. */
    protected fun audioOf(info: StreamInfo): PlayableStream? {
        val progressive = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { StreamSelector.AudioTrack(it.content, it.averageBitrate.toLong() * 1000, it.codec.orEmpty()) }
        StreamSelector.pickAudio(progressive, StreamPolicy.audioMaxBps)?.let { return PlayableStream(it.url, headers = headers) }
        info.audioStreams.firstOrNull { it.isUrl && it.deliveryMethod == DeliveryMethod.HLS }
            ?.let { return PlayableStream(it.content, headers = headers, kind = PlayableStream.Kind.HLS) }
        return null
    }
}

/** SoundCloud: charts / new & hot, search, playlists as folders. */
class SoundCloudSource : NewPipeServiceSource(SourceId.SOUNDCLOUD) {
    override val service: StreamingService get() = ServiceList.SoundCloud
    override suspend fun resolve(item: VideoItem): PlayableStream = withContext(Dispatchers.IO) {
        audioOf(StreamInfo.getInfo(service, item.id)) ?: throw UserFacingException(I18n.s.noAudioStream)
    }
}

/** Bandcamp: featured albums (folders) and Bandcamp Radio shows, search. */
class BandcampSource : NewPipeServiceSource(SourceId.BANDCAMP) {
    override val service: StreamingService get() = ServiceList.Bandcamp
    override suspend fun resolve(item: VideoItem): PlayableStream = withContext(Dispatchers.IO) {
        audioOf(StreamInfo.getInfo(service, item.id)) ?: throw UserFacingException(I18n.s.noAudioStream)
    }
}

/** PeerTube (the extractor's default instance): trending / most liked / recent videos. */
class PeerTubeSource : NewPipeServiceSource(SourceId.PEERTUBE) {
    override val service: StreamingService get() = ServiceList.PeerTube

    override suspend fun resolve(item: VideoItem): PlayableStream = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, item.id)
        val live = info.streamType.name.contains("LIVE")
        if (StreamPolicy.audioOnly) audioOf(info)?.let { return@withContext it.copy(audioOnly = true, live = live) }
        val muxed = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { StreamSelector.VideoTrack(it.content, it.height, it.codec.orEmpty(), width = it.width) }
        StreamSelector.pickVideo(muxed, StreamPolicy.maxHeight, DeviceCodecs::canPlay)
            ?.let { return@withContext PlayableStream(it.url, headers = headers) }
        if (info.hlsUrl.isNotEmpty()) return@withContext PlayableStream(info.hlsUrl, headers = headers, kind = PlayableStream.Kind.HLS, live = live)
        throw UserFacingException(I18n.s.noVideoStream)
    }
}
