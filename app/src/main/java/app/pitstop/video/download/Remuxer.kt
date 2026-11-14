package app.pitstop.video.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies the tracks of one or more downloaded files (a video-only and an audio-only stream, or a
 * single fragmented DASH file) into one ordinary MP4 / WebM, without re-encoding.
 */
object Remuxer {
    enum class Container(val ext: String, val audioExt: String, val format: Int) {
        MP4("mp4", "m4a", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
        WEBM("webm", "weba", MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM),
    }

    private val MP4_TYPES = buildSet {
        add("video/avc"); add("video/hevc"); add("audio/mp4a-latm")
        if (Build.VERSION.SDK_INT >= 34) add("video/av01")
    }
    private val WEBM_TYPES = setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "audio/opus", "audio/vorbis")

    /** [container] all tracks fit in (null: save the file as downloaded); [hasVideo] false for sound only. */
    data class Probe(val container: Container?, val hasVideo: Boolean?)

    fun probe(inputs: List<File>): Probe {
        val mimes = inputs.flatMap { f ->
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.path)
                (0 until ex.trackCount).mapNotNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            } catch (_: Exception) {
                return Probe(null, null)
            } finally {
                ex.release()
            }
        }.filter { it.startsWith("audio/") || it.startsWith("video/") }
        if (mimes.isEmpty()) return Probe(null, null)
        val container = when {
            mimes.all { it in MP4_TYPES } -> Container.MP4
            mimes.all { it in WEBM_TYPES } -> Container.WEBM
            else -> null
        }
        return Probe(container, mimes.any { it.startsWith("video/") })
    }

    /** Writes every audio/video track of [inputs] into [out], samples interleaved by time. */
    fun mux(inputs: List<File>, out: File, container: Container, onProgress: (Float) -> Unit = {}) {
        val muxer = MediaMuxer(out.path, container.format)
        val readers = mutableListOf<Reader>()
        try {
            var durationUs = 0L
            for (f in inputs) {
                val ex = MediaExtractor().apply { setDataSource(f.path) }
                for (t in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(t)
                    val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                    val bufSize = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                    // One extractor per track, so each one can be read at its own pace.
                    val own = if (readers.any { it.extractor === ex }) MediaExtractor().apply { setDataSource(f.path) } else ex
                    own.selectTrack(t)
                    readers += Reader(own, muxer.addTrack(fmt), ByteBuffer.allocate(maxOf(bufSize, 4 shl 20)))
                }
                if (readers.none { it.extractor === ex }) ex.release()
            }
            require(readers.isNotEmpty()) { "No audio or video in the download" }
            muxer.start()
            val info = MediaCodec.BufferInfo()
            readers.forEach { it.advance() }
            var lastReport = 0L
            while (true) {
                val r = readers.filter { !it.done }.minByOrNull { it.timeUs } ?: break
                info.set(0, r.size, r.timeUs, toCodecFlags(r.extractor.sampleFlags))
                muxer.writeSampleData(r.track, r.buffer, info)
                if (durationUs > 0 && r.timeUs - lastReport > 2_000_000) {
                    lastReport = r.timeUs
                    onProgress((r.timeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                }
                r.extractor.advance()
                r.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            readers.map { it.extractor }.distinct().forEach { runCatching { it.release() } }
        }
    }

    private fun toCodecFlags(sampleFlags: Int): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

    private class Reader(val extractor: MediaExtractor, val track: Int, var buffer: ByteBuffer) {
        var size = 0
        var timeUs = 0L
        var done = false

        /** Loads the current sample into [buffer]; marks [done] at the end. */
        fun advance() {
            buffer.clear()
            size = extractor.readSampleData(buffer, 0)
            if (size < 0) { done = true; return }
            timeUs = extractor.sampleTime
        }
    }
}
