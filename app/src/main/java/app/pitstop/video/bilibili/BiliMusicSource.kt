package app.pitstop.video.bilibili

import app.pitstop.video.core.Category
import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.categoryKey

/** Bilibili's music zone, played audio-only (best stream: Hi-Res / Dolby when the account allows). */
class BiliMusicSource(private val api: BiliApi) : VideoSource {
    override val id = SourceId.BILI_MUSIC
    override val displayName get() = I18n.s.biliMusic

    override fun feeds() = listOf(
        Feed(FEED_RANKING, I18n.s.musicRanking),
        Feed(FEED_RECOMMENDED, I18n.s.recommended),
    )

    override suspend fun load(feed: Feed) = loadPage(feed, null).items

    /** Every feed and category is endless; the cursor is always the next page number. */
    override suspend fun loadPage(feed: Feed, cursor: String?): FeedPage {
        val page = cursor?.toIntOrNull() ?: 1
        val cat = feed.categoryKey()?.let(BiliMusicKeys::parse)
        return when {
            cat is BiliMusicKeys.Key.SubZone ->
                api.searchPage(cat.keyword, page, tids = cat.oldTid.toString(), source = SourceId.BILI_MUSIC)
            cat is BiliMusicKeys.Key.Chart ->
                if (page == 1) FeedPage(api.musicCenterList(cat.path)) else FeedPage(emptyList())
            feed.key == FEED_RECOMMENDED -> api.zonePage(BiliMusicKeys.MUSIC_ZONE, page, SourceId.BILI_MUSIC)
            else -> api.musicRankingPage(page)
        }
    }

    /**
     * The music zone's sub-zones (names from Bilibili) and the music center's charts. Bilibili has no list
     * endpoint for sub-zones any more, so a sub-zone lists a search for its name inside the matching zone.
     */
    override suspend fun categories(): List<Category> {
        val subs = runCatching { BiliMusicKeys.subZones(api.zones()) }.getOrDefault(emptyList())
        return subs + BiliMusicKeys.charts(I18n.s.topCharts, I18n.s.musicHot, I18n.s.newReleases)
    }

    override suspend fun search(query: String) = api.search(query, tids = "3", source = SourceId.BILI_MUSIC)

    /** Endless search inside the music zone (tids 3); the cursor is the next page number. */
    override suspend fun searchPage(query: String, cursor: String?): FeedPage =
        api.searchPage(query, cursor?.toIntOrNull() ?: 1, tids = "3", source = SourceId.BILI_MUSIC)

    override suspend fun suggestions(query: String): List<String> = api.suggestions(query)

    override val supportsCreators get() = true

    override suspend fun creatorVideos(authorId: String) = api.uploaderVideos(authorId, SourceId.BILI_MUSIC)

    override suspend fun creatorPage(authorId: String, cursor: String?): FeedPage =
        api.uploaderPage(authorId, cursor?.toIntOrNull() ?: 1, SourceId.BILI_MUSIC)

    /** Albums / concerts uploaded as one video with many parts: one track per part. */
    override suspend fun expand(item: VideoItem): List<VideoItem>? {
        if (item.part != null) return null
        val v = api.view(item.id)
        if (v.pages.size <= 1) return null
        return v.pages.map { p ->
            VideoItem(
                source = SourceId.BILI_MUSIC,
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
        return api.audioUrl(item.id, cid)
    }

    companion object {
        const val FEED_RANKING = "ranking"
        const val FEED_RECOMMENDED = "recommended"
    }
}

/** Category keys of the music source (pure; unit-tested). */
object BiliMusicKeys {
    /** The music zone's id in Bilibili's current zone scheme (region feed). */
    const val MUSIC_ZONE = 1003

    /** The search API still filters by the old zone ids; new sub-zone route -> old id (unknown ones: whole music zone, 3). */
    private val OLD_TIDS = mapOf(
        "original" to 28, "cover" to 31, "vocaloid" to 30, "perform" to 59, "mv" to 193,
        "live" to 29, "other" to 130, "commentary" to 243, "tutorial" to 244,
    )
    private const val OLD_MUSIC = 3

    sealed interface Key {
        data class SubZone(val oldTid: Int, val keyword: String) : Key
        data class Chart(val path: String) : Key
    }

    /** The music zone's sub-zones, grouped under the zone's name. */
    fun subZones(zones: List<BiliZones.Zone>): List<Category> {
        val music = zones.firstOrNull { it.route == "music" } ?: zones.firstOrNull { it.tid == MUSIC_ZONE } ?: return emptyList()
        return music.subs.map { s ->
            Category("s:${OLD_TIDS[s.route] ?: OLD_MUSIC}:${s.name}", s.name, group = music.name)
        }
    }

    fun charts(group: String, hot: String, new: String) = listOf(
        Category("c:hot", hot, group = group),
        Category("c:new", new, group = group),
    )

    fun parse(key: String): Key? {
        val parts = key.split(':', limit = 3)
        return when (parts[0]) {
            "s" -> {
                val tid = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val keyword = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
                Key.SubZone(tid, keyword)
            }
            "c" -> when (parts.getOrNull(1)) {
                "hot" -> Key.Chart(BiliApi.MUSIC_HOT)
                "new" -> Key.Chart(BiliApi.MUSIC_NEW)
                else -> null
            }
            else -> null
        }
    }
}
