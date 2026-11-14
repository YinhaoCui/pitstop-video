package app.pitstop.video

import app.pitstop.video.bilibili.BiliLiveParse
import app.pitstop.video.live.TwitchParse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samples trimmed from real responses (Nov 2026). */
class LiveSourcesTest {
    private val viewers: (Long) -> String = { "$it watching" }

    // --- Bilibili Live ---

    private val playInfo = JSONObject(
        """
        {"room_id":545068,"live_status":1,"playurl_info":{"playurl":{"stream":[
          {"protocol_name":"http_stream","format":[{"format_name":"flv","codec":[
            {"codec_name":"avc","current_qn":250,"accept_qn":[10000,400,250],"base_url":"/live-bvc/1/live_2500.flv?",
             "url_info":[{"host":"https://d1--ov-gotcha05.bilivideo.com","extra":"expires=1&qn=250"}]}]}]},
          {"protocol_name":"http_hls","format":[
            {"format_name":"ts","codec":[
              {"codec_name":"hevc","current_qn":250,"accept_qn":[10000,400,250],"base_url":"/live-bvc/2/live_minihevc.m3u8?",
               "url_info":[{"host":"https://d1--ov-gotcha105.bilivideo.com","extra":"expires=1"}]},
              {"codec_name":"avc","current_qn":250,"accept_qn":[10000,400,250],"base_url":"/live-bvc/3/live_2500.m3u8?",
               "url_info":[{"host":"https://xy1x2.mcdn.bilivideo.cn:486","extra":"expires=1"},
                           {"host":"https://d1--ov-gotcha105.bilivideo.com","extra":"expires=1&qn=250"}]}]},
            {"format_name":"fmp4","codec":[
              {"codec_name":"avc","current_qn":250,"accept_qn":[10000,400,250],"base_url":"/live-bvc/4/live_2500/index.m3u8?",
               "url_info":[{"host":"https://d1--ov-gotcha207.bilivideo.com","extra":"expires=1"}]}]}]}
        ]}}}
        """,
    )

    @Test fun biliPrefersHlsTsAvc() {
        val c = BiliLiveParse.chooseStream(playInfo)!!
        assertTrue(c.hls)
        assertEquals(250, c.qn)
        assertEquals(listOf(10000, 400, 250), c.acceptQn)
        assertEquals("https://d1--ov-gotcha105.bilivideo.com/live-bvc/3/live_2500.m3u8?expires=1&qn=250", c.urls[1])
    }

    @Test fun biliFallsBackToFlv() {
        val flvOnly = JSONObject(playInfo.toString())
        val streams = flvOnly.getJSONObject("playurl_info").getJSONObject("playurl").getJSONArray("stream")
        streams.remove(1)
        val c = BiliLiveParse.chooseStream(flvOnly)!!
        assertFalse(c.hls)
        assertTrue(c.urls.single().contains(".flv?"))
    }

    @Test fun biliOfflineHasNoStream() =
        assertNull(BiliLiveParse.chooseStream(JSONObject("""{"live_status":2,"playurl_info":null}""")))

    @Test fun biliQualityPicking() {
        val all = BiliLiveParse.STANDARD_QNS
        assertEquals(10000, BiliLiveParse.pickQn(all, 1080, audioOnly = false))
        assertEquals(250, BiliLiveParse.pickQn(all, 720, audioOnly = false))
        assertEquals(150, BiliLiveParse.pickQn(all, 480, audioOnly = false))
        assertEquals(80, BiliLiveParse.pickQn(all, 360, audioOnly = false))
        assertEquals(80, BiliLiveParse.pickQn(all, 1080, audioOnly = true))
        // Anonymous viewers are only offered 10000/400/250: nothing fits 480p, so take the lowest.
        assertEquals(250, BiliLiveParse.pickQn(listOf(10000, 400, 250), 480, audioOnly = false))
        assertEquals(10000, BiliLiveParse.pickQn(listOf(30000, 20000, 10000, 400), 1080, audioOnly = false))
    }

    @Test fun biliRoomCards() {
        val list = JSONArray(
            """[
              {"roomid":7734200,"uid":50329118,"title":"GEN vs BLG","uname":"赛事","online":453412,
               "cover":"https://i0.hdslb.com/bfs/live/new_room_cover/a8.jpg"},
              {"roomid":22907643,"uid":3949941,"title":"和<em class=\"keyword\">挖机</em>双排","uname":"小丑熊","online":0,
               "live_status":1,"user_cover":"//i0.hdslb.com/bfs/live/new_room_cover/cf.jpg","cover":"//i0.hdslb.com/kf.jpg"},
              {"roomid":5440,"uid":1,"title":"replay","uname":"x","live_status":2}
            ]""",
        )
        val items = BiliLiveParse.rooms(list, viewers)
        assertEquals(2, items.size)
        assertEquals("7734200", items[0].id)
        assertEquals("赛事 · 453412 watching", items[0].author)
        assertEquals("50329118", items[0].authorId)
        assertTrue(items[0].thumbnailUrl!!.startsWith("https://i0.hdslb.com/bfs/live/new_room_cover/a8.jpg"))
        assertNull(items[0].durationSec)
        assertEquals("和挖机双排", items[1].title)
        assertEquals("小丑熊", items[1].author)
        assertTrue(items[1].thumbnailUrl!!.startsWith("https://i0.hdslb.com/bfs/live/new_room_cover/cf.jpg"))
    }

    @Test fun biliFollowingKeepsOnlyLive() {
        val list = JSONArray(
            """[
              {"roomid":544853,"uid":686127,"uname":"籽岷","title":"尝试双机位","live_status":1,"text_small":"10.9万",
               "room_cover":"http://i0.hdslb.com/bfs/live/new_room_cover/6c.jpg"},
              {"roomid":21686237,"uid":456664753,"uname":"央视新闻","title":"央视新闻的直播间","live_status":0,"text_small":"0"}
            ]""",
        )
        val items = BiliLiveParse.followingLive(list)
        assertEquals(1, items.size)
        assertEquals("544853", items[0].id)
        assertEquals("籽岷 · 10.9万", items[0].author)
        assertTrue(items[0].thumbnailUrl!!.startsWith("https://"))
    }

    // --- Twitch ---

    @Test fun twitchStreams() {
        val streams = JSONObject(
            """{"edges":[
              {"node":{"title":"ALL FLIGHTS GROUNDED","viewersCount":59720,
                "previewImageURL":"https://static-cdn.jtvnw.net/previews-ttv/live_user_hasanabi-440x248.jpg",
                "broadcaster":{"login":"hasanabi","displayName":"HasanAbi"}}},
              {"node":{"title":"","viewersCount":0,"previewImageURL":null,"broadcaster":{"login":"zackrawrr","displayName":""}}},
              {"node":{"title":"orphan","broadcaster":null}}
            ]}""",
        )
        val items = TwitchParse.streams(streams, viewers)
        assertEquals(2, items.size)
        assertEquals("hasanabi", items[0].id)
        assertEquals("hasanabi", items[0].authorId)
        assertEquals("HasanAbi · 59720 watching", items[0].author)
        assertEquals("ALL FLIGHTS GROUNDED", items[0].title)
        assertTrue(items[0].thumbnailUrl!!.endsWith("-440x248.jpg"))
        assertEquals("zackrawrr", items[1].author)
        assertEquals("zackrawrr", items[1].title)
        assertNull(items[1].thumbnailUrl)
    }

    @Test fun twitchSearchSkipsOfflineChannels() {
        val searchFor = JSONObject(
            """{"channels":{"edges":[
              {"item":{"login":"minecraft","displayName":"Minecraft","stream":{"title":"Stage","viewersCount":2727,
                "previewImageURL":"https://static-cdn.jtvnw.net/previews-ttv/live_user_minecraft-440x248.jpg"}}},
              {"item":{"login":"shroud","displayName":"shroud","stream":null}},
              {"item":null}
            ]}}""",
        )
        val items = TwitchParse.searchResults(searchFor, viewers)
        assertEquals(listOf("minecraft"), items.map { it.id })
    }

    @Test fun twitchGameNames() = assertEquals(
        listOf("Just Chatting", "World of Warcraft"),
        TwitchParse.gameNames(JSONObject("""{"edges":[{"node":{"name":"Just Chatting"}},{"node":{"name":"World of Warcraft"}}]}""")),
    )

    @Test fun twitchUsherUrl() {
        val url = TwitchParse.usherUrl("HasanAbi", "abc123", """{"channel":"hasanabi"}""", 42)
        assertTrue(url.startsWith("https://usher.ttvnw.net/api/v2/channel/hls/hasanabi.m3u8?platform=web&p=42&"))
        assertTrue("allow_audio_only=true" in url)
        assertTrue("sig=abc123" in url)
        assertTrue("token=%7B%22channel%22%3A%22hasanabi%22%7D" in url)
    }

    @Test fun twitchQueriesAreValidJson() {
        val q = TwitchParse.accessTokenQuery("hasanabi")
        assertEquals("hasanabi", q.getJSONObject("variables").getString("login"))
        assertTrue(q.getString("query").contains("streamPlaybackAccessToken(channelName:\$login"))
        assertEquals("Marvel Rivals", TwitchParse.gameStreamsQuery("Marvel Rivals").getJSONObject("variables").getString("name"))
    }

    @Test fun twitchAudioOnlyVariant() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)",AUTOSELECT=YES,DEFAULT=YES
            #EXT-X-STREAM-INF:BANDWIDTH=9009771,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
            https://use23.playlist.ttvnw.net/v1/playlist/source.m3u8
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="audio_only",NAME="audio_only",AUTOSELECT=NO,DEFAULT=NO
            #EXT-X-STREAM-INF:BANDWIDTH=160000,CODECS="mp4a.40.2",VIDEO="audio_only"
            https://use23.playlist.ttvnw.net/v1/playlist/audio.m3u8
        """.trimIndent()
        assertEquals("https://use23.playlist.ttvnw.net/v1/playlist/audio.m3u8", TwitchParse.audioOnlyVariant(master))
        assertNull(TwitchParse.audioOnlyVariant(master.lines().take(4).joinToString("\n")))
    }
}
