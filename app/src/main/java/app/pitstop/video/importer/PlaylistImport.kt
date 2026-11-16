package app.pitstop.video.importer

import app.pitstop.video.bilibili.BiliApi
import app.pitstop.video.core.I18n
import app.pitstop.video.core.Playlist
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.netease.NeteaseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Links of platforms Pitstop plays itself: imported track for track, no matching needed. Pure, unit-tested. */
sealed interface NativeLink {
    data class Netease(val id: Long) : NativeLink
    data class YouTube(val listId: String, val music: Boolean) : NativeLink
    data class BiliFav(val mediaId: Long) : NativeLink
    data class BiliSeason(val mid: Long, val seasonId: Long) : NativeLink
    data class BiliVideo(val bvid: String) : NativeLink

    companion object {
        fun parse(url: String): NativeLink? {
            Regex("""music\.163\.com/.*playlist.*[?&]id=(\d+)""").find(url)?.let { return Netease(it.groupValues[1].toLong()) }
            Regex("""music\.163\.com/(?:#/)?(?:m/)?playlist/(\d+)""").find(url)?.let { return Netease(it.groupValues[1].toLong()) }
            if (Regex("""(youtube\.com|youtu\.be)/""").containsMatchIn(url)) {
                Regex("""[?&]list=([\w-]+)""").find(url)?.let { return YouTube(it.groupValues[1], url.contains("music.youtube.com")) }
            }
            Regex("""space\.bilibili\.com/(\d+)/(?:channel/collectiondetail\?sid=|lists/)(\d+)""").find(url)?.let {
                return BiliSeason(it.groupValues[1].toLong(), it.groupValues[2].toLong())
            }
            Regex("""space\.bilibili\.com/\d+/favlist\?.*fid=(\d+)""").find(url)?.let { return BiliFav(it.groupValues[1].toLong()) }
            Regex("""bilibili\.com/(?:medialist/detail|list)/ml(\d+)""").find(url)?.let { return BiliFav(it.groupValues[1].toLong()) }
            Regex("""(BV[0-9A-Za-z]{10})""").find(url)?.let { return BiliVideo(it.groupValues[1]) }
            return null
        }
    }
}

/** What was read: [ready] items play as they are; [toMatch] tracks still have to be found on a platform. */
data class ImportRead(val name: String, val ready: List<VideoItem>, val toMatch: List<TrackMeta>, val from: String)

/** One matched (or not) track, for the import screen. */
data class MatchOutcome(val track: TrackMeta, val item: VideoItem?, val score: Double)

/**
 * Turns a link, pasted text or a file into playlist items. [music] picks the audio-only variant of a
 * platform (Bilibili Music, YouTube Music) for items imported as songs.
 */
class PlaylistImport(
    private val http: OkHttpClient,
    private val bili: BiliApi,
    private val netease: NeteaseApi,
    private val source: (SourceId) -> VideoSource,
) {
    private val importers: List<PlaylistImporter> = listOf(
        QqMusicImporter(http), KugouImporter(http), SpotifyImporter(http), AppleMusicImporter(http),
    )

    /** Pitstop playlist files contain whole playlists; returns them as-is, or null when [text] isn't one. */
    fun pitstopFile(text: String): List<Playlist>? = PlaylistFiles.fromPitstop(text)

    /** Reads a pasted link / share text, or plain text / CSV when there's no link. */
    suspend fun read(input: String, music: Boolean): ImportRead {
        val link = extractUrl(input)
        if (link == null || input.trim().lines().size > 1 && !looksLikeShare(input)) return readText(input)
        val url = runCatching { resolveShortLink(http, link) }.getOrDefault(link)
        NativeLink.parse(url)?.let { return readNative(it, music) }
        val importer = importers.firstOrNull { it.canHandle(url) } ?: throw UserFacingException(I18n.s.importUnknownLink)
        val list = importer.fetch(url)
        return ImportRead(list.name, emptyList(), list.tracks, importer.platform)
    }

    /** CSV (has a header with commas or tabs) or one "Artist - Title" per line. */
    fun readText(text: String, name: String = ""): ImportRead {
        val firstLine = text.trim().lineSequence().firstOrNull().orEmpty()
        val csv = firstLine.count { it == ',' || it == '\t' } >= 1 && text.trim().lines().size > 1
        val tracks = if (csv) PlaylistFiles.parseCsv(text) else PlaylistFiles.parseText(text)
        if (tracks.isEmpty()) throw UserFacingException(I18n.s.importNothingFound)
        return ImportRead(name, emptyList(), tracks, if (csv) "CSV" else I18n.s.importText)
    }

    private fun looksLikeShare(text: String) = text.trim().lines().count { it.isNotBlank() } <= 3

    private suspend fun readNative(link: NativeLink, music: Boolean): ImportRead = withContext(Dispatchers.IO) {
        when (link) {
            is NativeLink.Netease -> ImportRead("", netease.playlistTracks(link.id), emptyList(), I18n.s.netease)
            is NativeLink.YouTube -> {
                val sourceId = if (music || link.music) SourceId.YT_MUSIC else SourceId.YOUTUBE
                val url = "https://www.youtube.com/playlist?list=${link.listId}"
                val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
                val items = info.relatedItems.toMutableList()
                var next = info.nextPage
                while (next != null && items.size < 1000) {
                    val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
                    items += more.items
                    next = more.nextPage
                }
                ImportRead(info.name, items.filterIsInstance<StreamInfoItem>().map { s ->
                    VideoItem(
                        source = sourceId, id = s.url, title = s.name,
                        author = s.uploaderName?.removeSuffix(" - Topic"), authorId = s.uploaderUrl?.ifEmpty { null },
                        thumbnailUrl = s.thumbnails.sortedBy { it.width }.firstOrNull { it.width >= 320 }?.url ?: s.thumbnails.lastOrNull()?.url,
                        durationSec = s.duration.takeIf { it > 0 },
                    )
                }, emptyList(), "YouTube")
            }
            is NativeLink.BiliFav -> bili.favFolder(link.mediaId, biliSource(music)).let { (n, items) -> ImportRead(n, items, emptyList(), I18n.s.bilibili) }
            is NativeLink.BiliSeason -> bili.season(link.mid, link.seasonId, biliSource(music)).let { (n, items) -> ImportRead(n, items, emptyList(), I18n.s.bilibili) }
            is NativeLink.BiliVideo -> {
                // A single video; a multi-part one (an album / concert) becomes its parts.
                val v = bili.view(link.bvid)
                val item = VideoItem(biliSource(music), link.bvid, v.title, v.author, v.cover)
                val parts = runCatching { source(item.source).expand(item) }.getOrNull()
                ImportRead(v.title, parts ?: listOf(item), emptyList(), I18n.s.bilibili)
            }
        }
    }

    private fun biliSource(music: Boolean) = if (music) SourceId.BILI_MUSIC else SourceId.BILIBILI

    /**
     * Finds [track] on the platforms in [order] (first page of each platform's search) with [TrackMatcher].
     */
    suspend fun match(track: TrackMeta, order: List<SourceId>): MatchOutcome {
        val best = TrackMatcher.match(track, order) { id, q -> source(id).search(q) }
        return MatchOutcome(track, best?.item, best?.score ?: 0.0)
    }
}
