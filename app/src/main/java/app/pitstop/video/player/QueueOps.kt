package app.pitstop.video.player

import app.pitstop.video.core.VideoItem

/**
 * Queue edits that keep track of the playing item ([index]). Pure, so it's unit-tested; the hub
 * applies the result and only restarts playback when the playing item itself was removed.
 */
object QueueOps {
    data class Result(val queue: List<VideoItem>, val index: Int, val currentRemoved: Boolean = false)

    fun remove(queue: List<VideoItem>, index: Int, at: Int): Result {
        if (at !in queue.indices) return Result(queue, index)
        val q = queue.toMutableList().apply { removeAt(at) }
        return when {
            at < index -> Result(q, index - 1)
            at > index -> Result(q, index)
            // The playing item went: the one after it (now at the same place) plays, or the queue ends.
            else -> Result(q, if (index < q.size) index else q.lastIndex, currentRemoved = true)
        }
    }

    /**
     * Removes the entries at [at] (a multi-selection). When the playing item goes, the first kept item
     * after it plays (or the last one, when nothing follows).
     */
    fun removeAll(queue: List<VideoItem>, index: Int, at: Set<Int>): Result {
        val gone = at.filterTo(HashSet()) { it in queue.indices }
        if (gone.isEmpty()) return Result(queue, index)
        val q = queue.filterIndexed { i, _ -> i !in gone }
        val before = (0 until index.coerceAtLeast(0)).count { it !in gone }
        return if (index in gone) Result(q, if (before < q.size) before else q.lastIndex, currentRemoved = true)
        else Result(q, before)
    }

    fun move(queue: List<VideoItem>, index: Int, from: Int, to: Int): Result {
        if (from !in queue.indices || from == to) return Result(queue, index)
        val target = to.coerceIn(0, queue.lastIndex)
        val q = queue.toMutableList()
        q.add(target, q.removeAt(from))
        val newIndex = when {
            from == index -> target
            from < index && target >= index -> index - 1
            from > index && target <= index -> index + 1
            else -> index
        }
        return Result(q, newIndex)
    }

    /** [items] right after the playing one. */
    fun playNext(queue: List<VideoItem>, index: Int, items: List<VideoItem>): Result {
        val at = (index + 1).coerceIn(0, queue.size)
        return Result(queue.take(at) + items + queue.drop(at), index)
    }

    /** Everything after the playing item goes. */
    fun clearUpcoming(queue: List<VideoItem>, index: Int): Result = Result(queue.take(index + 1), index)
}
