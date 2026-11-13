package app.pitstop.video

import app.pitstop.video.core.Feed
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.folderTracks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTracksTest {
    private fun dir(id: String) = VideoItem(SourceId.GDRIVE, "list:$id", id)
    private fun song(id: String) = VideoItem(SourceId.GDRIVE, id, id)

    /** root: a, sub/ (b, deeper/ (c)), d — plus a loop back to root that must not recurse forever. */
    private val tree = mapOf(
        "list:root" to listOf(dir("sub"), song("a"), song("d")),
        "list:sub" to listOf(dir("deeper"), dir("root"), song("b")),
        "list:deeper" to listOf(song("c")),
    )

    private val source = object : VideoSource {
        override val id = SourceId.GDRIVE
        override val displayName = "test"
        override fun feeds() = emptyList<Feed>()
        override suspend fun load(feed: Feed) = emptyList<VideoItem>()
        override suspend fun search(query: String) = emptyList<VideoItem>()
        override suspend fun resolve(item: VideoItem) = PlayableStream(item.id)
        override fun isFolder(item: VideoItem) = item.id.startsWith("list:")
        override suspend fun browse(item: VideoItem) = tree[item.id].orEmpty()
    }

    @Test fun walksSubfoldersBreadthFirst() = runBlocking {
        assertEquals(listOf("a", "d", "b", "c"), source.folderTracks(dir("root")).map { it.id })
    }

    @Test fun stopsAtLimit() = runBlocking {
        assertEquals(listOf("a", "d"), source.folderTracks(dir("root"), limit = 2).map { it.id })
    }

    @Test fun songIsItself() = runBlocking {
        assertEquals(listOf("x"), source.folderTracks(song("x")).map { it.id })
    }
}
