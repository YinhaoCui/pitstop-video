package app.pitstop.video

import app.pitstop.video.bilibili.BiliPaid
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaidContentTest {
    @Test
    fun biliTrialClipIsRefused() {
        assertTrue(BiliPaid.isPreview(JSONObject("""{"quality":32,"is_preview":1,"durl":[{"url":"x"}]}""")))
        assertTrue(BiliPaid.isPreview(JSONObject("""{"is_preview":true}""")))
    }

    @Test
    fun normalVideoPlays() {
        assertFalse(BiliPaid.isPreview(JSONObject("""{"quality":80,"is_preview":0,"dash":{}}""")))
        assertFalse(BiliPaid.isPreview(JSONObject("""{"quality":80,"dash":{}}""")))
    }
}
