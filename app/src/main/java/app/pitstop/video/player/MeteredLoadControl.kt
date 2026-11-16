package app.pitstop.video.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import app.pitstop.video.core.StreamPolicy

/**
 * Streaming-app buffering (30-120 s ahead; start fast, wait for 4 s after a stall), but on mobile data
 * it stops downloading once [StreamPolicy.maxBufferAheadMs] is buffered, so skipping wastes little.
 */
@OptIn(UnstableApi::class)
class MeteredLoadControl : DefaultLoadControl(
    DefaultAllocator(true, 64 * 1024),
    30_000, 120_000, 1_500, 4_000,
    DEFAULT_TARGET_BUFFER_BYTES,
    /* prioritizeTimeOverSizeThresholds = */ true,
    /* backBufferDurationMs = */ 30_000,
    /* retainBackBufferFromKeyframe = */ true,
) {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val wants = super.shouldContinueLoading(parameters)
        return wants && parameters.bufferedDurationUs < StreamPolicy.maxBufferAheadMs * 1000
    }
}
