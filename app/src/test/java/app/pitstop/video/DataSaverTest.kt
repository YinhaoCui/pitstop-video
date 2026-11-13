package app.pitstop.video

import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.ThumbUrls
import app.pitstop.video.player.StreamSelector
import app.pitstop.video.player.StreamSelector.AudioTrack
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

    @Test
    fun mobileAudioCapPicksBestTrackUnderIt() {
        // Bilibili-style tracks: 64k / 132k / 192k AAC.
        val a = listOf(AudioTrack("64", 67_000), AudioTrack("132", 132_000), AudioTrack("192", 190_000))
        assertEquals("192", StreamSelector.pickAudio(a, StreamPolicy.audioMaxBps(metered = false))!!.url)
        assertEquals("132", StreamSelector.pickAudio(a, StreamPolicy.audioMaxBps(metered = true))!!.url)
        // Everything above the cap: the smallest one.
        assertEquals("132", StreamSelector.pickAudio(a.drop(1), 100_000)!!.url)
    }

    @Test
    fun mobileDataBuffersLessAhead() {
        assertEquals(40_000L, StreamPolicy.maxBufferAheadMs(metered = true))
        assertEquals(120_000L, StreamPolicy.maxBufferAheadMs(metered = false))
    }

    @Test
    fun thumbnailUrlsShrink() {
        assertEquals(
            "https://i.ytimg.com/vi/abc123/mqdefault.jpg",
            ThumbUrls.smaller("https://i.ytimg.com/vi/abc123/hq720.jpg?sqp=xyz&rs=abc", 320),
        )
        // Bigger than mqdefault was asked for: leave it.
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", ThumbUrls.smaller("https://i.ytimg.com/vi/abc123/hqdefault.jpg", 600))
        assertEquals(
            "https://lh3.googleusercontent.com/x=w226-h226-l90-rj",
            ThumbUrls.smaller("https://lh3.googleusercontent.com/x=w544-h544-l90-rj", 226),
        )
        assertEquals("https://i0.hdslb.com/bfs/archive/a.jpg@320w.jpg", ThumbUrls.smaller("https://i0.hdslb.com/bfs/archive/a.jpg", 320))
        assertEquals("https://p1.music.126.net/a.jpg?param=320y320", ThumbUrls.smaller("https://p1.music.126.net/a.jpg", 320))
        assertEquals("https://example.com/a.jpg", ThumbUrls.smaller("https://example.com/a.jpg", 320))
    }
}
