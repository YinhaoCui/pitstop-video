package app.pitstop.video

import app.pitstop.video.core.Playlist
import app.pitstop.video.core.PlaylistOps
import app.pitstop.video.core.Selection
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    @Test fun toggleAddsAndRemoves() {
        assertEquals(setOf(1), Selection.toggle(emptySet(), 1))
        assertEquals(setOf(1, 3), Selection.toggle(setOf(1), 3))
        assertEquals(setOf(3), Selection.toggle(setOf(1, 3), 1))
    }

    @Test fun selectAllAndInvertUseVisibleKeysOnly() {
        val visible = listOf(4, 0, 2)
        assertEquals(setOf(0, 2, 4), Selection.selectAll(visible))
        assertEquals(emptySet<Int>(), Selection.selectAll(emptyList<Int>()))
        // 9 is selected but hidden by a filter: inverting drops it.
        assertEquals(setOf(4, 2), Selection.invert(setOf(0, 9), visible))
        assertEquals(setOf(0, 2, 4), Selection.invert(emptySet(), visible))
        assertEquals(emptySet<Int>(), Selection.invert(setOf(0, 2, 4), visible))
        // Inverting twice gives back the visible part of the selection.
        assertEquals(setOf(0), Selection.invert(Selection.invert(setOf(0, 9), visible), visible))
    }

    @Test fun allSelected() {
        assertTrue(Selection.allSelected(setOf(1, 2, 7), listOf(1, 2)))
        assertFalse(Selection.allSelected(setOf(1), listOf(1, 2)))
        assertFalse(Selection.allSelected(emptySet<Int>(), emptyList()))
    }

    @Test fun retainDropsMissingKeys() {
        assertEquals(setOf("a"), Selection.retain(setOf("a", "gone"), listOf("a", "b")))
    }

    @Test fun pickedAndRemoveKeepOrder() {
        val items = listOf("a", "b", "c", "d", "e")
        val sel = setOf(3, 0, 2)
        assertEquals(listOf("a", "c", "d"), Selection.picked(items, sel))
        assertEquals(listOf("b", "e"), Selection.removeSelected(items, sel))
        assertEquals(items, Selection.removeSelected(items, setOf(9, -1)))
        assertEquals(emptyList<String>(), Selection.removeSelected(items, items.indices.toSet()))
    }

    @Test fun playlistRemoveAll() {
        val a = VideoItem(SourceId.YOUTUBE, "a", "A")
        val b = VideoItem(SourceId.BILIBILI, "b", "B")
        val c = VideoItem(SourceId.LOCAL, "c", "C")
        val p = Playlist("p", "P", listOf(a, b, c))
        assertEquals(listOf(b), PlaylistOps.removeAll(p, setOf(0, 2)).items)
        assertEquals(p, PlaylistOps.removeAll(p, emptySet()))
    }
}
