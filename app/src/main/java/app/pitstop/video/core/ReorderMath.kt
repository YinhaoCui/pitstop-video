package app.pitstop.video.core

/**
 * Drag-to-reorder geometry for a list whose rows may differ in height. Rows keep their layout while
 * one is dragged; the others only slide by the dragged row's height. Pure JVM, unit-tested.
 */
object ReorderMath {
    /** A laid-out row: its index in the data, top edge and height (pixels, list viewport coordinates). */
    data class Slot(val index: Int, val top: Int, val size: Int)

    /**
     * Where row [from] (height [size], drawn with its top at [top]) would land among [slots]: past a
     * neighbour once it covers that neighbour's middle. Clamped to `0 until count`.
     */
    fun target(from: Int, top: Float, size: Int, slots: List<Slot>, count: Int): Int {
        if (count <= 0) return 0
        var to = from
        val bottom = top + size
        for (s in slots) {
            val middle = s.top + s.size / 2f
            if (s.index > from && bottom > middle) to = maxOf(to, s.index)
            if (s.index < from && top < middle) to = minOf(to, s.index)
        }
        return to.coerceIn(0, count - 1)
    }

    /** Rows the dragged one has passed move one place: -1 (up), +1 (down) or 0, in dragged-row heights. */
    fun shift(index: Int, from: Int, to: Int): Int = when {
        from < to && index in (from + 1)..to -> -1
        to < from && index in to until from -> 1
        else -> 0
    }
}
