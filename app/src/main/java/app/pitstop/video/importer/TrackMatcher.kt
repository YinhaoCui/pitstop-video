package app.pitstop.video.importer

import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * Finds a track (from another platform, a CSV ...) on the platforms Pitstop can play. Searches the
 * platforms in the user's order and keeps the best-scoring result; pure scoring, unit-tested.
 */
object TrackMatcher {

    /** A candidate with its score in 0..1; [MIN_SCORE] and above counts as a match. */
    data class Scored(val item: VideoItem, val score: Double)

    const val MIN_SCORE = 0.62
    /** Good enough to stop searching further platforms. */
    const val GREAT_SCORE = 0.86

    /**
     * Best match for [track]: tries each platform of [order] in turn (search "artist title"), stops early
     * on a great match. [search] returns a platform's first results page.
     */
    suspend fun match(
        track: TrackMeta,
        order: List<SourceId>,
        search: suspend (SourceId, String) -> List<VideoItem>,
    ): Scored? {
        var best: Scored? = null
        for (source in order) {
            val results = runCatching { search(source, query(track)) }.getOrDefault(emptyList())
            val top = results.take(10).map { Scored(it, score(track, it)) }.maxByOrNull { it.score } ?: continue
            if (best == null || top.score > best.score) best = top
            if (top.score >= GREAT_SCORE) break
        }
        return best?.takeIf { it.score >= MIN_SCORE }
    }

    fun query(track: TrackMeta): String =
        (track.artists.take(2) + cleanTitle(track.title)).joinToString(" ").trim()

    /**
     * Title similarity (most weight), artist found in the result's title or uploader, and duration
     * closeness. Live / cover / karaoke versions are penalised unless the original asks for them.
     */
    fun score(track: TrackMeta, item: VideoItem): Double {
        val wantTitle = normalize(cleanTitle(track.title))
        val haystack = normalize(item.title + " " + (item.author ?: ""))
        val gotTitle = normalize(cleanTitle(item.title))
        if (wantTitle.isEmpty()) return 0.0

        val titleSim = max(similarity(wantTitle, gotTitle), if (haystack.contains(wantTitle)) 0.92 else 0.0)
        val artistScore = when {
            track.artists.isEmpty() -> 0.5
            track.artists.any { a -> normalize(a).let { it.isNotEmpty() && haystack.contains(it) } } -> 1.0
            else -> 0.0
        }
        val durationScore = when {
            track.durationSec == null || item.durationSec == null -> 0.5
            else -> {
                val d = abs(track.durationSec - item.durationSec)
                when { d <= 3 -> 1.0; d <= 10 -> 0.8; d <= 30 -> 0.4; else -> 0.0 }
            }
        }
        var s = 0.6 * titleSim + 0.25 * artistScore + 0.15 * durationScore
        val wantRaw = track.title.lowercase()
        val gotRaw = item.title.lowercase()
        for (tag in VERSION_TAGS) if (gotRaw.contains(tag) && !wantRaw.contains(tag)) s -= 0.12
        return s.coerceIn(0.0, 1.0)
    }

    private val VERSION_TAGS = listOf("live", "cover", "karaoke", "伴奏", "翻唱", "现场", "remix", "instrumental", "钢琴版", "dj")

    /** Drops "(Official Video)", "【MV】", "- Remastered 2011", "feat. X" ... so titles compare on the song name. */
    fun cleanTitle(title: String): String = title
        .replace(Regex("""[(\[【《「（][^)\]】》」）]*(official|mv|lyric|audio|video|hd|4k|官方|歌词|高清|完整版)[^)\]】》」）]*[)\]】》」）]""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\s+-\s+(\d{4}\s+)?remaster(ed)?.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*[(\[（]?\s*(feat\.?|ft\.?)\s+[^)\]）]*[)\]）]?""", RegexOption.IGNORE_CASE), " ")
        .trim()

    /** Lower case, accents and full-width forms folded, punctuation and spaces removed. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase()
            .let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") }
            .replace(Regex("""[\p{P}\p{S}\s]+"""), "")

    /** 1 - normalised Levenshtein distance (works for CJK, where words have no spaces). */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            cur.copyInto(prev)
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }
}
