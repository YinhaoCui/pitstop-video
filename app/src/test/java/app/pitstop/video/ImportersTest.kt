package app.pitstop.video

import app.pitstop.video.core.UserFacingException
import app.pitstop.video.importer.AppleMusicParse
import app.pitstop.video.importer.KugouParse
import app.pitstop.video.importer.KugouRef
import app.pitstop.video.importer.QqMusicParse
import app.pitstop.video.importer.ShortLinks
import app.pitstop.video.importer.SpotifyParse
import app.pitstop.video.importer.TrackMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playlist importers: link parsing + JSON/HTML → ImportedList, with trimmed real responses (Nov 2026). */
class ImportersTest {

    // ---------------- QQ Music ----------------

    private val qq = JSONObject(
        """{"code":0,"cdlist":[{"disstid":"7729596131","dissname":"耳机里的秘密 | 宝藏女声集合站",
        "logo":"http://qpic.y.qq.com/music_cover/qhuJFHlwiayRp1rhWoJk9HaAkric2uNwiaX5vwuDF1mb4LgEId9z2eExg/300?n=1","songnum":1297,
        "songlist":[{"id":738994756,"mid":"003y6XtT1xZJ3c","name":"回见（See U）","title":"回见（See U）","interval":217,
        "singer":[{"id":1158805,"mid":"001TGf0a3dy36i","name":"庄达菲","title":"庄达菲"}],
        "album":{"id":106425376,"mid":"002eeO4w0t6YTG","pmid":"002eeO4w0t6YTG_2","name":"感，觉","title":"感，觉","subtitle":""}},
        {"id":738993886,"mid":"001Gaz3p1WcU9i","name":"重感冒（Overloaded）","title":"重感冒（Overloaded）","interval":223,
        "singer":[{"id":1158805,"mid":"001TGf0a3dy36i","name":"庄达菲","title":"庄达菲"},{"id":1,"mid":"x","name":"客串","title":"客串"}],
        "album":{"id":106425376,"mid":"002eeO4w0t6YTG","pmid":"002eeO4w0t6YTG_2","name":"感，觉","title":"感，觉","subtitle":""}}]}]}""",
    )

    @Test
    fun qqPlaylistIdFromEveryLinkShape() {
        assertEquals("7729596131", QqMusicParse.playlistId("https://y.qq.com/n/ryqq/playlist/7729596131"))
        assertEquals("7729596131", QqMusicParse.playlistId("https://y.qq.com/n/yqq/playlist/7729596131.html"))
        assertEquals(
            "7729596131",
            QqMusicParse.playlistId("https://i.y.qq.com/n2/m/share/details/taoge.html?platform=11&appshare=android_qq&hosteuin=abc&id=7729596131&ADTAG=wxfshare"),
        )
        assertEquals("7729596131", QqMusicParse.playlistId("https://y.qq.com/musicmac/v6/playlist/detail.html?id=7729596131"))
        assertNull(QqMusicParse.playlistId("https://y.qq.com/n/ryqq/songDetail/003y6XtT1xZJ3c"))
        assertNull(QqMusicParse.playlistId("https://example.com/playlist/7729596131"))
    }

    @Test
    fun qqParsesTracks() {
        val list = QqMusicParse.playlist(qq)
        assertEquals("耳机里的秘密 | 宝藏女声集合站", list.name)
        assertTrue(list.coverUrl!!.startsWith("https://qpic.y.qq.com/"))
        assertEquals(TrackMeta("回见（See U）", listOf("庄达菲"), "感，觉", 217), list.tracks[0])
        assertEquals(listOf("庄达菲", "客串"), list.tracks[1].artists)
    }

    @Test(expected = UserFacingException::class)
    fun qqMissingPlaylistIsUserFacing() {
        QqMusicParse.playlist(JSONObject("""{"code":-1,"subcode":0,"cdnum":0,"cdlist":[],"realcdnum":0}"""))
    }

    // ---------------- Kugou ----------------

    private val kugouSongs = JSONObject(
        """{"error_code":0,"errmsg":"","status":1,"data":{"listid":181,"page":2,"pagesize":300,"count":517,"userid":520033052,"info":[
        {"hash":"5DADF8765844989D71F37F7B3EBE6021","name":"洪思雨 - 踏浪 (清新版)","timelen":152346,"album_id":"102719427",
         "albuminfo":{"name":"踏浪","id":102719427,"publish":1},"singerinfo":[{"name":"洪思雨","publish":1,"id":971315,"type":0}],"remark":"踏浪"},
        {"hash":"x","name":"潘玮柏、弦子 - 不得不爱","timelen":208000},
        {"hash":"100950822784C10B90FDF39381EFBAFC","name":"大头针 Official - 如果遗憾 (烟嗓版)","timelen":272000,"album_id":"172761168",
         "albuminfo":{"name":"如果遗憾（烟嗓版）","id":172761168,"publish":1},"singerinfo":[{"name":"大头针 Official","publish":1,"id":2535782,"type":1}]}]}}""",
    )

    @Test
    fun kugouRefFromLinkShapes() {
        assertEquals(KugouRef.Special(9856614), KugouParse.ref("https://www.kugou.com/yy/special/single/9856614.html"))
        assertEquals(KugouRef.Encoded("gcid_3z9vj1r7z56z018"), KugouParse.ref("https://m.kugou.com/songlist/gcid_3z9vj1r7z56z018/?src_cid=x"))
        assertEquals(KugouRef.Encoded("gcid_3z9vj1r7z56z018"), KugouParse.ref("https://www.kugou.com/songlist/gcid_3z9vj1r7z56z018/"))
        assertEquals(
            KugouRef.Collection("collection_3_520033052_181_0"),
            KugouParse.ref("https://m.kugou.com/share/zlist.html?listid=181&type=0&uid=520033052&share_type=collect&global_collection_id=collection_3_520033052_181_0"),
        )
        assertEquals(KugouRef.Collection("collection_3_1_1_0"), KugouParse.ref("https://m.kugou.com/share/zlist.html?listid=1&type=0&uid=1"))
        assertNull(KugouParse.ref("https://www.kugou.com/song/#hash=abc"))
        assertNull(KugouParse.ref("https://y.qq.com/n/ryqq/playlist/1"))
    }

    @Test
    fun kugouSignatureMatchesInfSign() {
        // Reference values computed with the infSign.min.js algorithm; the second request signature was accepted live.
        assertEquals("88236a387c34222174e4b6cb3c0980ab", KugouParse.sign(mapOf("b" to "2", "a" to "1", "srcappid" to "2919")))
        val url = KugouParse.songsUrl(KugouRef.Collection("collection_3_1_1_0"), 1, 1_700_000_000_000)
        assertEquals("pubsongscdn.kugou.com", url.host)
        assertEquals("collection_3_1_1_0", url.queryParameter("global_collection_id"))
        assertEquals("300", url.queryParameter("pagesize"))
        assertEquals("5a1c022103fa1647a50899250d0c7100", url.queryParameter("signature"))
    }

    @Test
    fun kugouParsesSongsAndSplitsNames() {
        val p = KugouParse.songsPage(kugouSongs)
        assertEquals(517, p.total)
        assertEquals(3, p.rawCount)
        assertEquals(TrackMeta("踏浪 (清新版)", listOf("洪思雨"), "踏浪", 152), p.tracks[0])
        assertEquals(TrackMeta("不得不爱", listOf("潘玮柏", "弦子"), null, 208), p.tracks[1])
        assertEquals(listOf("大头针 Official"), p.tracks[2].artists)
        assertEquals(emptyList<String>() to "Untitled", KugouParse.splitName("Untitled"))
        assertEquals(listOf("A") to "B - C", KugouParse.splitName("A - B - C"))
    }

    @Test(expected = UserFacingException::class)
    fun kugouListFailureIsUserFacing() {
        KugouParse.songsPage(JSONObject("""{"error_code":30203,"errmsg":"get other list file fail","data":{},"status":0}"""))
    }

    @Test
    fun kugouSonglistPage() {
        val html = """<script>window.${'$'}output = {"encode_gic":"gcid_3z9vj1r7z56z018","global_collection_id":"","info":{"listinfo":
            {"global_collection_id":"","name":"抖音热歌2026：全网热门歌曲推荐","pic":"http:\/\/imge.kugou.com\/soft\/collection\/{size}\/20260930\/20260930145329922077.jpg",
            "list_create_userid":520033052,"count":517,"is_pri":0,"specialid":9856614},"songs":[]}};</script>"""
        val page = KugouParse.songlistPage(html)!!
        assertEquals(KugouRef.Special(9856614), page.ref)
        assertEquals("抖音热歌2026：全网热门歌曲推荐", page.name)
        assertEquals("https://imge.kugou.com/soft/collection/400/20260930/20260930145329922077.jpg", page.cover)
        val user = html.replace("\"global_collection_id\":\"\",\"name\"", "\"global_collection_id\":\"collection_3_1_1_0\",\"name\"")
        assertEquals(KugouRef.Collection("collection_3_1_1_0"), KugouParse.songlistPage(user)!!.ref)
        assertNull(KugouParse.songlistPage(html.replace("\"is_pri\":0", "\"is_pri\":1")))
        assertNull(KugouParse.songlistPage("<html>loading</html>"))
    }

    // ---------------- Spotify ----------------

    @Test
    fun spotifyPlaylistId() {
        assertEquals("37i9dQZF1DX5Ejj0EkURtP", SpotifyParse.playlistId("https://open.spotify.com/playlist/37i9dQZF1DX5Ejj0EkURtP?si=abc123"))
        assertEquals("37i9dQZF1DX5Ejj0EkURtP", SpotifyParse.playlistId("https://open.spotify.com/intl-de/playlist/37i9dQZF1DX5Ejj0EkURtP"))
        assertEquals("37i9dQZF1DX5Ejj0EkURtP", SpotifyParse.playlistId("https://open.spotify.com/embed/playlist/37i9dQZF1DX5Ejj0EkURtP"))
        assertEquals("37i9dQZF1DX5Ejj0EkURtP", SpotifyParse.playlistId("spotify:playlist:37i9dQZF1DX5Ejj0EkURtP"))
        assertNull(SpotifyParse.playlistId("https://open.spotify.com/album/37i9dQZF1DX5Ejj0EkURtP"))
    }

    @Test
    fun spotifyEmbedPage() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist",
            "name":"All Out 2010s","coverArt":{"sources":[{"height":null,"width":null,"url":"https://i.scdn.co/image/ab67706f00000002ddeb90231491c14812b062ff"}]},
            "trackList":[{"uri":"spotify:track:190jyVPHYjAqEaOGmMzdyk","title":"Beauty And A Beat","subtitle":"Justin Bieber, Nicki Minaj","duration":227986,"entityType":"track"},
            {"uri":"spotify:track:2QjOHCTQ1Jl3zawyYOpxh6","title":"Sweater Weather","subtitle":"The Neighbourhood","duration":240400,"entityType":"track"}]}},
            "settings":{"session":{"accessToken":"TOKEN","isAnonymous":true}}}}}}</script></html>"""
        val e = SpotifyParse.embed(html)
        assertEquals("TOKEN", e.token)
        assertEquals("All Out 2010s", e.list.name)
        assertEquals(TrackMeta("Beauty And A Beat", listOf("Justin Bieber", "Nicki Minaj"), null, 228), e.list.tracks[0])
    }

    @Test(expected = UserFacingException::class)
    fun spotifyEmbed404IsUserFacing() {
        SpotifyParse.embed(
            """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"status":404,"title":"Page not found"}}}</script>""",
        )
    }

    @Test
    fun spotifyPathfinderPage() {
        val json = JSONObject(
            """{"data":{"playlistV2":{"__typename":"Playlist","name":"All Out 2010s","images":{"items":[{"sources":[{"height":null,
            "url":"https://i.scdn.co/image/ab67706f00000002ddeb90231491c14812b062ff","width":null}]}]},"content":{"__typename":"PlaylistItemsPage",
            "totalCount":150,"pagingInfo":{"limit":100,"offset":0},"items":[{"itemV2":{"__typename":"TrackResponseWrapper","data":{"__typename":"Track",
            "albumOfTrack":{"name":"Believe (Deluxe Edition)"},"artists":{"items":[{"profile":{"name":"Justin Bieber"},"uri":"spotify:artist:1uNFoZAHBGtllmzznpCI3s"},
            {"profile":{"name":"Nicki Minaj"},"uri":"spotify:artist:0hCNtLu0JehylgoiP8L4Gh"}]},"name":"Beauty And A Beat","trackDuration":{"totalMilliseconds":227986},
            "uri":"spotify:track:190jyVPHYjAqEaOGmMzdyk"}}},{"itemV2":{"__typename":"TrackResponseWrapper","data":{"__typename":"Track",
            "albumOfTrack":{"name":"I Love You."},"artists":{"items":[{"profile":{"name":"The Neighbourhood"},"uri":"spotify:artist:77SW9BnxLY8rJ0RciFqkHh"}]},
            "name":"Sweater Weather","trackDuration":{"totalMilliseconds":240400},"uri":"spotify:track:2QjOHCTQ1Jl3zawyYOpxh6"}}}]}}}}""",
        )
        val p = SpotifyParse.pathfinderPage(json)!!
        assertEquals(150, p.total)
        assertEquals(2, p.rawCount)
        assertEquals("All Out 2010s", p.name)
        assertEquals(TrackMeta("Beauty And A Beat", listOf("Justin Bieber", "Nicki Minaj"), "Believe (Deluxe Edition)", 228), p.tracks[0])
        assertNull(
            SpotifyParse.pathfinderPage(
                JSONObject("""{"data":{"playlistV2":{"__typename":"NotFound","message":"Object with uri 'spotify:playlist:0' not found"}}}"""),
            ),
        )
        val url = SpotifyParse.pathfinderUrl("37i9dQZF1DX5Ejj0EkURtP", 100, 100)
        assertEquals("fetchPlaylist", url.queryParameter("operationName"))
        assertEquals(100, JSONObject(url.queryParameter("variables")!!).getInt("offset"))
    }

    // ---------------- Apple Music ----------------

    private val appleHtml = """<html><head><script type="module" crossorigin src="/assets/index~B8RKPfmaCI.js"></script></head>
        <script type="application/json" id="serialized-server-data">{"data":[{"intent":{},"data":{"sections":[
        {"id":"playlist-detail-header-section - pl.674abcd261d04582b58d6388394cd047","itemKind":"containerDetailHeaderLockup","items":[{"title":"Hip-Hop/R&B Throwback",
        "trackCount":249,"artwork":{"dictionary":{"url":"https://is1-ssl.mzstatic.com/image/thumb/Features125/v4/f3/ff/37/x.png/{w}x{h}SC.DN02.{f}?l=en-US","width":1080}}}]},
        {"id":"track-list - pl.674abcd261d04582b58d6388394cd047","itemKind":"trackLockup","items":[
        {"id":"track-lockup - pl.674abcd261d04582b58d6388394cd047 - 1512301465","title":"Go Crazy","duration":176960,"artistName":"Chris Brown & Young Thug",
         "contentDescriptor":{"kind":"song","identifiers":{"storeAdamID":"1512301465"}},"subtitleLinks":[{"title":"Chris Brown"},{"title":"Young Thug"}],"tertiaryLinks":[{"title":"Slime & B"}]},
        {"id":"track-lockup - pl.674abcd261d04582b58d6388394cd047 - 1440760661","title":"Leave You Alone (feat. Ne-Yo)","duration":329187,"artistName":"Jeezy",
         "contentDescriptor":{"kind":"song","identifiers":{"storeAdamID":"1440760661"}},"subtitleLinks":[{"title":"Jeezy"}],"tertiaryLinks":[{"title":"TM:103 Hustlerz Ambition (Deluxe Version)"}]}
        ]}]}}],"userTokenHash":""}</script></html>"""

    private val amp = JSONObject(
        """{"next":"/v1/catalog/us/playlists/pl.674abcd261d04582b58d6388394cd047/tracks?offset=100","data":[
        {"id":"1512301465","type":"songs","attributes":{"albumName":"Slime & B","artistName":"Chris Brown & Young Thug","durationInMillis":176960,"isrc":"USRC12001234","name":"Go Crazy"}},
        {"id":"1440760661","type":"songs","attributes":{"albumName":"TM:103 Hustlerz Ambition (Deluxe Version)","artistName":"Jeezy","durationInMillis":329187,"isrc":"USUM70800001","name":"Leave You Alone (feat. Ne-Yo)"}},
        {"id":"1258975642","type":"songs","attributes":{"albumName":"Urban Legend","artistName":"T.I.","durationInMillis":243506,"isrc":"USAT20402062","name":"U Don't Know Me"}}]}""",
    )

    @Test
    fun appleRef() {
        assertEquals(
            AppleMusicParse.Ref("us", "pl.674abcd261d04582b58d6388394cd047"),
            AppleMusicParse.ref("https://music.apple.com/us/playlist/hip-hop-r-b-throwback/pl.674abcd261d04582b58d6388394cd047"),
        )
        assertEquals(AppleMusicParse.Ref("cn", "pl.u-abc123"), AppleMusicParse.ref("https://music.apple.com/cn/playlist/pl.u-abc123?ls"))
        assertEquals(AppleMusicParse.Ref("us", "pl.674abcd261d04582b58d6388394cd047"), AppleMusicParse.ref("https://music.apple.com/playlist/pl.674abcd261d04582b58d6388394cd047"))
        assertNull(AppleMusicParse.ref("https://music.apple.com/us/album/housewife/6822497770"))
    }

    @Test
    fun applePageAndMerge() {
        val page = AppleMusicParse.page(appleHtml)
        assertEquals("Hip-Hop/R&B Throwback", page.name)
        assertEquals(249, page.trackCount)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Features125/v4/f3/ff/37/x.png/600x600SC.DN02.jpg?l=en-US", page.cover)
        assertEquals(TrackMeta("Go Crazy", listOf("Chris Brown", "Young Thug"), "Slime & B", 177), page.tracks[0].meta)
        assertEquals("/assets/index~B8RKPfmaCI.js", AppleMusicParse.bundlePath(appleHtml))

        val ampPage = AppleMusicParse.ampPage(amp)
        assertEquals("/v1/catalog/us/playlists/pl.674abcd261d04582b58d6388394cd047/tracks?offset=100", ampPage.next)
        assertEquals(listOf("Chris Brown", "Young Thug"), ampPage.tracks[0].meta.artists)

        // amp-api complete: its order and ISRCs, page artists kept for known songs.
        val merged = AppleMusicParse.merge(page, ampPage.tracks)
        assertEquals(3, merged.tracks.size)
        assertEquals(TrackMeta("Go Crazy", listOf("Chris Brown", "Young Thug"), "Slime & B", 177, "USRC12001234"), merged.tracks[0])
        assertEquals("USAT20402062", merged.tracks[2].isrc)
        // amp-api failed: page tracks only.
        assertEquals(2, AppleMusicParse.merge(page, null).tracks.size)
        assertNull(AppleMusicParse.merge(page, null).tracks[0].isrc)
    }

    @Test
    fun appleWebToken() {
        val jwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IldlYlBsYXlLaWQifQ.eyJpc3MiOiJBTVBXZWJQbGF5In0.sig_-123"
        assertEquals(jwt, AppleMusicParse.webToken("""const Do="2646.4.0-external",${'$'}a="$jwt";"""))
        assertNull(AppleMusicParse.webToken("""x="eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiIsImtpZCI6IkxUMlpEWlNYTlEifQ.a.b""""))
    }

    // ---------------- Short links ----------------

    @Test
    fun shortLinks() {
        assertTrue(ShortLinks.isShortLink("https://c6.y.qq.com/base/fcgi-bin/u?__=AbCdEf1"))
        assertTrue(ShortLinks.isShortLink("https://c.y.qq.com/base/fcgi-bin/u?__=AbCdEf1"))
        assertTrue(ShortLinks.isShortLink("https://t1.kugou.com/song.html?id=abc123"))
        assertTrue(ShortLinks.isShortLink("https://spotify.link/abcdEFGH12"))
        assertTrue(ShortLinks.isShortLink("https://apple.co/3xyzabc"))
        assertFalse(ShortLinks.isShortLink("https://open.spotify.com/playlist/37i9dQZF1DX5Ejj0EkURtP"))
        assertFalse(ShortLinks.isShortLink("not a url"))
        assertEquals(
            "https://www.kugou.com/share/abc123.html?id=abc123",
            ShortLinks.nextHop("https://t1.kugou.com/song.html?id=abc123", "http://www.kugou.com/share/abc123.html?id=abc123"),
        )
        assertEquals("https://spotify.link/next", ShortLinks.nextHop("https://spotify.link/abc", "/next"))
    }
}
