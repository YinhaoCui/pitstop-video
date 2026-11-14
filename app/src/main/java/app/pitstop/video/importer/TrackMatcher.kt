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
            val results = runCatching { search(source, query(track)) }.getOrDefault(emptyList()).take(10)
            val aliases = inferredArtists(track, results)
            val top = results.map { Scored(it, score(track, it, aliases)) }.maxByOrNull { it.score } ?: continue
            if (best == null || top.score > best.score) best = top
            if (top.score >= GREAT_SCORE) break
        }
        return best?.takeIf { it.score >= MIN_SCORE }
    }

    /**
     * The artist under another name ("久石譲" is "Joe Hisaishi" on YouTube Music): when no result
     * names the wanted artist, uploaders that come up at least twice in the results of the
     * "artist title" search. Normalised; empty when the artist is found as written.
     */
    fun inferredArtists(track: TrackMeta, results: List<VideoItem>): Set<String> {
        val wanted = track.artists.map(::normalize).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptySet()
        val named = results.any { r -> normalize(r.title + " " + (r.author ?: "")).let { h -> wanted.any { h.contains(it) } } }
        if (named) return emptySet()
        return results.mapNotNull { it.author?.let(::normalize)?.takeIf(String::isNotEmpty) }
            .groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
    }

    fun query(track: TrackMeta): String =
        (track.artists.take(2) + cleanTitle(track.title)).joinToString(" ").trim()

    /**
     * Title similarity (most weight), artist found in the result's title or uploader, and duration
     * closeness. Live / cover / karaoke versions are penalised unless the original asks for them.
     */
    fun score(track: TrackMeta, item: VideoItem, aliases: Set<String> = emptySet()): Double {
        val wantTitle = normalize(cleanTitle(track.title))
        val haystack = normalize(item.title + " " + (item.author ?: ""))
        val gotTitle = normalize(cleanTitle(item.title))
        if (wantTitle.isEmpty()) return 0.0

        // Also compare without bracketed extras ("Ask me why (疎開)" vs "Ask me why (Sokai)"), a little discounted.
        val coreSim = similarity(normalize(coreTitle(track.title)), normalize(coreTitle(item.title))) * 0.95
        val titleSim = maxOf(similarity(wantTitle, gotTitle), coreSim, if (haystack.contains(wantTitle)) 0.92 else 0.0)
        val artistScore = when {
            track.artists.isEmpty() -> 0.5
            track.artists.any { a -> normalize(a).let { it.isNotEmpty() && haystack.contains(it) } } -> 1.0
            item.author?.let(::normalize) in aliases -> 0.8
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
        // The artist's other name is known: a result by someone else is a different song or a cover.
        if (artistScore == 0.0 && aliases.isNotEmpty()) s -= 0.1
        val wantRaw = track.title.lowercase()
        val gotRaw = item.title.lowercase()
        for (tag in VERSION_TAGS) if (gotRaw.contains(tag) && !wantRaw.contains(tag)) s -= 0.12
        return s.coerceIn(0.0, 1.0)
    }

    private val VERSION_TAGS = listOf("live", "cover", "karaoke", "伴奏", "翻唱", "现场", "remix", "instrumental", "钢琴版", "dj", "piano version", "music box", "8-bit", "lullaby")

    /** Drops "(Official Video)", "【MV】", "- Remastered 2011", "feat. X" ... so titles compare on the song name. */
    fun cleanTitle(title: String): String = title
        .replace(Regex("""[(\[【《「（][^)\]】》」）]*(official|mv|lyric|audio|video|hd|4k|官方|歌词|高清|完整版)[^)\]】》」）]*[)\]】》」）]""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""\s+-\s+(\d{4}\s+)?remaster(ed)?.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*[(\[（]?\s*(feat\.?|ft\.?)\s+[^)\]）]*[)\]）]?""", RegexOption.IGNORE_CASE), " ")
        .trim()

    /** [cleanTitle] without any bracketed part: the bare song name. */
    fun coreTitle(title: String): String =
        cleanTitle(title).replace(Regex("""[(\[【《「（][^)\]】》」）]*[)\]】》」）]"""), " ").trim().ifEmpty { cleanTitle(title) }

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
