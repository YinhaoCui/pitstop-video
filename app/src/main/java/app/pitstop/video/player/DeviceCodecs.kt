package app.pitstop.video.player

import android.media.MediaCodecList
import app.pitstop.video.core.StreamPolicy

/** Asks the device which video codecs / sizes it can actually decode. */
object DeviceCodecs {
    private val decoders by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
            .getOrDefault(emptyList())
    }

    private fun mimeOf(codec: String): String? = when {
        codec.startsWith("avc") -> "video/avc"
        codec.startsWith("hev") || codec.startsWith("hvc") -> "video/hevc"
        codec.startsWith("av01") || codec == "av1" -> "video/av01"
        codec.startsWith("vp9") || codec.startsWith("vp09") -> "video/x-vnd.on2.vp9"
        else -> null
    }

    /** Use as StreamSelector's `supported` predicate. Unknown codecs are allowed. */
    fun canPlay(track: StreamSelector.VideoTrack): Boolean {
        // Downloads want AVC too: it merges with the AAC sound into an MP4 that plays everywhere.
        if ((StreamPolicy.safe || StreamPolicy.downloading) && !track.codec.startsWith("avc")) return false
        val mime = mimeOf(track.codec) ?: return !StreamPolicy.safe
        if (decoders.isEmpty()) return true
        val h = track.height.coerceAtLeast(1)
        val w = if (track.width > 0) track.width else h * 16 / 9
        return decoders.any { info ->
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } && runCatching {
                val caps = info.getCapabilitiesForType(mime).videoCapabilities
                caps == null || caps.isSizeSupported(w, h) || caps.isSizeSupported(h, w)
            }.getOrDefault(false)
        }
    }
}
