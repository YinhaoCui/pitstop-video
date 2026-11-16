package app.pitstop.video

import app.pitstop.video.bilibili.BiliApi
import app.pitstop.video.bilibili.BiliException
import app.pitstop.video.bilibili.BiliLiveParse
import app.pitstop.video.bilibili.BiliMusicKeys
import app.pitstop.video.bilibili.BiliZones
import app.pitstop.video.core.SourceId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bilibili categories / endless paging. Samples trimmed from real anonymous responses (Nov 2026). */
class BiliCategoriesTest {
    private val viewers: (Long) -> String = { "$it watching" }

    // --- Video zones (kv-frontend namespace data, appKey 333.1339 nscode 10) ---

    /** The namespace map holds each value as a JSON string. */
    private val kv = JSONObject()
        .put("channel_list.sort", """["anime","game","music","love"]""")
        .put("channel_list.popular_page_sort", """["all","anime"]""")
        .put("channel_list.all", """{"tid":0,"name":"全部","route":"all","sub":[]}""")
        .put(
            "channel_list.anime",
            """{"channelId":1,"seasonType":1,"tid":0,"route":"anime","name":"番剧","sub":[{"subChannelId":10001,"tid":0,"route":"timeline","name":"新番时间表"}]}""",
        )
        .put(
            "channel_list.game",
            """{"channelId":8,"tid":1008,"route":"game","name":"游戏","url":"//www.bilibili.com/c/game/","icon":"homeicon/game/1","sub":[{"subChannelId":80001,"tid":2064,"route":"rpg","name":"单人RPG游戏","url":""}]}""",
        )
        .put(
            "channel_list.music",
            """{"channelId":10,"tid":1003,"route":"music","name":"音乐","sub":[{"subChannelId":100001,"tid":2016,"route":"original","name":"原创音乐","url":""},{"subChannelId":100005,"tid":2020,"route":"cover","name":"翻唱","url":""},{"subChannelId":100008,"tid":2023,"route":"ai_music","name":"AI音乐","url":""}],"config":{"enableSub":0}}""",
        )
        .put("channel_list.love", """{"channelId":37,"tid":0,"route":"love","name":"公益","url":"//love.bilibili.com","sub":[]}""")

    @Test fun zonesFollowSortOrderAndSkipChannelsWithoutZoneId() {
        val zones = BiliZones.parseZones(kv)
        assertEquals(listOf(1008, 1003), zones.map { it.tid })
        assertEquals("游戏", zones[0].name)
        assertEquals(listOf("原创音乐", "翻唱", "AI音乐"), zones[1].subs.map { it.name })
        assertEquals(2016, zones[1].subs[0].tid)
    }

    @Test fun zonesWithoutSortKeyStillParse() {
        val noSort = JSONObject(kv.toString()).apply { remove("channel_list.sort") }
        assertEquals(setOf(1008, 1003), BiliZones.parseZones(noSort).map { it.tid }.toSet())
        assertTrue(BiliZones.parseZones(null).isEmpty())
    }

    // --- Zone feed (x/web-interface/region/feed/rcmd) ---

    private val rcmd = JSONObject(
        """
        {"code":0,"message":"OK","data":{"archives":[
          {"aid":117335956393626,"bvid":"BV1N2hX66EGV","cid":42213969434,
           "title":"“爱为世间魔法，抚平满身伤痕.”【This place is a shelter】","cover":"http://i0.hdslb.com/bfs/archive/675b1ae8d516f5074137016fdf58626e4ca8c0d5.jpg",
           "duration":285,"pubdate":1790406000,"stat":{"view":2304856},"author":{"mid":676239823,"name":"早睡起大王"},"trackid":"","goto":"av","rec_reason":""},
          {"aid":1,"bvid":"","title":"no bvid"}]}}
        """,
    )

    @Test fun zonePageMapsArchivesAndAdvances() {
        val p = BiliZones.regionPage(rcmd, 3, SourceId.BILIBILI)
        assertEquals(1, p.items.size)
        val v = p.items[0]
        assertEquals("BV1N2hX66EGV", v.id)
        assertEquals("早睡起大王", v.author)
        assertEquals("676239823", v.authorId)
        assertEquals(285L, v.durationSec)
        assertEquals("42213969434", v.extra)
        assertTrue(v.thumbnailUrl!!.startsWith("https://i0.hdslb.com/"))
        assertEquals("4", p.next)
    }

    @Test fun zonePageEndsOnNoMore() {
        val p = BiliZones.regionPage(JSONObject("""{"code":62013,"message":"暂时没有更多内容了","ttl":1}"""), 400, SourceId.BILIBILI)
        assertTrue(p.items.isEmpty())
        assertNull(p.next)
    }

    @Test(expected = BiliException::class) fun zonePageRejectsSubZoneIds() {
        BiliZones.regionPage(JSONObject("""{"code":-400,"message":"请求错误","ttl":1}"""), 1, SourceId.BILIBILI)
    }

    // --- Search (wbi/search/type) and suggestions ---

    private fun search(page: Int, numPages: Int) = JSONObject(
        """
        {"page":$page,"pagesize":20,"numResults":1000,"numPages":$numPages,"result":[
          {"type":"video","bvid":"BV1DN4n6CEbA","title":"<em class=\"keyword\">Lofi</em> Chill Music👫丨你现在需要睡觉",
           "author":"高粱地里吃过虧","mid":37044453,"pic":"//i1.hdslb.com/bfs/archive/ccf96f43a21e946e42693d7802894b37ac88e66d.jpg",
           "duration":"180:0","typename":"音乐综合"}]}
        """,
    )

    @Test fun searchPagesUntilNumPages() {
        val first = BiliZones.searchPage(search(1, 50), 1, SourceId.BILI_MUSIC)
        assertEquals("2", first.next)
        val v = first.items.single()
        assertEquals("Lofi Chill Music👫丨你现在需要睡觉", v.title)
        assertEquals(SourceId.BILI_MUSIC, v.source)
        assertEquals("37044453", v.authorId)
        assertEquals(10800L, v.durationSec)
        assertNull(BiliZones.searchPage(search(50, 50), 50, SourceId.BILIBILI).next)
        assertNull(BiliZones.searchPage(JSONObject("""{"numPages":0,"result":[]}"""), 1, SourceId.BILIBILI).next)
    }

    @Test fun suggestionsTakeTagValues() {
        val json = JSONObject(
            """
            {"exp_str":"","code":0,"result":{"tag":[
              {"value":"周杰伦歌曲全集","term":"周杰伦歌曲全集","ref":0,"name":"<em class=\"suggest_high_light\">周杰</em>伦歌曲全集","spid":5},
              {"value":"周杰伦","term":"周杰伦","ref":0,"name":"<em class=\"suggest_high_light\">周杰</em>伦","spid":5},
              {"value":"周杰伦","term":"周杰伦","spid":5}]}}
            """,
        )
        assertEquals(listOf("周杰伦歌曲全集", "周杰伦"), BiliZones.suggestions(json))
        assertTrue(BiliZones.suggestions(JSONObject("""{"exp_str":"","code":3,"result":{"tag":[]},"total_count":0}""")).isEmpty())
        assertTrue(BiliZones.suggestions(JSONObject("""{"code":0,"result":[]}""")).isEmpty())
    }

    // --- Uploader space (space/wbi/arc/search) ---

    @Test fun uploaderPagesUntilCount() {
        fun d(pn: Int) = JSONObject(
            """
            {"list":{"vlist":[{"bvid":"BV1hJWJ6eEu5","title":"夜神月，你的数学作业还没给我","author":"Lunak_重庆电音之神",
              "pic":"http://i2.hdslb.com/bfs/archive/7b059e444267ad45c0e07f357be45bf893096c30.jpg","length":"01:04"}]},
             "page":{"pn":$pn,"ps":30,"count":52}}
            """,
        )
        val p1 = BiliZones.uploaderPage(d(1), "23099350", 1, 30, SourceId.BILIBILI)
        assertEquals("2", p1.next)
        assertEquals("23099350", p1.items.single().authorId)
        assertEquals(64L, p1.items.single().durationSec)
        assertNull(BiliZones.uploaderPage(d(2), "23099350", 2, 30, SourceId.BILIBILI).next)
    }

    // --- Music center lists and music categories ---

    @Test fun musicCenterItemsUseSongTitleAndCid() {
        val list = JSONArray(
            """
            [{"music_title":"別れ","music_id":"MA507959219589279202","cid":"42988996911","author":"久石譲","bvid":"BV1gDnb6WE3w",
              "album":"「君たちはどう生きるか」サウンドトラック","aid":"117489031711051",
              "cover":"https://i0.hdslb.com/bfs/station_src/music_metadata/4c6e782f037a81a0a35af798370846d4.jpg","score":228368},
             {"id":32117,"music_title":"橘子酸sour orange","publish_time":"11-6","author":"那艺娜","aid":"117568706713611","cid":"0","bvid":"BV1vM4B6vE49"},
             {"music_title":"no video","bvid":""}]
            """,
        )
        val items = BiliZones.musicCenterItems(list, SourceId.BILI_MUSIC)
        assertEquals(listOf("BV1gDnb6WE3w", "BV1vM4B6vE49"), items.map { it.id })
        assertEquals("別れ", items[0].title)
        assertEquals("久石譲", items[0].author)
        assertEquals("42988996911", items[0].extra)
        assertNull(items[1].extra)
    }

    @Test fun musicSubZonesMapToOldSearchZones() {
        val cats = BiliMusicKeys.subZones(BiliZones.parseZones(kv))
        assertEquals(listOf("原创音乐", "翻唱", "AI音乐"), cats.map { it.title })
        assertTrue(cats.all { it.group == "音乐" })
        assertEquals(BiliMusicKeys.Key.SubZone(28, "原创音乐"), BiliMusicKeys.parse(cats[0].key))
        assertEquals(BiliMusicKeys.Key.SubZone(31, "翻唱"), BiliMusicKeys.parse(cats[1].key))
        // No old zone for AI音乐: search the whole music zone (3) for its name.
        assertEquals(BiliMusicKeys.Key.SubZone(3, "AI音乐"), BiliMusicKeys.parse(cats[2].key))
    }

    @Test fun musicChartKeys() {
        val charts = BiliMusicKeys.charts("Top charts", "Hot songs", "New releases")
        assertEquals(BiliMusicKeys.Key.Chart(BiliApi.MUSIC_HOT), BiliMusicKeys.parse(charts[0].key))
        assertEquals(BiliMusicKeys.Key.Chart(BiliApi.MUSIC_NEW), BiliMusicKeys.parse(charts[1].key))
        assertNull(BiliMusicKeys.parse("c:other"))
        assertNull(BiliMusicKeys.parse("s:x:name"))
        assertNull(BiliMusicKeys.parse("s:28:"))
        // A sub-zone name containing ':' survives the round trip.
        assertEquals(BiliMusicKeys.Key.SubZone(3, "电台:歌单"), BiliMusicKeys.parse("s:3:电台:歌单"))
    }

    // --- Live areas (room/v1/Area/getList?need_entrance=1) and area rooms (room/v3/area/getRoomList) ---

    private val areas = JSONArray(
        """
        [{"id":2,"name":"网游","list":[
           {"id":"0","parent_id":"2","old_area_id":"0","name":"全部网游","act_id":"0","pk_status":"0","hot_status":0,"lock_status":"0",
            "pic":"https://i0.hdslb.com/bfs/live/3aa12550186a2f8c4dd9352f6c3fcc82054e594d.png","parent_name":"网游","area_type":1},
           {"id":"86","parent_id":"2","name":"英雄联盟","pic":"//i0.hdslb.com/bfs/vc/lol.png","parent_name":"网游"}]},
         {"id":5,"name":"电台","list":[{"id":"190","parent_id":"5","name":"唱见电台","pic":"","parent_name":"电台"}]}]
        """,
    )

    @Test fun liveAreasGroupedByParent() {
        val cats = BiliLiveParse.areas(areas)
        assertEquals(listOf("2:0", "2:86", "5:190"), cats.map { it.key })
        assertEquals(listOf("网游", "网游", "电台"), cats.map { it.group })
        assertEquals("全部网游", cats[0].title)
        assertEquals("https://i0.hdslb.com/bfs/live/3aa12550186a2f8c4dd9352f6c3fcc82054e594d.png", cats[0].thumbnailUrl)
        assertEquals("https://i0.hdslb.com/bfs/vc/lol.png", cats[1].thumbnailUrl)
        assertNull(cats[2].thumbnailUrl)
        assertEquals("2" to "86", BiliLiveParse.areaKey("2:86"))
        assertNull(BiliLiveParse.areaKey("recommended"))
        assertNull(BiliLiveParse.areaKey("2:x"))
    }

    private fun roomList(count: Int) = JSONObject(
        """
        {"count":$count,"list":[
          {"roomid":545068,"uid":8739477,"title":"德云色  上分如喝汤！","uname":"老实憨厚的笑笑","online":361486,
           "user_cover":"https://i0.hdslb.com/bfs/live/new_room_cover/258c61418fc8483e5c191ff9c4f771509de606a5.jpg",
           "system_cover":"https://i0.hdslb.com/bfs/live-key-frame/keyframe11141026000000545068mis8hg.jpg",
           "link":"/7777","parent_id":2,"parent_name":"网游","area_id":86,"area_name":"英雄联盟"}]}
        """,
    )

    @Test fun areaRoomsPageUntilCount() {
        val p = BiliLiveParse.areaPage(roomList(363), 1, 30, viewers)
        assertEquals("545068", p.items.single().id)
        assertEquals("老实憨厚的笑笑 · 361486 watching", p.items.single().author)
        assertEquals("2", p.next)
        assertNull(BiliLiveParse.areaPage(roomList(363), 13, 30, viewers).next)
        assertNull(BiliLiveParse.areaPage(JSONObject("""{"count":363,"list":[]}"""), 40, 30, viewers).next)
    }

    @Test fun liveSearchPagesUntilNumPages() {
        fun d(numPages: Int) = JSONObject(
            """
            {"page":1,"pagesize":40,"numResults":4,"numPages":$numPages,"result":[
              {"roomid":27519423,"uid":1685650605,"uname":"LofiGirl","title":"学习工作背景音乐 24/7 自习室","online":8331,
               "user_cover":"//i0.hdslb.com/bfs/live/new_room_cover/a5a2ddf7a83665207299079de6e574683db121ea.jpg","live_status":1}]}
            """,
        )
        assertNull(BiliLiveParse.searchPage(d(1), 1, viewers).next)
        val p = BiliLiveParse.searchPage(d(12), 1, viewers)
        assertEquals("2", p.next)
        assertEquals("27519423", p.items.single().id)
    }
}
