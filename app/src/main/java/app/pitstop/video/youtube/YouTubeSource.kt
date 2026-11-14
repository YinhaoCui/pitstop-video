package app.pitstop.video.youtube

import app.pitstop.video.core.I18n
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.Settings
import app.pitstop.video.player.DeviceCodecs
import java.util.Locale
import org.schabi.newpipe.extractor.MediaFormat
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.player.StreamSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.pitstop.video.core.Category
import app.pitstop.video.core.categoryKey
import app.pitstop.video.newpipe.CategoryKeys
import app.pitstop.video.newpipe.NewPipePager
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
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
    override val displayName get() = I18n.s.youtube

    override fun feeds(): List<Feed> = buildList {
        add(Feed(FEED_POPULAR, I18n.s.popular))
        if (account.signedIn.value) {
            add(Feed(FEED_SUBSCRIPTIONS, I18n.s.subscriptions))
            add(Feed(FEED_LIKED, I18n.s.liked))
        }
    }

    override suspend fun load(feed: Feed): List<VideoItem> = loadPage(feed, null).items

    /** NewPipe's next-page handles aren't strings: the pager keeps them, cursors are keys into it. */
    private val pager = NewPipePager()

    private fun kiosks(): List<String> {
        val list = ServiceList.YouTube.kioskList
        return CategoryKeys.kioskOrder(list.availableKiosks, list.defaultKioskId)
    }

    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        // Data API feeds: the cursor is the API's own pageToken.
        when (feed.key) {
            FEED_SUBSCRIPTIONS -> return account.subscriptionPage(cursor)
            FEED_LIKED -> return account.likedPage(cursor)
        }
        val category = feed.categoryKey()
        if (category == null && feed.key != FEED_POPULAR) return FeedPage(emptyList())
        return withContext(Dispatchers.IO) {
            when {
                cursor != null -> pager.resume(cursor)
                category == null -> kioskChain(kiosks(), 0)
                category.startsWith(CategoryKeys.KIOSK) -> {
                    val kiosk = category.removePrefix(CategoryKeys.KIOSK)
                    kioskPages(kiosk) { searchFirst(I18n.s.ytTopicQuery(CategoryKeys.ytKioskFollowUp(kiosk))) }
                }
                else -> searchFirst(I18n.s.ytTopicQuery(category.removePrefix(CategoryKeys.TOPIC)))
            }
        }
    }

    /**
     * Popular, endlessly: YouTube's trending lists are short charts, so when one runs out the feed
     * continues with the next trending kiosk (music, gaming, podcasts, live ...), then with a search.
     */
    private fun kioskChain(kiosks: List<String>, index: Int): FeedPage {
        val kiosk = kiosks.getOrNull(index) ?: return searchFirst(I18n.s.ytTopicQuery("popular"))
        return kioskPages(kiosk) { kioskChain(kiosks, index + 1) }
    }

    private fun kioskPages(kioskId: String, after: () -> FeedPage): FeedPage {
        val extractor = runCatching { ServiceList.YouTube.kioskList.getExtractorById(kioskId, null) }.getOrNull()
            ?: return after()
        return pager.pages(extractor, null, ::toStream, after)
    }

    private fun searchFirst(query: String): FeedPage = pager.pages(
        ServiceList.YouTube.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""), null, ::toStream,
    )

    private fun toStream(i: InfoItem): VideoItem? = (i as? StreamInfoItem)?.let(::toItem)

    /** Trending lists (YouTube's own kiosks), then topics that are searches in the app language. */
    override suspend fun categories(): List<Category> = withContext(Dispatchers.IO) {
        val s = I18n.s
        val trending = runCatching { kiosks() }.getOrDefault(emptyList()).map { k ->
            Category(CategoryKeys.KIOSK + k, s.ytCategory(k) ?: k, s.catTrending)
        }
        trending + CategoryKeys.named(CategoryKeys.YT_TOPICS, s::ytCategory, s.catTopics) { CategoryKeys.TOPIC + it }
    }

    override val supportsCreators get() = true

    override suspend fun creatorVideos(authorId: String) = creatorPage(authorId, null).items

    override suspend fun creatorPage(authorId: String, cursor: String?): FeedPage =
        channelPage(pager, authorId, SourceId.YOUTUBE, cursor)

    override suspend fun search(query: String): List<VideoItem> = searchPage(query, null).items

    override suspend fun searchPage(query: String, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        if (cursor != null) pager.resume(cursor) else searchFirst(query)
    }

    override suspend fun suggestions(query: String): List<String> = youtubeSuggestions(query)

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
            .map { StreamSelector.VideoTrack(it.content, it.height, it.codec.orEmpty(), it.bitrate.toLong(), it.width) }
        val audio = info.audioStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            // averageBitrate is in kbps; StreamSelector works in bits/s.
            .map { StreamSelector.AudioTrack(it.content, it.averageBitrate.toLong() * 1000, it.codec.orEmpty()) }

        val subs = YouTubeSubtitles.pick(
            info.subtitles.filter { it.isUrl && it.format == MediaFormat.VTT }.map {
                YouTubeSubtitles.Candidate(it.content, it.languageTag, it.displayLanguageName, it.isAutoGenerated)
            },
            Settings.subtitleLanguage(),
        )
        val a = StreamSelector.pickAudio(audio, StreamPolicy.audioMaxBps)
        if (StreamPolicy.audioOnly && a != null) {
            return@withContext PlayableStream(a.url, headers = headers, subtitles = subs, audioOnly = true)
        }
        val v = StreamSelector.pickVideo(videoOnly, StreamPolicy.maxHeight, DeviceCodecs::canPlay)
        if (v != null && a != null) return@withContext PlayableStream(v.url, a.url, headers, subtitles = subs)

        val muxed = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .map { StreamSelector.VideoTrack(it.content, it.height, it.codec.orEmpty(), width = it.width) }
        StreamSelector.pickVideo(muxed, StreamPolicy.maxHeight, DeviceCodecs::canPlay)
            ?.let { return@withContext PlayableStream(it.url, headers = headers, subtitles = subs) }

        if (info.hlsUrl.isNotEmpty()) return@withContext PlayableStream(info.hlsUrl, headers = headers, kind = PlayableStream.Kind.HLS)
        throw UserFacingException("No playable stream found for this YouTube video")
    }

    private fun toItem(s: StreamInfoItem) = VideoItem(
        source = SourceId.YOUTUBE,
        id = s.url,
        title = s.name,
        author = s.uploaderName,
        authorId = s.uploaderUrl?.ifEmpty { null },
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
        fun channelUrl(channelId: String) = "https://www.youtube.com/channel/$channelId"

        /** A channel's Videos tab (newest first), via NewPipeExtractor; one page. */
        suspend fun channelVideos(channelUrl: String, source: SourceId): List<VideoItem> =
            channelPage(NewPipePager(), channelUrl, source, null).items

        /** A channel's Videos tab (newest first), endlessly; cursors live in [pager]. */
        suspend fun channelPage(pager: NewPipePager, channelUrl: String, source: SourceId, cursor: String?): FeedPage =
            withContext(Dispatchers.IO) {
                if (cursor != null) return@withContext pager.resume(cursor)
                val service = ServiceList.YouTube
                val info = ChannelInfo.getInfo(service, channelUrl)
                val tab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
                    ?: info.tabs.firstOrNull() ?: return@withContext FeedPage(emptyList())
                pager.pages(service.getChannelTabExtractor(tab), null, { i ->
                    (i as? StreamInfoItem)?.let { s ->
                        VideoItem(
                            source = source,
                            id = s.url,
                            title = s.name,
                            author = (s.uploaderName ?: info.name)?.removeSuffix(" - Topic"),
                            thumbnailUrl = s.thumbnails.sortedBy { it.width }.firstOrNull { it.width >= 320 }?.url ?: s.thumbnails.lastOrNull()?.url,
                            durationSec = s.duration.takeIf { it > 0 },
                            authorId = channelUrl,
                        )
                    }
                })
            }

        /** YouTube's search-as-you-type completions (also used for YouTube Music). */
        suspend fun youtubeSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
            if (query.isBlank()) emptyList()
            else runCatching { ServiceList.YouTube.suggestionExtractor.suggestionList(query) }.getOrNull().orEmpty()
                .filter { it.isNotBlank() }.distinct().take(10)
        }
    }
}
