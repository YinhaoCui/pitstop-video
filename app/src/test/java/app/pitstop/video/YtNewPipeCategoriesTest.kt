package app.pitstop.video

import app.pitstop.video.core.AppLanguage
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.Category
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.Strings
import app.pitstop.video.core.asFeed
import app.pitstop.video.core.categoryKey
import app.pitstop.video.newpipe.BandcampDiscover
import app.pitstop.video.newpipe.CategoryKeys
import app.pitstop.video.newpipe.PageStore
import app.pitstop.video.newpipe.PeerTubeApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtNewPipeCategoriesTest {
    private val en = Strings(AppLanguage.EN)
    private val zh = Strings(AppLanguage.ZH)

    @Test fun pageStoreKeepsAndBounds() {
        val store = PageStore<Int>(max = 3)
        val a = store.keep(1)
        val b = store.keep(2)
        assertNotEquals(a, b)
        assertEquals(1, store[a])
        assertEquals(1, store[a]) // re-reading a cursor works (retry after a failed page)
        store.keep(3); store.keep(4) // b is the least recently used now
        assertEquals(3, store.size)
        assertNull(store[b])
        assertEquals(1, store[a])
        assertNull(store["unknown"])
    }

    @Test fun kioskOrderPutsDefaultFirst() {
        assertEquals(
            listOf("Trending", "live", "trending_gaming", "trending_music"),
            CategoryKeys.kioskOrder(setOf("trending_music", "live", "Trending", "trending_gaming"), "Trending"),
        )
    }

    @Test fun youtubeKiosksAndTopicsHaveTitlesAndQueries() {
        val kiosks = listOf("Trending", "live", "trending_gaming", "trending_movies_and_shows", "trending_music", "trending_podcasts_episodes")
        for (k in kiosks) {
            assertNotNull(k, en.ytCategory(k)); assertNotNull(k, zh.ytCategory(k))
            val follow = CategoryKeys.ytKioskFollowUp(k)
            assertTrue(en.ytTopicQuery(follow).isNotBlank())
            assertNotEquals(follow, zh.ytTopicQuery(follow)) // a real query, not the raw key
        }
        val topics = CategoryKeys.named(CategoryKeys.YT_TOPICS, zh::ytCategory, zh.catTopics) { CategoryKeys.TOPIC + it }
        assertEquals(CategoryKeys.YT_TOPICS.size, topics.size)
        assertTrue(topics.all { it.key.startsWith(CategoryKeys.TOPIC) && it.group == "主题" })
        for (t in CategoryKeys.YT_TOPICS) {
            assertTrue(en.ytTopicQuery(t).isNotBlank()) // the English query may equal the key ("comedy")
            assertNotEquals(en.ytTopicQuery(t), zh.ytTopicQuery(t))
        }
        // A picked category round-trips through its feed key.
        val c = topics.first()
        assertEquals(c.key, c.asFeed().categoryKey())
        assertEquals("news", c.asFeed().categoryKey()!!.removePrefix(CategoryKeys.TOPIC))
    }

    @Test fun everyGenreListIsFullyNamed() {
        val keys = CategoryKeys.MUSIC_GENRES + CategoryKeys.MUSIC_MOODS + CategoryKeys.SOUNDCLOUD_GENRES +
            CategoryKeys.BANDCAMP_TAGS.map { it.second }
        for (k in keys) {
            assertNotNull(k, en.musicGenre(k)); assertNotNull(k, zh.musicGenre(k))
            assertTrue(k, en.musicGenreQuery(k).isNotBlank())
        }
        for (list in listOf(CategoryKeys.MUSIC_GENRES, CategoryKeys.MUSIC_MOODS, CategoryKeys.SOUNDCLOUD_GENRES, CategoryKeys.BANDCAMP_TAGS.map { it.first })) {
            assertEquals("duplicate keys", list.size, list.toSet().size)
        }
        val named = CategoryKeys.named(listOf("pop", "nope"), en::musicGenre, en.catGenres)
        assertEquals(listOf(Category("pop", "Pop", "Genres")), named)
    }

    @Test fun peerTubeCategoriesParseInIdOrder() {
        val json = """{"1":"Music","2":"Films","10":"Entertainment","3":"Vehicles","18":"Food"}"""
        val cats = PeerTubeApi.parseCategories(json)
        assertEquals(listOf("1", "2", "3", "10", "18"), cats.map { it.key })
        assertEquals("Vehicles", cats[2].title)
        assertEquals(
            "https://framatube.org/api/v1/videos?categoryOneOf=15&start=24&count=24&sort=-publishedAt&nsfw=false",
            PeerTubeApi.videosUrl("https://framatube.org/", "15", 24),
        )
    }

    /** Trimmed from a real https://framatube.org/api/v1/videos?categoryOneOf=1 response. */
    private val peerTubeVideos = """
        {"total":2319,"data":[
          {"id":76072,"uuid":"3761ed93-9341-47cc-8620-ec6431b06435","name":"Live set",
           "url":"https://tube.tchncs.de/videos/watch/3761ed93-9341-47cc-8620-ec6431b06435","duration":25386,
           "isLive":false,"thumbnailPath":"/lazy-static/thumbnails/ff1de714.jpg",
           "thumbnails":[{"height":480,"width":850,"fileUrl":"https://framatube.org/lazy-static/thumbnails/850.png"},
                         {"height":157,"width":280,"fileUrl":"https://framatube.org/lazy-static/thumbnails/280.png"},
                         {"height":720,"width":1280,"fileUrl":"https://framatube.org/lazy-static/thumbnails/1280.png"}],
           "account":{"id":105443,"displayName":"Pele","name":"pele","host":"tube.tchncs.de"}},
          {"id":2,"uuid":"0a0c6237-4ea0-4996-820c-5dbd27c7f2d2","name":"No thumbs","duration":0,
           "thumbnailPath":"/lazy-static/thumbnails/f2.png","account":{"displayName":"","name":"x","host":"framatube.org"}},
          {"id":3,"name":"missing uuid"}
        ]}
    """.trimIndent()

    @Test fun peerTubeVideosParse() {
        val (items, total) = PeerTubeApi.parseVideos(peerTubeVideos, "https://framatube.org")
        assertEquals(2319, total)
        assertEquals(2, items.size)
        val a = items[0]
        assertEquals(SourceId.PEERTUBE, a.source)
        assertEquals("https://framatube.org/videos/watch/3761ed93-9341-47cc-8620-ec6431b06435", a.id)
        assertEquals("Pele", a.author)
        assertEquals("https://framatube.org/accounts/pele@tube.tchncs.de", a.authorId)
        assertEquals("https://framatube.org/lazy-static/thumbnails/850.png", a.thumbnailUrl)
        assertEquals(25386L, a.durationSec)
        val b = items[1]
        assertEquals("x", b.author)
        assertEquals("https://framatube.org/lazy-static/thumbnails/f2.png", b.thumbnailUrl)
        assertNull(b.durationSec)
    }

    @Test fun peerTubeCursor() {
        assertEquals("24", PeerTubeApi.nextCursor(0, 24, 100))
        assertNull(PeerTubeApi.nextCursor(96, 4, 100))
        assertNull(PeerTubeApi.nextCursor(24, 0, 100))
    }

    @Test fun bandcampDiscoverBodyAndParse() {
        val body = JSONObject(BandcampDiscover.body("hip-hop-rap", null))
        assertEquals("hip-hop-rap", body.getJSONArray("tag_norm_names").getString(0))
        assertEquals("*", body.getString("cursor"))
        assertEquals("a", body.getJSONArray("include_result_types").getString(0))
        assertEquals(1, body.getJSONArray("include_result_types").length())
        assertEquals("abc=", JSONObject(BandcampDiscover.body("jazz", "abc=")).getString("cursor"))

        // Trimmed from a real discover_web response.
        val json = """
            {"results":[
              {"itemId":109296858,"itemType":"a","title":"20260930 La Rosa, Tucson, AZ",
               "itemUrl":"https://stickmen.bandcamp.com/album/20260930-la-rosa-tucson-az?from=discover_page",
               "primaryImage":{"imageId":292950429,"isArt":true},"albumArtist":null,"bandName":"Stick Men",
               "bandUrl":"https://stickmen.bandcamp.com?from=discover_page","trackCount":16,"duration":6801.0},
              {"itemId":1,"itemType":"t","title":"Komodo","itemUrl":"https://stickmen.bandcamp.com/track/komodo-4",
               "primaryImage":null,"bandName":"Stick Men","bandUrl":"https://stickmen.bandcamp.com","duration":259.9}
            ],"resultCount":928153,"batchResultCount":2,"cursor":"AoMIQuoMbnC24OTUnwMrYTM2NzIyNjc5OTM="}
        """.trimIndent()
        val page = BandcampDiscover.parse(json)
        assertEquals("AoMIQuoMbnC24OTUnwMrYTM2NzIyNjc5OTM=", page.next)
        val album = page.items[0]
        assertEquals(COLLECTION_PREFIX + "https://stickmen.bandcamp.com/album/20260930-la-rosa-tucson-az", album.id)
        assertEquals("Stick Men", album.author)
        assertEquals("https://stickmen.bandcamp.com", album.authorId)
        assertEquals("https://f4.bcbits.com/img/a292950429_2.jpg", album.thumbnailUrl)
        assertNull(album.durationSec)
        val track = page.items[1]
        assertEquals("https://stickmen.bandcamp.com/track/komodo-4", track.id)
        assertEquals(259L, track.durationSec)
        assertNull(track.thumbnailUrl)

        assertNull(BandcampDiscover.parse("""{"results":[],"cursor":"x"}""").next)
    }
}
