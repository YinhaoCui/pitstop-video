package app.pitstop.video

import app.pitstop.video.bilibili.BiliCdn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliCdnTest {
    private val pcdn = "https://xy113x0x0x1xy.mcdn.bilivideo.cn:4483/upgcxcode/40/63/1/1-1-30080.m4s?e=abc"
    private val upos = "https://upos-sz-estgoss.bilivideo.com/upgcxcode/40/63/1/1-1-30080.m4s?e=abc"
    private val akamai = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/40/63/1/1-1-30080.m4s?e=abc"
    private val ip = "https://123.45.67.89/v1/resource/x.m4s?e=1"

    @Test fun skipsPeerToPeerNodes() {
        assertEquals(upos, BiliCdn.choose(listOf(pcdn, ip, upos), overseas = false))
        assertEquals(upos, BiliCdn.choose(listOf(pcdn, upos), overseas = true))
    }

    @Test fun prefersAkamaiOnlyOverseas() {
        assertEquals(akamai, BiliCdn.choose(listOf(upos, akamai), overseas = true))
        assertEquals(upos, BiliCdn.choose(listOf(akamai, upos), overseas = false))
    }

    @Test fun neverRewritesAndFallsBackToWhatExists() =
        assertEquals(pcdn, BiliCdn.choose(listOf(pcdn), overseas = true))

    @Test fun noUsableUrls() = assertNull(BiliCdn.choose(listOf("", "ftp://x"), overseas = true))

    @Test fun speedTestsEachRealServerOnce() {
        val upos2 = upos.replace("e=abc", "e=def")
        assertEquals(
            listOf(akamai, upos),
            BiliCdn.speedTestCandidates(listOf(pcdn, upos, upos2, akamai, ip), overseas = true),
        )
        assertEquals(listOf(akamai), BiliCdn.speedTestCandidates(listOf(upos, akamai), overseas = true, max = 1))
        assertEquals(emptyList<String>(), BiliCdn.speedTestCandidates(listOf(pcdn, ip), overseas = true))
    }
}
