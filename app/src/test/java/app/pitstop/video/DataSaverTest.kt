package app.pitstop.video

import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class DataSaverTest {
    @Test
    fun userChoiceCapsTheHeight() {
        assertEquals(1080, StreamPolicy.effectiveMaxHeight(VideoQuality.AUTO, 1080, safe = false))
        assertEquals(480, StreamPolicy.effectiveMaxHeight(VideoQuality.P480, 1080, safe = false))
    }

    @Test
    fun stallLadderAndSafeModeOnlyLowerIt() {
        assertEquals(360, StreamPolicy.effectiveMaxHeight(VideoQuality.P720, 360, safe = false))
        assertEquals(480, StreamPolicy.effectiveMaxHeight(VideoQuality.AUTO, 1080, safe = true))
        assertEquals(360, StreamPolicy.effectiveMaxHeight(VideoQuality.P360, 1080, safe = true))
    }

    @Test
    fun audioOnlyKeepsTheLadderAsFallback() {
        // Audio only has no height of its own; if a source can't split audio, the normal cap applies.
        assertEquals(720, StreamPolicy.effectiveMaxHeight(VideoQuality.AUDIO, 720, safe = false))
    }
}
