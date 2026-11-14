package app.pitstop.video.importer

import app.pitstop.video.core.Playlist
import app.pitstop.video.core.PlaylistOps
import app.pitstop.video.core.VideoItem
import org.json.JSONObject

/**
 * Playlist files: Pitstop's own format (every item playable as-is, for backup / sharing with another
 * phone), CSV and plain text for other apps. Pure, unit-tested.
 */
object PlaylistFiles {
    const val FORMAT = "pitstop-playlists"
    const val VERSION = 1

    /** Pitstop file: `{"format":"pitstop-playlists","version":1,"playlists":[...]}`. */
    fun toPitstop(playlists: List<Playlist>): String =
        JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("playlists", org.json.JSONArray(PlaylistOps.toJson(playlists)))
            .toString(2)

    /** Playlists from a Pitstop file, or null when [text] isn't one. */
    fun fromPitstop(text: String): List<Playlist>? = runCatching {
        val o = JSONObject(text.trim())
        if (o.optString("format") != FORMAT) return null
        PlaylistOps.fromJson(o.getJSONArray("playlists").toString())
    }.getOrNull()

    /** CSV with a header row; opens in spreadsheets and TuneMyMusic-style tools. */
    fun toCsv(p: Playlist, link: (VideoItem) -> String?): String = buildString {
        appendLine("Title,Artist,Duration,Platform,Link")
        p.items.forEach { v ->
            appendLine(listOf(v.title, v.author.orEmpty(), v.durationSec?.toString().orEmpty(), v.source.name, link(v).orEmpty())
                .joinToString(",") { csvCell(it) })
        }
    }

    /** One "Artist - Title" line per item. */
    fun toText(p: Playlist): String = p.items.joinToString("\n") { v ->
        if (v.author.isNullOrBlank()) v.title else "${v.author} - ${v.title}"
    }

    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /**
     * Tracks from pasted text: one per line, "Artist - Title" (also "Title - Artist" is tolerated by
     * the matcher, since it searches both words); blank lines and numbering ("1. ", "01 ") are skipped.
     */
    fun parseText(text: String): List<TrackMeta> = text.lines()
        .map { it.trim().replace(Regex("""^\d{1,4}[.)、:\s]\s*"""), "") }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            if (parts.size == 2) TrackMeta(parts[1].trim(), splitArtists(parts[0])) else TrackMeta(line)
        }

    /**
     * CSV exports (Exportify, TuneMyMusic, Soundiiz, ours ...): finds the title / artist / album /
     * duration / ISRC columns by header name; without a header, column 1 = title, 2 = artist.
     */
    fun parseCsv(text: String): List<TrackMeta> {
        val rows = csvRows(text).filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = header.indexOfFirst { h -> names.any { h == it } }
            .takeIf { it >= 0 } ?: header.indexOfFirst { h -> names.any { h.contains(it) } }.takeIf { it >= 0 }
        val title = col("track name", "title", "name", "song", "track", "歌曲", "歌名", "标题")
        val hasHeader = title != null
        val t = title ?: 0
        val artist = if (hasHeader) col("artist name(s)", "artist name", "artist", "artists", "歌手", "艺人") else 1
        val album = if (hasHeader) col("album name", "album", "专辑") else null
        val durMs = if (hasHeader) col("duration (ms)", "duration_ms", "track duration (ms)") else null
        val dur = if (hasHeader && durMs == null) col("duration", "length", "时长") else null
        val isrc = if (hasHeader) col("isrc") else null
        return rows.drop(if (hasHeader) 1 else 0).mapNotNull { r ->
            val name = r.getOrNull(t)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            TrackMeta(
                title = name,
                artists = artist?.let { r.getOrNull(it) }?.let(::splitArtists).orEmpty(),
                album = album?.let { r.getOrNull(it)?.trim() }?.ifEmpty { null },
                durationSec = durMs?.let { r.getOrNull(it)?.trim()?.toLongOrNull()?.div(1000) }
                    ?: dur?.let { r.getOrNull(it)?.trim()?.let(::parseDuration) },
                isrc = isrc?.let { r.getOrNull(it)?.trim() }?.ifEmpty { null },
            )
        }
    }

    /** "A, B & C" / "A;B" / "A / B" / "A、B" → [A, B, C]. */
    fun splitArtists(s: String): List<String> =
        s.split(Regex("""\s*(,|;|/|&|、|，| feat\. | ft\. | x )\s*""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** "3:45", "1:02:03" or plain seconds. */
    fun parseDuration(s: String): Long? {
        if (s.isBlank()) return null
        s.toLongOrNull()?.let { return it }
        val parts = s.split(':').map { it.trim().toLongOrNull() ?: return null }
        return parts.fold(0L) { acc, p -> acc * 60 + p }
    }

    /** RFC 4180-ish CSV: quoted cells with "" escapes and line breaks; also tab-separated files. */
    fun csvRows(text: String): List<List<String>> {
        val sep = if (text.lineSequence().firstOrNull()?.let { it.count { c -> c == '\t' } > it.count { c -> c == ',' } } == true) '\t' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("﻿")
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && s.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }
}
