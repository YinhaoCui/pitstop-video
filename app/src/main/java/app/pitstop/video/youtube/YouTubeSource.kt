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
import app.pitstop.video.core.Option
import app.pitstop.video.core.OptionGroup
import app.pitstop.video.core.Picked
import app.pitstop.video.core.pick
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor

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

    override fun searchOptions(): List<OptionGroup> {
        val s = I18n.s
        return listOf(
            OptionGroup(YtSearchParams.SORT, s.optSort, listOf(
                Option("0", s.optRelevance), Option("2", s.optNewest), Option("3", s.optMostViewed), Option("1", s.optTopRated),
            )),
            OptionGroup(YtSearchParams.DATE, s.optUploaded, listOf(
                Option("0", s.optAny), Option("1", s.optLastHour), Option("2", s.optToday), Option("3", s.optThisWeek),
                Option("4", s.optThisMonth), Option("5", s.optThisYear),
            )),
            OptionGroup(YtSearchParams.LENGTH, s.optLength, listOf(
                Option("0", s.optAny), Option("1", s.optUnderMin(4)), Option("3", s.optMinRange(4, 20)), Option("2", s.optOverMin(20)),
            )),
        )
    }

    /**
     * Search with YouTube's own sort / filters: NewPipe can't pass them, so this calls the site's
     * search endpoint with the "sp" parameter (NewPipe's request helpers and item parser). The cursor
     * is YouTube's continuation token.
     */
    override suspend fun searchPage(query: String, cursor: String?, picked: Picked): FeedPage {
        if (picked.isEmpty()) return searchPage(query, cursor)
        return withContext(Dispatchers.IO) {
            val loc = NewPipe.getPreferredLocalization()
            val builder = YoutubeParsingHelper.prepareDesktopJsonBuilder(loc, NewPipe.getPreferredContentCountry())
            if (cursor == null) {
                val sp = YtSearchParams.encode(
                    picked.pick(YtSearchParams.SORT, "0").toInt(), picked.pick(YtSearchParams.DATE, "0").toInt(),
                    picked.pick(YtSearchParams.LENGTH, "0").toInt(),
                )
                builder.value("query", query).value("params", sp)
            } else builder.value("continuation", cursor)
            val body = JsonWriter.string(builder.done()).toByteArray(Charsets.UTF_8)
            val json = YoutubeParsingHelper.getJsonPostResponse("search", body, loc)
            val parser = ServiceList.YouTube.getTimeAgoParser(loc)
            val videos = mutableListOf<JsonObject>()
            var next: String? = null
            fun walk(node: Any?) {
                when (node) {
                    is JsonObject -> {
                        node.getObject("videoRenderer").takeIf { it.isNotEmpty() }?.let { videos += it; return }
                        node.getObject("continuationItemRenderer").getObject("continuationEndpoint")
                            .getObject("continuationCommand").getString("token")?.let { next = it }
                        node.values.forEach(::walk)
                    }
                    is JsonArray -> node.forEach(::walk)
                }
            }
            walk(json)
            val items = videos.mapNotNull { v ->
                runCatching {
                    val e = YoutubeStreamInfoItemExtractor(v, parser)
                    VideoItem(
                        source = SourceId.YOUTUBE,
                        id = e.url,
                        title = e.name,
                        author = e.uploaderName,
                        authorId = e.uploaderUrl?.ifEmpty { null },
                        thumbnailUrl = e.thumbnails.sortedBy { it.width }.firstOrNull { it.width >= 320 }?.url ?: e.thumbnails.lastOrNull()?.url,
                        durationSec = e.duration.takeIf { it > 0 },
                    )
                }.getOrNull()
            }
            FeedPage(items, next?.takeIf { items.isNotEmpty() })
        }
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
                    ?: info.tabs.firstOrNull()
                    // Auto-generated artist channels ("Miles Davis - Topic") have no tabs at all:
                    // list the artist's songs from a YouTube Music search instead, only this channel's.
                    ?: return@withContext topicSongs(pager, channelUrl, info.name.removeSuffix(" - Topic"), source)
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

        /** Songs by one artist channel (a song search for its name, kept to that channel). Blocking. */
        private fun topicSongs(pager: NewPipePager, channelUrl: String, artist: String, source: SourceId): FeedPage {
            val channelId = channelUrl.substringAfterLast('/')
            return pager.pages(
                ServiceList.YouTube.getSearchExtractor(
                    artist, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "",
                ),
                null,
                { i ->
                    (i as? StreamInfoItem)?.takeIf { it.uploaderUrl.orEmpty().endsWith(channelId) }?.let { s ->
                        VideoItem(
                            source = source,
                            id = Regex("[?&]v=([\\w-]{6,})").find(s.url)?.groupValues?.get(1)?.let(::watchUrl) ?: s.url,
                            title = s.name,
                            author = artist,
                            thumbnailUrl = s.thumbnails.sortedBy { it.width }.firstOrNull { it.width >= 226 }?.url ?: s.thumbnails.lastOrNull()?.url,
                            durationSec = s.duration.takeIf { it > 0 },
                            authorId = channelUrl,
                        )
                    }
                },
            )
        }

        /** YouTube's search-as-you-type completions (also used for YouTube Music). */
        suspend fun youtubeSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
            if (query.isBlank()) emptyList()
            else runCatching { ServiceList.YouTube.suggestionExtractor.suggestionList(query) }.getOrNull().orEmpty()
                .filter { it.isNotBlank() }.distinct().take(10)
        }
    }
}
