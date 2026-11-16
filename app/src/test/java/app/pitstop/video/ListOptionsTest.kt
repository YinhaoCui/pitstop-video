package app.pitstop.video

import app.pitstop.video.core.LengthFilter
import app.pitstop.video.core.ListSort
import app.pitstop.video.core.ListView
import app.pitstop.video.core.Option
import app.pitstop.video.core.OptionGroup
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.normalized
import app.pitstop.video.youtube.YtSearchParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListOptionsTest {
    private fun v(title: String, author: String?, dur: Long?, source: SourceId = SourceId.YOUTUBE) =
        VideoItem(source, title, title, author, durationSec = dur)

    private val items = listOf(
        v("banana", "Zed", 300), v("Apple", "amy", 60), v("cherry", null, null), v("date", "Bob", 1800, SourceId.BILIBILI),
    )

    @Test fun defaultViewKeepsTheListAsIs() {
        assertTrue(ListView().isDefault)
        assertEquals(items, ListView().apply(items))
    }

    @Test fun sortsUnknownLengthsLast() {
        assertEquals(listOf("Apple", "banana", "cherry", "date"), ListView(sort = ListSort.TITLE).apply(items).map { it.title })
        assertEquals(listOf("Apple", "banana", "date", "cherry"), ListView(sort = ListSort.SHORTEST).apply(items).map { it.title })
        assertEquals(listOf("date", "banana", "Apple", "cherry"), ListView(sort = ListSort.LONGEST).apply(items).map { it.title })
        assertEquals(listOf("Apple", "date", "banana", "cherry"), ListView(sort = ListSort.AUTHOR).apply(items).map { it.title })
    }

    @Test fun filtersByLengthTextAndPlatformButKeepsFolders() {
        assertEquals(listOf("Apple"), ListView(length = LengthFilter.SHORT).apply(items).map { it.title })
        assertEquals(listOf("banana"), ListView(length = LengthFilter.MEDIUM).apply(items).map { it.title })
        assertEquals(listOf("date"), ListView(length = LengthFilter.LONG).apply(items).map { it.title })
        assertEquals(listOf("banana"), ListView(text = "zed").apply(items).map { it.title })
        assertEquals(listOf("date"), ListView(platforms = setOf(SourceId.BILIBILI)).apply(items).map { it.title })
        val folder = v("list:x", "folder", null)
        assertEquals(listOf("list:x", "Apple"), ListView(length = LengthFilter.SHORT).apply(listOf(folder) + items) { it.title.startsWith("list:") }.map { it.title })
    }

    @Test fun pickedDropsDefaults() {
        val groups = listOf(OptionGroup("sort", "Sort", listOf(Option("0", "Rel"), Option("2", "New"))))
        assertEquals(emptyMap<String, String>(), mapOf("sort" to "0").normalized(groups))
        assertEquals(mapOf("sort" to "2"), mapOf("sort" to "2", "junk" to "1").normalized(groups))
    }

    @Test fun youtubeSearchParamsMatchTheSite() {
        assertEquals("EgIQAQ==", YtSearchParams.encode(0, 0, 0))   // videos
        assertEquals("CAISAhAB", YtSearchParams.encode(2, 0, 0))   // newest videos
        assertEquals("EgQIAhAB", YtSearchParams.encode(0, 2, 0))   // today, videos
        assertEquals("EgQQARgB", YtSearchParams.encode(0, 0, 1))   // under 4 min, videos
    }
}
