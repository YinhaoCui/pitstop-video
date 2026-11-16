package app.pitstop.video

import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.VideoQuality
import app.pitstop.video.download.DownloadNames
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNamesTest {
    @Test fun baseNameJoinsArtistAndDropsBadCharacters() {
        assertEquals("周杰伦 - 晴天", DownloadNames.baseName("晴天", "周杰伦"))
        // Artist already in the title: not repeated.
        assertEquals("Queen - Bohemian Rhapsody", DownloadNames.baseName("Queen - Bohemian Rhapsody", "Queen"))
        assertEquals("a_b_ c_d", DownloadNames.baseName("a/b: c?d", null))
        assertEquals("Pitstop", DownloadNames.baseName("...", null))
        assertEquals(120, DownloadNames.baseName("x".repeat(300), null).length)
    }

    @Test fun typesAndRanges() {
        assertEquals("m4a", DownloadNames.extensionFor("audio/mp4; codecs=\"mp4a.40.2\""))
        assertEquals("mp3", DownloadNames.extensionFor("audio/mpeg"))
        assertNull(DownloadNames.extensionFor("application/octet-stream"))
        assertEquals("flac", DownloadNames.extensionFromUrl("https://x.y/a/song.FLAC?sig=1"))
        assertNull(DownloadNames.extensionFromUrl("https://x.y/videoplayback?id=1"))
        assertEquals("video/mp4", DownloadNames.mimeFor("mp4"))
        assertTrue(DownloadNames.isAudio("m4a"))
        assertEquals(1234L, DownloadNames.totalFromContentRange("bytes 0-0/1234"))
        assertNull(DownloadNames.totalFromContentRange("bytes 0-0/*"))
    }

    @Test fun downloadQualityOverridesOnlyInsideTheDownload() = runBlocking {
        StreamPolicy.quality = VideoQuality.AUDIO
        StreamPolicy.metered = true
        withContext(StreamPolicy.forDownload(VideoQuality.P720)) {
            assertEquals(720, StreamPolicy.maxHeight)
            assertFalse(StreamPolicy.audioOnly)
            assertFalse(StreamPolicy.metered)
            assertTrue(StreamPolicy.downloading)
        }
        assertTrue(StreamPolicy.audioOnly)
        assertTrue(StreamPolicy.metered)
        assertFalse(StreamPolicy.downloading)
        StreamPolicy.quality = VideoQuality.AUTO
        StreamPolicy.metered = false
    }
}
