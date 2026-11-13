package app.pitstop.video

import app.pitstop.video.core.CREATOR_FEED_PREFIX
import app.pitstop.video.core.Creator
import app.pitstop.video.core.CreatorCodec
import app.pitstop.video.core.CreatorStore
import app.pitstop.video.core.Feed
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.feedsWith
import app.pitstop.video.core.loadFeed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CreatorsTest {
    private val ychan = Creator(SourceId.YOUTUBE, "https://www.youtube.com/channel/UC1", "Chan")

    @Test
    fun codecRoundTripSkipsUnknownSources() {
        val text = CreatorCodec.encode(listOf(ychan, Creator(SourceId.BILIBILI, "123", "UP主")))
        assertEquals(2, CreatorCodec.decode(text).size)
        val withJunk = text.replace("BILIBILI", "NOT_A_SOURCE")
        assertEquals(listOf(ychan), CreatorCodec.decode(withJunk))
    }

    @Test
    fun toggleSavesAndPersists() {
        val f = File.createTempFile("creators", ".json").apply { delete() }
        val store = CreatorStore(f)
        store.toggle(ychan)
        assertTrue(store.isSaved(SourceId.YOUTUBE, ychan.id))
        assertTrue(CreatorStore(f).isSaved(SourceId.YOUTUBE, ychan.id)) // reloaded from disk
        store.toggle(ychan)
        assertFalse(store.isSaved(SourceId.YOUTUBE, ychan.id))
        f.delete()
    }

    @Test
    fun savedCreatorsBecomeFeedsOnlyForTheirSource(): Unit = runBlocking {
        val f = File.createTempFile("creators", ".json").apply { delete() }
        val store = CreatorStore(f).apply { toggle(ychan) }
        val yt = FakeSource(SourceId.YOUTUBE, creators = true)
        val feeds = yt.feedsWith(store)
        assertEquals(listOf("popular", CREATOR_FEED_PREFIX + ychan.id), feeds.map { it.key })
        assertEquals("creator videos", yt.loadFeed(feeds[1]).single().title)
        assertEquals(1, FakeSource(SourceId.BILIBILI, creators = true).feedsWith(store).size)
        assertEquals(1, FakeSource(SourceId.YOUTUBE, creators = false).feedsWith(store).size)
        f.delete()
    }

    private class FakeSource(override val id: SourceId, creators: Boolean) : VideoSource {
        override val displayName = "fake"
        override val supportsCreators = creators
        override fun feeds() = listOf(Feed("popular", "Popular"))
        override suspend fun load(feed: Feed) = listOf(VideoItem(id, "1", "feed"))
        override suspend fun search(query: String) = emptyList<VideoItem>()
        override suspend fun resolve(item: VideoItem) = PlayableStream("x")
        override suspend fun creatorVideos(authorId: String) = listOf(VideoItem(id, "2", "creator videos"))
    }
}
