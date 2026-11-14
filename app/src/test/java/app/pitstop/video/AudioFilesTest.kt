package app.pitstop.video

import app.pitstop.video.core.AudioFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFilesTest {
    @Test fun audioMimeTypes() {
        assertTrue(AudioFiles.isAudio("Hello.mp3", "audio/mpeg"))
        assertTrue(AudioFiles.isAudio("no extension", "audio/flac"))
    }

    @Test fun mislabelledByDrive() {
        assertTrue(AudioFiles.isAudio("Song.m4a", "video/mp4"))
        assertTrue(AudioFiles.isAudio("Song.OGG", "application/ogg"))
        assertTrue(AudioFiles.isAudio("Song.opus", "application/octet-stream"))
    }

    @Test fun notSongs() {
        assertFalse(AudioFiles.isAudio("Clip.mp4", "video/mp4"))
        assertFalse(AudioFiles.isAudio("cover.jpg", "image/jpeg"))
        assertFalse(AudioFiles.isAudio("notes", "application/vnd.google-apps.document"))
    }
}
