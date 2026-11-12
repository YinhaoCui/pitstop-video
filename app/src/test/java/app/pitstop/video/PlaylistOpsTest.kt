package app.pitstop.video

import app.pitstop.video.core.Playlist
import app.pitstop.video.core.PlaylistOps
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOpsTest {
    private val a = VideoItem(SourceId.YOUTUBE, "https://www.youtube.com/watch?v=a", "A", "Chan", "https://t/a.jpg", 61)
    private val b = VideoItem(SourceId.BILIBILI, "BV1xx", "B", extra = "123")
    private val c = VideoItem(SourceId.LOCAL, "content://media/external/video/media/5", "C.mp4")
    private val p = Playlist("p1", "Road trip", listOf(a, b, c))

    @Test fun addSkipsDuplicates() {
        assertEquals(3, PlaylistOps.add(p, a.copy(title = "renamed")).items.size)
        assertEquals(1, PlaylistOps.add(Playlist("x", "x"), a).items.size)
        // same id from another source is a different video
        assertEquals(4, PlaylistOps.add(p, a.copy(source = SourceId.LOCAL)).items.size)
    }

    @Test fun removeAndMove() {
        assertEquals(listOf(a, c), PlaylistOps.removeAt(p, 1).items)
        assertEquals(p, PlaylistOps.removeAt(p, 9))
        assertEquals(listOf(b, c, a), PlaylistOps.move(p, 0, 2).items)
        assertEquals(listOf(c, a, b), PlaylistOps.move(p, 2, 0).items)
        assertEquals(p, PlaylistOps.move(p, 0, 5))
    }

    @Test fun jsonRoundTrip() {
        val list = listOf(p, Playlist("p2", "Empty"))
        assertEquals(list, PlaylistOps.fromJson(PlaylistOps.toJson(list)))
    }
}
