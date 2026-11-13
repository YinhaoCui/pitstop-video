package app.pitstop.video

import app.pitstop.video.live.DouyuParse
import app.pitstop.video.live.DouyuRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyuTest {
    // Trimmed real responses (Nov 2026).
    private val listJson = """{"error":0,"msg":"success","data":{"pgcnt":0,"rl":[
        {"type":1,"rid":6979222,"rn":"小众宝藏聊一会儿","nn":"玩机器丶Machine","ol":3896660,
         "rs16":"https://rpic.douyucdn.cn/asrpic/261114/6979222_src_0719.avif/dy1","rs1":null},
        {"type":5,"rid":0,"rn":"ad"}]}}"""

    private val searchJson = """{"data":{"pageSize":20,"relateShow":[{"hot":"250.1万","isLive":1,
        "nickName":"南波儿大魔王丶","rid":5526219,"roomName":"云顶斗地主！",
        "roomSrc":"https://rpic.douyucdn.cn/asrpic/261114/5526219_src_0724.avif/dy1"}],"total":20}}"""

    private val encJson = """{"error":0,"msg":"正常","data":{"key":"f92MgbZfsl8Pc575IdXe369231",
        "rand_str":"cVDQ6SVk6u9hYm1k","enc_time":1,"expire_at":1794612890,"is_special":0,"enc_data":"eyJhbGdfdmVyIjoxfQ=="}}"""

    private val playJson = """{"error":0,"msg":"ok","data":{"room_id":5526219,"rtmp_cdn":"ws-h5",
        "rtmp_url":"https://ws1a.douyucdn.cn/live","rtmp_live":"5526219rDQb8D5sk_4000.flv?wsAuth=75f0&token=web-h5-0","rate":4,
        "multirates":[{"name":"原画2K60","rate":0,"bit":10143},{"name":"蓝光4M","rate":4,"bit":4000},
        {"name":"超清","rate":3,"bit":2000},{"name":"高清","rate":2,"bit":900}]}}"""

    @Test
    fun parsesListSkippingNonRooms() {
        val rooms = DouyuParse.parseListPage(listJson)
        assertEquals(1, rooms.size)
        with(rooms[0]) {
            assertEquals("6979222", rid)
            assertEquals("小众宝藏聊一会儿", title)
            assertEquals("玩机器丶Machine", streamer)
            assertEquals(3896660L, viewers)
            assertTrue(cover!!.startsWith("https://"))
        }
        assertTrue(DouyuParse.parseListPage("""{"error":0,"data":{"pgcnt":0,"rl":[]}}""").isEmpty())
    }

    @Test
    fun parsesSearchAndHotNumbers() {
        val r = DouyuParse.parseSearch(searchJson).single()
        assertEquals("5526219", r.rid)
        assertEquals("南波儿大魔王丶", r.streamer)
        assertEquals(2_501_000L, r.viewers)
        assertEquals(8345L, DouyuParse.parseHot("8345"))
        assertEquals(120_000_000L, DouyuParse.parseHot("1.2亿"))
        assertNull(DouyuParse.parseHot(""))
    }

    @Test
    fun authMatchesIteratedMd5() {
        // Reference values computed with Python hashlib from the same formula.
        val p = DouyuParse.signParams(encJson, "6979222", "d1d", "1794612290")
        assertEquals(
            DouyuParse.md5(DouyuParse.md5("cVDQ6SVk6u9hYm1k" + "f92MgbZfsl8Pc575IdXe369231") + "f92MgbZfsl8Pc575IdXe369231" + "69792221794612290"),
            p.auth,
        )
        assertEquals("1794612290", p.tt)
        assertEquals("eyJhbGdfdmVyIjoxfQ==", p.encData)
        assertEquals(
            "8f80ff6fb54df42ac670e0da7d1c33f1",
            DouyuParse.auth("cVDQ6SVk6u9hYm1k", "f92MgbZfsl8Pc575IdXe369231", 2, false, "6979222", "1794612290"),
        )
        assertEquals(
            "2d46685bd8126324cf2e95de8485ce5d",
            DouyuParse.auth("cVDQ6SVk6u9hYm1k", "f92MgbZfsl8Pc575IdXe369231", 1, true, "6979222", "1794612290"),
        )
    }

    @Test
    fun parsesPlayResponse() {
        val r = DouyuParse.parsePlay(playJson) as DouyuParse.PlayResult.Ok
        assertEquals("https://ws1a.douyucdn.cn/live/5526219rDQb8D5sk_4000.flv?wsAuth=75f0&token=web-h5-0", r.url)
        assertEquals(4, r.rate)
        assertEquals(listOf(1440, 1080, 720, 540), r.rates.map { it.height })
        assertEquals(DouyuParse.PlayResult.Offline, DouyuParse.parsePlay("""{"error":-5,"msg":"房间未开播","data":""}"""))
        assertEquals(DouyuParse.PlayResult.Offline, DouyuParse.parsePlay("""{"error":-3,"msg":"房间不存在","data":""}"""))
        assertTrue(DouyuParse.parsePlay("""{"error":-9,"msg":"x","data":""}""") is DouyuParse.PlayResult.Failed)
    }

    @Test
    fun choosesRateWithinHeight() {
        val rates = (DouyuParse.parsePlay(playJson) as DouyuParse.PlayResult.Ok).rates
        assertEquals(4, DouyuParse.chooseRate(rates, 1080, false)!!.rate)
        assertEquals(0, DouyuParse.chooseRate(rates, 1440, false)!!.rate)
        assertEquals(3, DouyuParse.chooseRate(rates, 720, false)!!.rate)
        assertEquals(2, DouyuParse.chooseRate(rates, 360, false)!!.rate) // nothing fits: lowest
        assertEquals(2, DouyuParse.chooseRate(rates, 1080, true)!!.rate)
        // Source and 蓝光 both 1080: prefer the source (higher bitrate).
        val tie = listOf(DouyuRate(0, "原画1080P60", 1080), DouyuRate(4, "蓝光4M", 1080), DouyuRate(2, "高清", 540))
        assertEquals(0, DouyuParse.chooseRate(tie, 1080, false)!!.rate)
        assertNull(DouyuParse.chooseRate(emptyList(), 1080, false))
    }
}
