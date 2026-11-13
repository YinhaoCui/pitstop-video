package app.pitstop.video.bilibili

import app.pitstop.video.core.Feed
import app.pitstop.video.core.I18n
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource

/** Bilibili's music zone, played audio-only (best stream: Hi-Res / Dolby when the account allows). */
class BiliMusicSource(private val api: BiliApi) : VideoSource {
    override val id = SourceId.BILI_MUSIC
    override val displayName get() = I18n.s.biliMusic

    override fun feeds() = listOf(Feed(FEED_RANKING, I18n.s.musicRanking))

    override suspend fun load(feed: Feed) = api.musicRanking()

    override suspend fun search(query: String) = api.search(query, tids = "3", source = SourceId.BILI_MUSIC)

    override val supportsCreators get() = true

    override suspend fun creatorVideos(authorId: String) = api.uploaderVideos(authorId, SourceId.BILI_MUSIC)

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
    }
}
