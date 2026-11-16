package app.pitstop.video

import app.pitstop.video.core.Duplicates
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private fun drive(id: String, title: String) = VideoItem(SourceId.GDRIVE, id, title, author = "MP3")

    @Test fun sameItemTwice() {
        val a = VideoItem(SourceId.YOUTUBE, "v1", "Song", "Chan")
        val g = Duplicates.groups(listOf(a, VideoItem(SourceId.YOUTUBE, "v2", "Other", "Chan"), a)).single()
        assertEquals(listOf(0, 2), g.indices)
        assertEquals(setOf(2), g.exact)
    }

    @Test fun oldPickedCopyAndFolderCopyOfTheSameFile() {
        val old = drive("content://docs/document/1", "Adele - Hello")
        val folder = drive("content://docs/tree/t/document/9", "Adele - Hello")
        val g = Duplicates.groups(listOf(old, drive("content://x", "Ado - Show"), folder)).single()
        assertEquals(listOf(0, 2), g.indices)
        assertEquals(setOf(2), g.exact)
    }

    @Test fun tagsInBracketsAndArtistsAcrossPlatforms() {
        val items = listOf(
            drive("d1", "Adele - Hello"),
            VideoItem(SourceId.YOUTUBE, "y1", "Adele - Hello (Official Music Video)", "AdeleVEVO"),
            VideoItem(SourceId.YT_MUSIC, "m1", "Hello", "Adele"),
            VideoItem(SourceId.BILIBILI, "b1", "【4K】Adele - Hello", "某UP主 · 1万"),
            VideoItem(SourceId.YT_MUSIC, "m2", "Hello", "Lionel Richie"),
        )
        val g = Duplicates.groups(items).single()
        assertEquals(listOf(0, 1, 2, 3), g.indices)
        // Only look-alikes, not certain copies: nothing is ticked for removal by default.
        assertTrue(g.exact.isEmpty())
    }

    @Test fun differentSongsStayApart() {
        val items = listOf(drive("1", "Ado - Show"), drive("2", "Ado - Backlight"), drive("3", "Ado - New Genesis"))
        assertTrue(Duplicates.groups(items).isEmpty())
    }
}
