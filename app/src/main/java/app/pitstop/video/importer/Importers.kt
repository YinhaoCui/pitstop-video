package app.pitstop.video.importer

/**
 * Playlist import from other platforms. An importer reads a public playlist link into plain track
 * metadata; [TrackMatcher] then finds each track on the platforms Pitstop can play.
 */

/** One track as another platform describes it (nothing playable yet). */
data class TrackMeta(
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationSec: Long? = null,
    /** International Standard Recording Code, when the platform exposes it (Spotify, Apple). */
    val isrc: String? = null,
)

/** A playlist read from another platform. */
data class ImportedList(val name: String, val tracks: List<TrackMeta>, val coverUrl: String? = null)

/** Reads public playlists of one platform. */
interface PlaylistImporter {
    /** Short platform name shown in the import screen ("QQ Music", "Spotify" ...). */
    val platform: String

    /** True when [url] (already extracted from share text and with short links resolved) is this platform's playlist. */
    fun canHandle(url: String): Boolean

    /** All tracks of the playlist (following its pages); throws UserFacingException with a clear message on failure. */
    suspend fun fetch(url: String): ImportedList
}

/** Pulls the first http(s) link out of pasted share text ("分享歌单《…》: https://…"). Pure, unit-tested. */
fun extractUrl(text: String): String? =
    Regex("""https?://[^\s"'<>，。、）)】]+""").find(text)?.value?.trimEnd('.', ',', '!', '?')
