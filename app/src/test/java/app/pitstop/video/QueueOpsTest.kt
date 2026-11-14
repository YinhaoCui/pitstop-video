package app.pitstop.video

import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.player.QueueOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueOpsTest {
    private fun q(vararg ids: String) = ids.map { VideoItem(SourceId.YOUTUBE, it, it) }
    private fun ids(r: QueueOps.Result) = r.queue.joinToString("") { it.id }

    @Test fun removeKeepsThePlayingItem() {
        val queue = q("a", "b", "c", "d") // playing c (index 2)
        QueueOps.remove(queue, 2, 0).let { assertEquals("bcd", ids(it)); assertEquals(1, it.index); assertFalse(it.currentRemoved) }
        QueueOps.remove(queue, 2, 3).let { assertEquals("abc", ids(it)); assertEquals(2, it.index) }
        // Removing the playing item: the next one takes its place.
        QueueOps.remove(queue, 2, 2).let { assertEquals("abd", ids(it)); assertEquals(2, it.index); assertTrue(it.currentRemoved) }
        // ... or the previous one when it was last.
        QueueOps.remove(queue, 3, 3).let { assertEquals(2, it.index); assertTrue(it.currentRemoved) }
    }

    @Test fun moveFollowsThePlayingItem() {
        val queue = q("a", "b", "c", "d") // playing b (index 1)
        QueueOps.move(queue, 1, 3, 0).let { assertEquals("dabc", ids(it)); assertEquals(2, it.index) }
        QueueOps.move(queue, 1, 0, 3).let { assertEquals("bcda", ids(it)); assertEquals(0, it.index) }
        QueueOps.move(queue, 1, 1, 3).let { assertEquals("acdb", ids(it)); assertEquals(3, it.index) }
        QueueOps.move(queue, 1, 2, 3).let { assertEquals("abdc", ids(it)); assertEquals(1, it.index) }
        // "Play next" for an earlier item: moved to the playing item's place, which ends up right after it.
        QueueOps.move(q("a", "b", "c"), 2, 0, 2).let { assertEquals("bca", ids(it)); assertEquals(1, it.index) }
    }

    @Test fun playNextAndClearUpcoming() {
        val queue = q("a", "b", "c")
        assertEquals("abxyc", ids(QueueOps.playNext(queue, 1, q("x", "y"))))
        QueueOps.clearUpcoming(queue, 1).let { assertEquals("ab", ids(it)); assertEquals(1, it.index) }
    }
}
