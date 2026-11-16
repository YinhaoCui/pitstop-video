package app.pitstop.video.core

/**
 * A choice the platform itself applies (it changes what the server returns): a sort order or a
 * filter for search results or a creator's videos. The first option of a group is the default.
 */
data class Option(val key: String, val title: String)
data class OptionGroup(val key: String, val title: String, val options: List<Option>) {
    val default: String get() = options.first().key
}

/** Picked platform options: group key -> option key. Groups left at their default are omitted. */
typealias Picked = Map<String, String>

fun Picked.pick(group: String, default: String): String = this[group] ?: default

/** Drops entries that are the group's default, so "nothing changed" is an empty map. */
fun Picked.normalized(groups: List<OptionGroup>): Picked =
    filter { (g, v) -> groups.firstOrNull { it.key == g }?.let { it.default != v } ?: false }

/** In-app order for an already loaded list. */
enum class ListSort { DEFAULT, TITLE, AUTHOR, SHORTEST, LONGEST }

/** Length filter applied in the app; items without a known length only pass [ANY]. */
enum class LengthFilter(val minSec: Long, val maxSec: Long) {
    ANY(0, Long.MAX_VALUE), SHORT(0, 4 * 60 - 1), MEDIUM(4 * 60, 20 * 60), LONG(20 * 60 + 1, Long.MAX_VALUE);
    fun accepts(sec: Long?): Boolean = this == ANY || (sec != null && sec > 0 && sec in minSec..maxSec)
}

/**
 * How the app shows a loaded list: [sort], [length], a [text] that title or creator must contain, and
 * the [platforms] to keep (empty = all). Folders always stay on top and are never filtered out.
 */
data class ListView(
    val sort: ListSort = ListSort.DEFAULT,
    val length: LengthFilter = LengthFilter.ANY,
    val text: String = "",
    val platforms: Set<SourceId> = emptySet(),
) {
    val isDefault: Boolean get() = this == ListView()

    fun apply(items: List<VideoItem>, isFolder: (VideoItem) -> Boolean = { false }): List<VideoItem> {
        if (isDefault) return items
        val (folders, tracks) = items.partition(isFolder)
        val needle = text.trim()
        val kept = tracks.filter { item ->
            length.accepts(item.durationSec) &&
                (platforms.isEmpty() || item.source in platforms) &&
                (needle.isEmpty() || item.title.contains(needle, ignoreCase = true) || item.author.orEmpty().contains(needle, ignoreCase = true))
        }
        val sorted = when (sort) {
            ListSort.DEFAULT -> kept
            ListSort.TITLE -> kept.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            ListSort.AUTHOR -> kept.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.author.orEmpty().ifEmpty { "￿" } })
            // Unknown lengths go last either way.
            ListSort.SHORTEST -> kept.sortedBy { it.durationSec?.takeIf { d -> d > 0 } ?: Long.MAX_VALUE }
            ListSort.LONGEST -> kept.sortedByDescending { it.durationSec?.takeIf { d -> d > 0 } ?: -1L }
        }
        return folders + sorted
    }
}
