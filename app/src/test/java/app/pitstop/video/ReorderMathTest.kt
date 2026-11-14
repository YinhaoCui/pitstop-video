package app.pitstop.video

import app.pitstop.video.core.ReorderMath
import app.pitstop.video.core.ReorderMath.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderMathTest {
    // Five rows, 100 px each except row 2 (160 px).
    private val slots = listOf(Slot(0, 0, 100), Slot(1, 100, 100), Slot(2, 200, 160), Slot(3, 360, 100), Slot(4, 460, 100))

    @Test
    fun smallDragStaysInPlace() {
        assertEquals(1, ReorderMath.target(1, 140f, 100, slots, 5))
        assertEquals(1, ReorderMath.target(1, 60f, 100, slots, 5)) // top 60 > row 0 middle 50
    }

    @Test
    fun movesDownPastMiddles() {
        // Row 1 dragged so its bottom (top + 100) passes row 2's middle (280).
        assertEquals(1, ReorderMath.target(1, 170f, 100, slots, 5)) // bottom 270
        assertEquals(2, ReorderMath.target(1, 181f, 100, slots, 5)) // bottom 281
        assertEquals(3, ReorderMath.target(1, 311f, 100, slots, 5)) // bottom 411 > 410
        assertEquals(4, ReorderMath.target(1, 900f, 100, slots, 5)) // clamped to the last row
    }

    @Test
    fun movesUpPastMiddles() {
        assertEquals(3, ReorderMath.target(3, 290f, 100, slots, 5)) // top 290 > 280
        assertEquals(2, ReorderMath.target(3, 279f, 100, slots, 5))
        assertEquals(0, ReorderMath.target(3, -50f, 100, slots, 5))
    }

    @Test
    fun othersShiftOnePlace() {
        // 1 -> 3: rows 2 and 3 slide up, 0 and 4 stay.
        assertEquals(listOf(0, 0, -1, -1, 0), (0..4).map { ReorderMath.shift(it, 1, 3) })
        // 3 -> 0: rows 0..2 slide down.
        assertEquals(listOf(1, 1, 1, 0, 0), (0..4).map { ReorderMath.shift(it, 3, 0) })
        assertEquals(listOf(0, 0, 0, 0, 0), (0..4).map { ReorderMath.shift(it, 2, 2) })
    }
}
