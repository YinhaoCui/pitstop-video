package app.pitstop.video

import app.pitstop.video.core.Creator
import app.pitstop.video.core.FollowedRoom
import app.pitstop.video.core.LiveFollow
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.creatorsOf
import app.pitstop.video.core.VideoItem
import app.pitstop.video.player.HubState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFollowTest {
    private fun room(id: String, live: Boolean, account: Boolean = false) =
        FollowedRoom(Creator(SourceId.DOUYU, id, "n$id"), null, live, account)

    @Test fun streamerFromALiveItem() {
        val item = VideoItem(SourceId.DOUYU, "9999", "陪伴每一天！", author = "yyfyyf · 487.6万人观看", authorId = "9999")
        assertEquals(Creator(SourceId.DOUYU, "9999", "yyfyyf"), LiveFollow.creatorOf(item))
        // No author id: the room id stands in. No author line: the title does.
        assertEquals(Creator(SourceId.TWITCH, "shroud", "title"), LiveFollow.creatorOf(VideoItem(SourceId.TWITCH, "shroud", "title")))
        // Videos and songs have creators, not streamers to follow.
        assertNull(LiveFollow.creatorOf(VideoItem(SourceId.YOUTUBE, "v", "t", author = "a", authorId = "c")))
    }

    @Test fun starredRoomsBecomeFollowsOnce() {
        val starred = listOf(
            VideoItem(SourceId.DOUYU, "1", "t", author = "A · 5 watching", authorId = "1"),
            VideoItem(SourceId.DOUYU, "1", "t again", author = "A", authorId = "1"),
            VideoItem(SourceId.BILI_LIVE, "22", "t", author = "B", authorId = "777"),
            VideoItem(SourceId.NETEASE, "song", "t", author = "C"),
            VideoItem(SourceId.TWITCH, "x", "t", author = "X", authorId = "x"),
        )
        val followed = listOf(Creator(SourceId.TWITCH, "x", "X"))
        assertEquals(
            listOf(Creator(SourceId.DOUYU, "1", "A"), Creator(SourceId.BILI_LIVE, "777", "B")),
            creatorsOf(starred, followed, LiveFollow::creatorOf),
        )
    }

    @Test fun mergePutsLiveFirstAndPrefersTheAppsOwnFollow() {
        val merged = LiveFollow.merge(
            local = listOf(room("1", live = false), room("2", live = true)),
            account = listOf(room("2", live = true, account = true), room("3", live = true, account = true), room("4", live = false, account = true)),
        )
        assertEquals(listOf("2", "3", "1", "4"), merged.map { it.creator.id })
        // Followed in both places: counts as the app's follow, so it can be unfollowed here.
        assertFalse(merged.first { it.creator.id == "2" }.fromAccount)
        assertTrue(merged.first { it.creator.id == "3" }.fromAccount)
    }

    @Test fun streamersThePlatformDidNotReportAreOffline() {
        val creators = listOf(Creator(SourceId.TWITCH, "a", "A"), Creator(SourceId.TWITCH, "gone", "Gone"))
        val rooms = LiveFollow.inOrder(creators, mapOf("a" to FollowedRoom(creators[0], null, live = true)))
        assertEquals(listOf(true, false), rooms.map { it.live })
        assertEquals("Gone", rooms[1].creator.name)
    }

    @Test fun onlyAStreamOnItsOwnHidesTheQueue() {
        val stream = VideoItem(SourceId.DOUYU, "1", "t")
        val song = VideoItem(SourceId.NETEASE, "s", "t")
        assertTrue(HubState(queue = listOf(stream), index = 0).soloLive)
        // A stream inside a queue the user built keeps the queue.
        assertFalse(HubState(queue = listOf(stream, song), index = 0).soloLive)
        assertFalse(HubState(queue = listOf(song), index = 0).soloLive)
        assertFalse(HubState().soloLive)
    }
}
