package app.pitstop.video

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.SourceId
import app.pitstop.video.youtube.InnertubeParse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnertubeParseTest {
    // Trimmed shapes of youtubei/v1/browse answers (WEB client).
    private val subscriptions = """{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}},
      "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[
        {"richItemRenderer":{"content":{"videoRenderer":{"videoId":"aaa111","title":{"runs":[{"text":"First video"}]},
          "ownerText":{"runs":[{"text":"Chan A","navigationEndpoint":{"browseEndpoint":{"browseId":"UCaaaa"}}}]},
          "lengthText":{"simpleText":"12:34"},
          "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/aaa111/small.jpg","width":168},{"url":"https://i.ytimg.com/vi/aaa111/big.jpg","width":720}]}}}}},
        {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"bbb222","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
          "contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/bbb222/hq.jpg","width":480}]},
            "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"1:02:03"}}]}}]}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"Second &amp; video"},
            "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Chan B",
              "commandRuns":[{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"UCbbbb"}}}}]}}]},
              {"metadataParts":[{"text":{"content":"3 days ago"}}]}]}}}}}}}},
        {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"PLx","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST"}}}},
        {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT_TOKEN","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}}}
      ]}}}}]}}}"""

    @Test
    fun parsesClassicAndLockupVideos() {
        val items = InnertubeParse.videos(JSONObject(subscriptions), SourceId.YOUTUBE)
        assertEquals(listOf("https://www.youtube.com/watch?v=aaa111", "https://www.youtube.com/watch?v=bbb222"), items.map { it.id })
        val a = items[0]
        assertEquals("First video", a.title)
        assertEquals("Chan A", a.author)
        assertEquals(754L, a.durationSec)
        assertEquals("https://i.ytimg.com/vi/aaa111/big.jpg", a.thumbnailUrl)
        assertEquals("https://www.youtube.com/channel/UCaaaa", a.authorId)
        val b = items[1]
        assertEquals("Chan B", b.author)
        assertEquals(3723L, b.durationSec)
        assertEquals("https://www.youtube.com/channel/UCbbbb", b.authorId)
    }

    @Test
    fun findsContinuationAndLoginState() {
        val root = JSONObject(subscriptions)
        assertEquals("NEXT_TOKEN", InnertubeParse.continuation(root))
        assertFalse(InnertubeParse.loggedOut(root))
        assertTrue(InnertubeParse.loggedOut(JSONObject("""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":true}}}""")))
        assertNull(InnertubeParse.continuation(JSONObject("""{"contents":{}}""")))
    }

    @Test
    fun parsesPlaylistRowsSkippingUnplayable() {
        val json = JSONObject("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[
          {"playlistVideoRenderer":{"videoId":"ccc333","title":{"runs":[{"text":"Song"}]},
            "shortBylineText":{"runs":[{"text":"Artist - Topic"}]},"lengthSeconds":"215","isPlayable":true}},
          {"playlistVideoRenderer":{"videoId":"ddd444","title":{"simpleText":"[Deleted video]"},"isPlayable":false}}]}}]}""")
        val items = InnertubeParse.videos(json, SourceId.YT_MUSIC)
        assertEquals(1, items.size)
        assertEquals("Artist", items[0].author)
        assertEquals(215L, items[0].durationSec)
        assertEquals(SourceId.YT_MUSIC, items[0].source)
    }

    @Test
    fun parsesPlaylistsPage() {
        val json = JSONObject("""{"contents":[
          {"lockupViewModel":{"contentId":"PL123","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
            "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
              "image":{"sources":[{"url":"https://i.ytimg.com/pl.jpg","width":336}]},
              "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"1,204 videos"}}]}}]}}}},
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Road trip"}}}}},
          {"gridPlaylistRenderer":{"playlistId":"PL456","title":{"runs":[{"text":"Old style"}]},"videoCountText":{"runs":[{"text":"7"},{"text":" videos"}]}}}]}""")
        val items = InnertubeParse.playlists(json, SourceId.YT_MUSIC) { "$it tracks" }
        assertEquals(listOf(COLLECTION_PREFIX + "PL123", COLLECTION_PREFIX + "PL456"), items.map { it.id })
        assertEquals("Road trip", items[0].title)
        assertEquals("1204 tracks", items[0].author)
        assertEquals("https://i.ytimg.com/pl.jpg", items[0].thumbnailUrl)
        assertEquals("7 tracks", items[1].author)
    }

    @Test
    fun parsesChannels() {
        val json = JSONObject("""{"items":[{"channelRenderer":{"channelId":"UC1","title":{"simpleText":"One"}}},
          {"channelRenderer":{"channelId":"UC2","title":{"simpleText":"Two"}}},{"channelRenderer":{"channelId":"bad"}}]}""")
        assertEquals(listOf("UC1" to "One", "UC2" to "Two"), InnertubeParse.channels(json))
    }

    @Test
    fun sapisidHashMatchesKnownValue() {
        // sha1("1700000000 abc/DEF https://www.youtube.com")
        val h = InnertubeParse.sapisidHash("abc/DEF", "https://www.youtube.com", 1_700_000_000)
        assertTrue(h.startsWith("SAPISIDHASH 1700000000_"))
        assertEquals(40, h.substringAfter('_').length)
        val expected = java.security.MessageDigest.getInstance("SHA-1")
            .digest("1700000000 abc/DEF https://www.youtube.com".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("SAPISIDHASH 1700000000_$expected", h)
    }

    @Test
    fun parsesRssFeed() {
        val xml = """<?xml version="1.0"?><feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">
          <title>Chan</title>
          <entry><id>yt:video:vid1</id><yt:videoId>vid1</yt:videoId><yt:channelId>UCchan</yt:channelId>
            <title>Tom &amp; Jerry</title><author><name>Chan &amp; Co</name><uri>https://www.youtube.com/channel/UCchan</uri></author>
            <published>2026-11-10T12:00:00+00:00</published></entry>
          <entry><yt:videoId>vid2</yt:videoId><title>Older</title><published>2026-11-01T12:00:00+00:00</published></entry>
        </feed>"""
        val entries = InnertubeParse.rss(xml, SourceId.YOUTUBE)
        assertEquals(2, entries.size)
        assertEquals("Tom & Jerry", entries[0].item.title)
        assertEquals("Chan & Co", entries[0].item.author)
        assertEquals("https://www.youtube.com/channel/UCchan", entries[0].item.authorId)
        assertEquals("2026-11-10T12:00:00+00:00", entries[0].published)
        assertEquals("https://www.youtube.com/watch?v=vid2", entries[1].item.id)
    }

    @Test
    fun clockParsing() {
        assertEquals(65L, InnertubeParse.clock("1:05"))
        assertEquals(3600L, InnertubeParse.clock("1:00:00"))
        assertNull(InnertubeParse.clock("LIVE"))
    }
}
