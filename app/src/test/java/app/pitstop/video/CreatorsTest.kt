package app.pitstop.video

import app.pitstop.video.core.CREATOR_FEED_PREFIX
import app.pitstop.video.core.FOLLOWED_WORKS_FEED
import app.pitstop.video.core.FollowedWorks
import app.pitstop.video.core.SAVED_CREATORS_FEED
import app.pitstop.video.core.followTarget
import app.pitstop.video.core.followedWorks
import app.pitstop.video.core.feedsFor
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
    fun savedCreatorsAreOneCategoryOnlyForTheirSource(): Unit = runBlocking {
        val f = File.createTempFile("creators", ".json").apply { delete() }
        val store = CreatorStore(f).apply { toggle(ychan) }
        val yt = FakeSource(SourceId.YOUTUBE, creators = true)
        // Platforms with creators always show the follow lists first, then their own feeds.
        assertEquals(listOf(FOLLOWED_WORKS_FEED, SAVED_CREATORS_FEED, "popular"), yt.feedsWith(store, "Saved", worksTitle = "Works").map { it.key })
        val creatorFeeds = store.feedsFor(SourceId.YOUTUBE)
        assertEquals(listOf(CREATOR_FEED_PREFIX + ychan.id), creatorFeeds.map { it.key })
        assertEquals("creator videos", yt.loadFeed(creatorFeeds[0]).single().title)
        assertEquals(3, FakeSource(SourceId.BILIBILI, creators = true).feedsWith(store, "Saved", worksTitle = "Works").size)
        assertEquals(1, FakeSource(SourceId.YOUTUBE, creators = false).feedsWith(store, "Saved", worksTitle = "Works").size)
        f.delete()
    }

    @Test fun interleaveTakesTheNewestOfEachCreatorFirst() {
        fun v(id: String) = VideoItem(SourceId.YOUTUBE, id, id)
        val mixed = FollowedWorks.interleave(listOf(listOf(v("a1"), v("a2"), v("a3")), listOf(v("b1")), listOf(v("c1"), v("a1"))), max = 5)
        assertEquals(listOf("a1", "b1", "c1", "a2", "a3"), mixed.map { it.id })
        assertEquals(2, FollowedWorks.interleave(listOf(listOf(v("x"), v("y"), v("z"))), max = 2).size)
        assertEquals(emptyList<VideoItem>(), FollowedWorks.interleave(emptyList()))
    }

    @Test fun followedWorksMixesEveryFollowedCreator(): Unit = runBlocking {
        val f = File.createTempFile("creators", ".json").apply { delete() }
        val store = CreatorStore(f).apply {
            toggle(Creator(SourceId.YOUTUBE, "c1", "One"))
            toggle(Creator(SourceId.YOUTUBE, "c2", "Two"))
            toggle(Creator(SourceId.BILIBILI, "other", "Elsewhere"))
        }
        val works = FakeSource(SourceId.YOUTUBE, creators = true).followedWorks(store)
        assertEquals(listOf("c1-new", "c2-new", "c1-old", "c2-old"), works.map { it.id })
        f.delete()
    }

    @Test fun followTargetIsTheCreatorOnlyWhereThereIsOne() {
        val yt = FakeSource(SourceId.YOUTUBE, creators = true)
        val video = VideoItem(SourceId.YOUTUBE, "v", "Title", author = "Channel", authorId = "https://youtube.com/channel/x")
        assertEquals(Creator(SourceId.YOUTUBE, "https://youtube.com/channel/x", "Channel"), yt.followTarget(video))
        assertEquals(null, yt.followTarget(video.copy(authorId = null)))
        assertEquals(null, FakeSource(SourceId.LOCAL, creators = false).followTarget(video))
    }

    private class FakeSource(override val id: SourceId, creators: Boolean) : VideoSource {
        override val displayName = "fake"
        override val supportsCreators = creators
        override fun feeds() = listOf(Feed("popular", "Popular"))
        override suspend fun load(feed: Feed) = listOf(VideoItem(id, "1", "feed"))
        override suspend fun search(query: String) = emptyList<VideoItem>()
        override suspend fun resolve(item: VideoItem) = PlayableStream("x")
        override suspend fun creatorVideos(authorId: String) =
            if (authorId.startsWith("c")) listOf(VideoItem(id, "$authorId-new", "new"), VideoItem(id, "$authorId-old", "old"))
            else listOf(VideoItem(id, "2", "creator videos"))
    }
}
