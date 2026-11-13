package app.pitstop.video

import app.pitstop.video.netease.NeteaseCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCryptoTest {
    @Test
    fun weapiParamsDecryptBackToJson() {
        val json = """{"s":"晴天","type":1,"limit":30}"""
        val key = "abcdefghijklmnop"
        val w = NeteaseCrypto.weapi(json, key)
        val once = NeteaseCrypto.aesDecrypt(w.params, key)
        assertEquals(json, NeteaseCrypto.aesDecrypt(once, "0CoJUm6Qyw8W8jud"))
    }

    @Test
    fun rsaOfKnownKeyMatchesWebPlayer() {
        // Widely used fixed pair from music.163.com's own core.js (secret key of 16 'F's).
        assertEquals(
            "257348aecb5e556c066de214e531faadd1c55d814f9be95fd06d6bff9f4c7a41f831f6394d5a3fd2e3881736d94a02ca919d952872e7d0a50ebfa1769a7a62d512f5f1ca21aec60bc3819a9c3ffca5eca9a0dba6d6f7249b06f5965ecfff3695b54e1c28f3f624750ed39e7de08fc8493242e26dbc4484a01c76f739e135637c",
            NeteaseCrypto.rsa("FFFFFFFFFFFFFFFF"),
        )
    }

    @Test
    fun randomKeyIsSixteenAlphanumerics() {
        val k = NeteaseCrypto.randomKey()
        assertEquals(16, k.length)
        assertTrue(k.all { it.isLetterOrDigit() })
    }
}
