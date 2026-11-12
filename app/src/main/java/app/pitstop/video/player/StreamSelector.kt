package app.pitstop.video.player

/** Pure stream-picking rules, kept free of Android / NewPipe types so they are unit-testable. */
object StreamSelector {

    data class VideoTrack(
        val url: String,
        val height: Int,
        /** "avc", "hevc", "av1", "vp9" ... */
        val codec: String,
        val bandwidth: Long = 0,
    )

    data class AudioTrack(val url: String, val bandwidth: Long, val codec: String = "")

    /** Codec preference: AVC plays everywhere, HEVC/AV1 are hardware-decoded on Pixel 9 Pro (Tensor G4). */
    private fun codecRank(codec: String): Int = when {
        codec.startsWith("avc") -> 0
        codec.startsWith("hev") || codec.startsWith("hvc") -> 1
        codec.startsWith("av01") || codec == "av1" -> 2
        codec.startsWith("vp9") || codec.startsWith("vp09") -> 3
        else -> 4
    }

    /** Highest resolution not above [maxHeight] (falls back to the smallest one), best codec within it. */
    fun pickVideo(tracks: List<VideoTrack>, maxHeight: Int = 1080): VideoTrack? {
        if (tracks.isEmpty()) return null
        val allowed = tracks.filter { it.height in 1..maxHeight }
        val pool = allowed.ifEmpty { listOf(tracks.minBy { it.height }) }
        val best = pool.maxOf { it.height }
        return pool.filter { it.height == best }
            .sortedWith(compareBy<VideoTrack> { codecRank(it.codec) }.thenByDescending { it.bandwidth })
            .first()
    }

    /** Prefer AAC (mp4a) for compatibility, then highest bitrate. */
    fun pickAudio(tracks: List<AudioTrack>): AudioTrack? =
        tracks.sortedWith(
            compareBy<AudioTrack> { if (it.codec.startsWith("mp4a") || it.codec.isEmpty()) 0 else 1 }
                .thenByDescending { it.bandwidth }
        ).firstOrNull()

    /** Bilibili quality id -> nominal height. */
    fun biliQnHeight(qn: Int): Int = when (qn) {
        6 -> 240; 16 -> 360; 32 -> 480; 64, 74 -> 720; 80, 112, 116 -> 1080
        120 -> 2160; 125, 126, 127 -> 2160
        else -> 0
    }

    fun biliCodec(codecId: Int): String = when (codecId) {
        7 -> "avc"; 12 -> "hevc"; 13 -> "av1"; else -> "unknown"
    }
}
