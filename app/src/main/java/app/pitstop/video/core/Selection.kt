package app.pitstop.video.core

/**
 * Pure multi-select logic for lists (unit-tested). A selection is a set of keys; "visible" is the
 * keys currently shown (after sort / filter), in display order. Select all and Invert act on the
 * visible keys only, so hidden rows never get picked up by a bulk action.
 */
object Selection {
    fun <K> toggle(selected: Set<K>, key: K): Set<K> = if (key in selected) selected - key else selected + key

    fun <K> selectAll(visible: List<K>): Set<K> = visible.toSet()

    /** Visible keys that were not selected; selected keys that are hidden drop out. */
    fun <K> invert(selected: Set<K>, visible: List<K>): Set<K> = visible.filterTo(LinkedHashSet()) { it !in selected }

    /** True when every visible key is selected (and there is at least one). */
    fun <K> allSelected(selected: Set<K>, visible: List<K>): Boolean = visible.isNotEmpty() && visible.all { it in selected }

    /** Drops keys that no longer exist, e.g. after the list changed underneath the selection. */
    fun <K> retain(selected: Set<K>, existing: Collection<K>): Set<K> {
        val keep = existing.toSet()
        return selected.filterTo(LinkedHashSet()) { it in keep }
    }

    /** The selected items in list order, selection by index. */
    fun <T> picked(items: List<T>, selected: Set<Int>): List<T> = items.filterIndexed { i, _ -> i in selected }

    /** [items] without the selected indices, keeping the order of the rest. */
    fun <T> removeSelected(items: List<T>, selected: Set<Int>): List<T> = items.filterIndexed { i, _ -> i !in selected }
}
