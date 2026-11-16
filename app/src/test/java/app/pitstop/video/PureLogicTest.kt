package app.pitstop.video

import app.pitstop.video.bilibili.BiliText
import app.pitstop.video.bilibili.Wbi
import app.pitstop.video.core.formatDuration
import app.pitstop.video.core.parseClock
import app.pitstop.video.core.parseIsoDuration
import app.pitstop.video.player.MotionLatch
import app.pitstop.video.player.StreamSelector
import app.pitstop.video.player.StreamSelector.AudioTrack
import app.pitstop.video.player.StreamSelector.VideoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WbiTest {
    // Reference vector from the bilibili-API-collect WBI documentation.
    private val img = "7cd084941338484aae1ad9425b84077c"
    private val sub = "4932caff0ff746eab6f01bf08b70ac45"

    @Test fun mixinKey() = assertEquals("ea1db124af3c7062474693fa704f4ff8", Wbi.mixinKey(img, sub))

    @Test fun sign() {
        val q = Wbi.sign(mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"), Wbi.mixinKey(img, sub), 1702204169)
        assertEquals("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4", q)
    }

    @Test fun encodesLikeEncodeUriComponent() {
        assertEquals("a%20b%E4%B8%AD-_.~", Wbi.encode("a b中-_.~"))
    }

    @Test fun keyFromUrl() =
        assertEquals("7cd084941338484aae1ad9425b84077c", Wbi.keyFromUrl("https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"))
}

class StreamSelectorTest {
    @Test fun picksHighestAllowedResolutionAndPrefersAvc() {
        val tracks = listOf(
            VideoTrack("2160", 2160, "avc"),
            VideoTrack("1080-av1", 1080, "av01.0.08M.08"),
            VideoTrack("1080-avc", 1080, "avc1.640028"),
            VideoTrack("720", 720, "avc1"),
        )
        assertEquals("1080-avc", StreamSelector.pickVideo(tracks)!!.url)
        assertEquals("720", StreamSelector.pickVideo(tracks, maxHeight = 720)!!.url)
    }

    @Test fun fallsBackToSmallestWhenAllTooBig() =
        assertEquals("1440", StreamSelector.pickVideo(listOf(VideoTrack("2160", 2160, "avc"), VideoTrack("1440", 1440, "avc")))!!.url)

    @Test fun emptyGivesNull() = assertNull(StreamSelector.pickVideo(emptyList()))

    @Test fun audioPrefersAacThenBitrate() {
        val a = listOf(AudioTrack("opus", 160, "opus"), AudioTrack("aac-low", 48, "mp4a.40.5"), AudioTrack("aac", 128, "mp4a.40.2"))
        assertEquals("aac", StreamSelector.pickAudio(a)!!.url)
    }
}

class MotionLatchTest {
    @Test fun hysteresis() {
        val l = MotionLatch(startMps = 2.5f, stopMps = 0.8f, stopHoldMs = 3000)
        assertFalse(l.update(1.0f, 0))      // creeping: still parked
        assertTrue(l.update(5f, 1000))      // driving
        assertTrue(l.update(0f, 2000))      // just stopped (red light)
        assertTrue(l.update(null, 3000))    // unknown keeps state
        assertTrue(l.update(0f, 4500))      // < 3 s stopped
        assertFalse(l.update(0f, 5100))     // stopped for 3.1 s
    }

    @Test fun slowRollingResetsStopTimer() {
        val l = MotionLatch(stopHoldMs = 3000)
        l.update(5f, 0)
        l.update(0f, 1000)
        l.update(1.5f, 2000)                // rolling again between thresholds
        assertTrue(l.update(0f, 4500))      // timer restarted at 4500
        assertFalse(l.update(0f, 7600))
    }
}

class TextAndTimeTest {
    @Test fun biliClean() = assertEquals("Hello \"World\" & co", BiliText.clean("<em class=\"keyword\">Hello</em> &quot;World&quot; &amp; co"))

    @Test fun biliThumb() {
        assertEquals("https://i0.hdslb.com/a.jpg@320w_180h_1c.jpg", BiliText.thumb("//i0.hdslb.com/a.jpg"))
        assertEquals("https://i1.hdslb.com/b.jpg@320w_180h_1c.jpg", BiliText.thumb("http://i1.hdslb.com/b.jpg"))
        assertNull(BiliText.thumb(""))
    }

    @Test fun durations() {
        assertEquals(3723L, parseIsoDuration("PT1H2M3S"))
        assertEquals(45L, parseIsoDuration("PT45S"))
        assertEquals(86400L + 60, parseIsoDuration("P1DT1M"))
        assertNull(parseIsoDuration("garbage"))
        assertEquals(125L, parseClock("2:05"))
        assertEquals(3725L, parseClock("1:02:05"))
        assertEquals("1:02:05", formatDuration(3725))
        assertEquals("2:05", formatDuration(125))
        assertEquals("", formatDuration(null))
    }
}
