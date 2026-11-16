package app.pitstop.video

import app.pitstop.video.core.Feed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.loadFeedPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedPagingTest {
    private fun v(id: String) = VideoItem(SourceId.BILIBILI, id, id)

    /** A source without paging: one page, no cursor. */
    private val single = object : VideoSource {
        override val id = SourceId.BILIBILI
        override val displayName = "test"
        override fun feeds() = listOf(Feed("f", "F"))
        override suspend fun load(feed: Feed) = listOf(v("a"), v("b"))
        override suspend fun search(query: String) = emptyList<VideoItem>()
        override suspend fun resolve(item: VideoItem) = PlayableStream(item.id)
        override val supportsCreators get() = true
        override suspend fun creatorVideos(authorId: String) = listOf(v("c-$authorId"))
    }

    @Test fun plainFeedIsOnePage() = runBlocking {
        val first = single.loadFeedPage(Feed("f", "F"), null)
        assertEquals(listOf("a", "b"), first.items.map { it.id })
        assertNull(first.next)
        assertEquals(FeedPage(emptyList()), single.loadFeedPage(Feed("f", "F"), "anything"))
    }

    @Test fun creatorFeedIsOnePage() = runBlocking {
        val first = single.loadFeedPage(Feed("creator:42", "Someone"), null)
        assertEquals(listOf("c-42"), first.items.map { it.id })
        assertNull(first.next)
    }
}
