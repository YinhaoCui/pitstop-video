package app.pitstop.video

import app.pitstop.video.core.SourceId
import app.pitstop.video.core.SourceId.*
import app.pitstop.video.core.fullSourceOrder
import app.pitstop.video.core.moveSource
import app.pitstop.video.core.orderedSources
import app.pitstop.video.core.sameBar
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformOrderTest {
    private val video = listOf(YOUTUBE, BILIBILI, PEERTUBE, LOCAL)

    @Test fun noSavedOrderKeepsTheDefault() {
        assertEquals(video, orderedSources(video, emptyList()) { it })
        assertEquals(SourceId.entries, fullSourceOrder(emptyList()))
    }

    @Test fun followsTheSavedOrder() {
        assertEquals(listOf(LOCAL, BILIBILI, YOUTUBE, PEERTUBE),
            orderedSources(video, listOf(LOCAL, BILIBILI, YOUTUBE, PEERTUBE)) { it })
    }

    @Test fun platformsMissingFromTheOrderGoLastInDefaultOrder() {
        assertEquals(listOf(LOCAL, YOUTUBE, BILIBILI, PEERTUBE), orderedSources(video, listOf(LOCAL)) { it })
    }

    @Test fun otherBarsInTheOrderDontMatter() {
        assertEquals(listOf(BILIBILI, YOUTUBE, PEERTUBE, LOCAL),
            orderedSources(video, listOf(NETEASE, BILIBILI, TWITCH)) { it })
    }

    @Test fun fullOrderHasEveryPlatformOnce() {
        val full = fullSourceOrder(listOf(TWITCH, TWITCH, LOCAL))
        assertEquals(SourceId.entries.size, full.size)
        assertEquals(SourceId.entries.toSet(), full.toSet())
        assertEquals(listOf(TWITCH, LOCAL), full.take(2))
    }

    @Test fun moveUpSwapsWithThePreviousPlatformOfTheSameBar() {
        // NETEASE (music) sits between BILIBILI and LOCAL, but only video platforms swap.
        val order = listOf(YOUTUBE, BILIBILI, NETEASE, LOCAL, PEERTUBE)
        val moved = moveSource(order, LOCAL, -1) { LOCAL.sameBar(it) }
        assertEquals(listOf(YOUTUBE, LOCAL, NETEASE, BILIBILI, PEERTUBE), moved.take(5))
        assertEquals(listOf(YOUTUBE, LOCAL, BILIBILI, PEERTUBE), orderedSources(video, moved) { it })
    }

    @Test fun moveDown() {
        val moved = moveSource(emptyList(), YOUTUBE, 1) { YOUTUBE.sameBar(it) }
        assertEquals(listOf(BILIBILI, YOUTUBE, PEERTUBE, LOCAL), orderedSources(video, moved) { it })
    }

    @Test fun movingPastTheEndChangesNothing() {
        assertEquals(SourceId.entries, moveSource(emptyList(), YOUTUBE, -1) { YOUTUBE.sameBar(it) })
        assertEquals(SourceId.entries, moveSource(emptyList(), LOCAL, 1) { LOCAL.sameBar(it) })
    }

    @Test fun barsAreVideoMusicAndLive() {
        assert(YOUTUBE.sameBar(LOCAL))
        assert(!YOUTUBE.sameBar(NETEASE))
        assert(!YOUTUBE.sameBar(TWITCH))
        assert(TWITCH.sameBar(DOUYU))
    }
}
