package app.pitstop.video

import app.pitstop.video.core.QualityLadder
import app.pitstop.video.core.StallTracker
import app.pitstop.video.core.isNetworkErrorCode
import app.pitstop.video.core.retryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilienceTest {
    @Test
    fun threeStallsInWindowStepDownThenStartOver() {
        val t = StallTracker(windowMs = 90_000, limit = 3)
        assertFalse(t.onStall(0))
        assertFalse(t.onStall(30_000))
        assertTrue(t.onStall(60_000))
        assertFalse(t.onStall(61_000)) // history cleared after a step down
    }

    @Test
    fun stallsSpreadOutDoNotCount() {
        val t = StallTracker(windowMs = 90_000, limit = 3)
        assertFalse(t.onStall(0))
        assertFalse(t.onStall(100_000))
        assertFalse(t.onStall(200_000))
    }

    @Test
    fun qualityLadderSteps() {
        assertEquals(720, QualityLadder.lower(1080))
        assertEquals(480, QualityLadder.lower(720))
        assertEquals(360, QualityLadder.lower(480))
        assertNull(QualityLadder.lower(360))
    }

    @Test
    fun backoffDoublesAndCaps() {
        assertEquals(1_000, retryDelayMs(0))
        assertEquals(8_000, retryDelayMs(3))
        assertEquals(16_000, retryDelayMs(9))
    }

    @Test
    fun networkCodes() {
        assertTrue(isNetworkErrorCode(2001)) // IO_NETWORK_CONNECTION_FAILED
        assertTrue(isNetworkErrorCode(2004)) // IO_BAD_HTTP_STATUS (expired link)
        assertFalse(isNetworkErrorCode(4001)) // decoder init
    }
}
