package app.pitstop.video.core

enum class SourceId { YOUTUBE, BILIBILI, LOCAL }

/** One playable video as listed by a source. [id] is whatever the source needs to resolve it. */
data class VideoItem(
    val source: SourceId,
    val id: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val durationSec: Long? = null,
    /** Source-specific extra (e.g. Bilibili cid). */
    val extra: String? = null,
)

/** A browsable list inside a source ("Popular", "Subscriptions", ...). */
data class Feed(val key: String, val title: String)

/** Where the player should actually fetch media from. */
data class PlayableStream(
    val videoUri: String,
    /** Separate audio track (YouTube / Bilibili DASH split streams), merged at playback. */
    val audioUri: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val kind: Kind = Kind.PROGRESSIVE,
) {
    enum class Kind { PROGRESSIVE, HLS, DASH }
}

interface VideoSource {
    val id: SourceId
    val displayName: String
    val supportsSearch: Boolean get() = true

    /** Feeds available right now (login-only feeds disappear when signed out). */
    fun feeds(): List<Feed>
    suspend fun load(feed: Feed): List<VideoItem>
    suspend fun search(query: String): List<VideoItem>
    suspend fun resolve(item: VideoItem): PlayableStream
}

/** Thrown for problems the user can fix (sign in, grant permission...). Message is shown as-is. */
class UserFacingException(message: String) : Exception(message)

fun formatDuration(sec: Long?): String {
    if (sec == null || sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Parses "m:ss" or "h:mm:ss". */
fun parseClock(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    val parts = text.trim().split(":").map { it.toLongOrNull() ?: return null }
    return parts.fold(0L) { acc, p -> acc * 60 + p }
}

/** Parses ISO-8601 durations as used by the YouTube Data API, e.g. "PT1H2M3S". */
fun parseIsoDuration(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    val m = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""").matchEntire(text) ?: return null
    val (d, h, min, s) = m.destructured
    fun n(v: String) = v.toLongOrNull() ?: 0L
    return n(d) * 86400 + n(h) * 3600 + n(min) * 60 + n(s)
}
