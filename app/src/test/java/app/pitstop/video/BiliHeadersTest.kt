package app.pitstop.video

import app.pitstop.video.core.withBiliHeaders
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliHeadersTest {
    private fun req(url: String, range: String? = null) =
        Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build()

    @Test fun videoServersAlwaysGetARange() {
        // Without one, upos-sz-mirrorcosov answers HTTP 567.
        val out = withBiliHeaders(req("https://upos-sz-mirrorcosov.bilivideo.com/upgcxcode/1-30080.m4s"))
        assertEquals("bytes=0-", out.header("Range"))
        assertEquals("https://www.bilibili.com/", out.header("Referer"))
        // The player's own range (seeking, resuming) is kept.
        assertEquals("bytes=500-", withBiliHeaders(req("https://x.mcdn.bilivideo.cn/a.m4s", "bytes=500-")).header("Range"))
    }

    @Test fun apiAndOtherHostsGetNoRange() {
        assertNull(withBiliHeaders(req("https://api.bilibili.com/x/web-interface/nav")).header("Range"))
        val akamai = withBiliHeaders(req("https://upos-hz-mirrorakam.akamaized.net/a.m4s"))
        assertNull(akamai.header("Range"))
        assertNull(akamai.header("Referer"))
    }
}
