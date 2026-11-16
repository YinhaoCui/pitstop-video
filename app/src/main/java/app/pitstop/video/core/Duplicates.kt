package app.pitstop.video.core

/**
 * Finds items in a list (a playlist) that are probably the same song or video, so the user can remove
 * the extra copies. Pure, so it's unit-tested.
 *
 * Two items are grouped when they are the same item (source, id, part) or their [similarityKey]s match:
 * the title without tags in brackets ("(Official Video)", "[MV]", "【4K】"), a file extension or
 * punctuation, with the artist in front when the title doesn't already carry one ("Adele - Hello").
 */
object Duplicates {
    /** One group: positions in the list, first one kept; [exact] are the ones that are certainly copies of it. */
    data class Group(val indices: List<Int>, val exact: Set<Int>)

    private val BRACKETS = Regex("""[(\[【（《「][^)\]】）》」]*[)\]】）》」]""")
    private val EXTENSION = Regex("""\.(mp3|m4a|aac|flac|wav|ogg|oga|opus|wma|aiff?|alac|ape|mka|amr|mp4|mkv|webm)$""", RegexOption.IGNORE_CASE)
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val CHANNEL_SUFFIX = Regex("""(vevo|\s*-\s*topic|\s*official|官方)$""", RegexOption.IGNORE_CASE)

    private fun words(text: String) = text.lowercase().replace(NOT_WORD, " ").trim().replace(Regex(" +"), " ")

    /** Title as written, file extension dropped: two copies with this equal are certainly the same. */
    fun exactKey(item: VideoItem): String = words(item.title.replace(EXTENSION, ""))

    /** Looser key for "probably the same": tags in brackets dropped, artist added when missing. */
    fun similarityKey(item: VideoItem): String {
        val title = item.title.replace(EXTENSION, "")
        val bare = words(title.replace(BRACKETS, " ")).ifEmpty { words(title) }
        // "Artist - Title" already names the artist; otherwise take it from the creator, unless that is
        // a file type ("MP3" for Drive songs) or a channel decoration ("AdeleVEVO", "Adele - Topic").
        if (title.contains(" - ")) return bare
        val author = item.author?.substringBefore(" · ")?.trim().orEmpty()
        val isFileType = author.length <= 5 && author.uppercase() == author && author.none { it.isWhitespace() }
        if (author.isEmpty() || isFileType) return bare
        val artist = words(author.replace(CHANNEL_SUFFIX, ""))
        return if (artist.isEmpty() || bare.startsWith(artist)) bare else "$artist $bare"
    }

    private fun sameItem(a: VideoItem, b: VideoItem) = a.source == b.source && a.id == b.id && a.part == b.part

    /** Groups of 2+ similar items, in list order; each group's first item is the one to keep. */
    fun groups(items: List<VideoItem>): List<Group> {
        val byKey = LinkedHashMap<String, MutableList<Int>>()
        items.forEachIndexed { i, item ->
            // An exact copy of an earlier item always joins that item's group.
            val earlier = items.subList(0, i).indexOfFirst { sameItem(it, item) }
            val key = if (earlier >= 0) similarityKey(items[earlier]) else similarityKey(item)
            byKey.getOrPut(key.ifEmpty { "#$i" }) { mutableListOf() } += i
        }
        return byKey.values.filter { it.size > 1 }.map { idx ->
            val first = items[idx.first()]
            val exact = idx.drop(1).filterTo(LinkedHashSet()) { sameItem(items[it], first) || exactKey(items[it]) == exactKey(first) }
            Group(idx, exact)
        }
    }
}
