package app.pitstop.video

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.SourceId
import app.pitstop.video.netease.NeteaseParse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** NetEase JSON → categories / items / cursors, with trimmed real responses (Nov 2026). */
class NeteaseCategoriesTest {
    private val catalogue = JSONObject(
        """{"code":200,"all":{"name":"全部歌单","category":4},"sub":[{"name":"流行","category":1,"imgUrl":null,"hot":true},
        {"name":"华语","category":0,"imgUrl":null,"hot":true},{"name":"欧美","category":0,"imgUrl":null,"hot":false},
        {"name":"ACG","category":4,"imgUrl":null,"hot":true},{"name":"治愈","category":3,"imgUrl":null,"hot":true},
        {"name":"驾车","category":2,"imgUrl":null,"hot":false}],
        "categories":{"0":"语种","1":"风格","2":"场景","3":"情感","4":"主题"}}""",
    )
    private val toplists = JSONObject(
        """{"code":200,"list":[{"id":19723756,"name":"飙升榜","coverImgUrl":"https://p1.music.126.net/rIi7Qzy2i2Y_1QD7cd0MYA==/109951170048506929.jpg","updateFrequency":"更新77首"},
        {"id":3779629,"name":"新歌榜","coverImgUrl":"https://p1.music.126.net/5guhqPBTcIrrhLBotgaT6w==/109951170048511751.jpg"}]}""",
    )

    @Test
    fun chartsFirstThenAllThenTagsByGroupInNeteaseOrder() {
        val cats = NeteaseParse.categories(catalogue, toplists, "Charts")
        assertEquals(
            listOf("飙升榜", "新歌榜", "全部歌单", "华语", "欧美", "流行", "驾车", "治愈", "ACG"),
            cats.map { it.title },
        )
        assertEquals(listOf("Charts", "Charts", null, "语种", "语种", "风格", "场景", "情感", "主题"), cats.map { it.group })
        assertEquals("chart:19723756", cats[0].key)
        assertEquals("https://p1.music.126.net/rIi7Qzy2i2Y_1QD7cd0MYA==/109951170048506929.jpg?param=300y300", cats[0].thumbnailUrl)
        assertEquals("tag:全部", cats[2].key)
        assertEquals("tag:驾车", cats[6].key)
        assertNull(cats[6].thumbnailUrl)
    }

    @Test
    fun categoriesSurviveAMissingHalf() {
        assertEquals(2, NeteaseParse.categories(null, toplists, "Charts").size)
        assertEquals(7, NeteaseParse.categories(catalogue, null, "Charts").size)
    }

    @Test
    fun categoryKeysRoundTrip() {
        assertEquals(NeteaseParse.Chart(3778678), NeteaseParse.categoryRef("chart:3778678"))
        assertEquals(NeteaseParse.Tag("R&B/Soul"), NeteaseParse.categoryRef("tag:R&B/Soul"))
        assertNull(NeteaseParse.categoryRef("chart:abc"))
        assertNull(NeteaseParse.categoryRef("tag:"))
        assertNull(NeteaseParse.categoryRef("other"))
    }

    @Test
    fun tagPlaylistsBecomeFolderRowsWithCleanCovers() {
        val j = JSONObject(
            """{"total":1668,"more":true,"playlists":[{"id":17837791371,"name":"城市霓虹脉搏 | 在流动心跳中穿梭整座城市",
            "coverImgUrl":"http://p1.music.126.net/GiqUcoHmWdJZxvA2keeSyQ==/109951172904230011.jpg?imageView=1&thumbnail=800y800&enlarge=1%7CimageView=1&watermark&type=1",
            "trackCount":30}]}""",
        )
        val p = NeteaseParse.playlists(j.optJSONArray("playlists")) { "$it tracks" }.single()
        assertEquals(COLLECTION_PREFIX + "17837791371", p.id)
        assertEquals("30 tracks", p.author)
        assertEquals("https://p1.music.126.net/GiqUcoHmWdJZxvA2keeSyQ==/109951172904230011.jpg?param=300y300", p.thumbnailUrl)
    }

    @Test
    fun searchSongsCarryArtistIdAndHttpsCover() {
        val j = JSONObject(
            """{"result":{"songCount":334,"songs":[{"id":2676833569,"name":"老弟别呲牙","ar":[{"id":96989167,"name":"晴天"},{"id":7,"name":"B"}],
            "al":{"id":263381474,"name":"呲牙","picUrl":"http://p1.music.126.net/UknYGzcSIUaD6VDEwDRJAw==/109951170500138557.jpg"},"dt":119166}]}}""",
        )
        val s = NeteaseParse.songs(j.getJSONObject("result").optJSONArray("songs")).single()
        assertEquals(SourceId.NETEASE, s.source)
        assertEquals("2676833569", s.id)
        assertEquals("晴天 / B", s.author)
        assertEquals("96989167", s.authorId)
        assertEquals(119L, s.durationSec)
        assertEquals("https://p1.music.126.net/UknYGzcSIUaD6VDEwDRJAw==/109951170500138557.jpg?param=300y300", s.thumbnailUrl)
    }

    @Test
    fun suggestKeywords() {
        val j = JSONObject("""{"result":{"allMatch":[{"keyword":"周杰伦","type":1},{"keyword":"周杰伦歌单","type":1},{"keyword":" 周杰伦 ","type":1}]},"code":200}""")
        assertEquals(listOf("周杰伦", "周杰伦歌单"), NeteaseParse.suggestions(j))
        assertEquals(emptyList<String>(), NeteaseParse.suggestions(JSONObject("""{"result":{},"code":200}""")))
    }

    @Test
    fun trackIdsKeepOrder() {
        val pl = JSONObject("""{"trackIds":[{"id":3},{"id":1},{"id":0},{"id":2}]}""")
        assertEquals(listOf(3L, 1L, 2L), NeteaseParse.trackIds(pl))
    }

    @Test
    fun offsetCursors() {
        assertEquals(0, NeteaseParse.offset(null))
        assertEquals(0, NeteaseParse.offset("x"))
        assertEquals(50, NeteaseParse.offset("50"))
        assertEquals("50", NeteaseParse.next(0, 50, total = 334))
        assertNull(NeteaseParse.next(300, 34, total = 334)) // reached songCount
        assertNull(NeteaseParse.next(50, 0, total = 334)) // empty page
        assertNull(NeteaseParse.next(0, 50, total = 1668, more = false))
        assertEquals("100", NeteaseParse.next(50, 50, more = true))
    }
}
