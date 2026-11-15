package app.pitstop.video

import app.pitstop.video.core.Updates
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun parsesVersions() {
        assertEquals(listOf(0, 6, 3), Updates.parseVersion("v0.6.3"))
        assertEquals(listOf(1, 0), Updates.parseVersion("1.0-beta2"))
        assertEquals(listOf(0, 7, 0), Updates.parseVersion("0.7.0+build5"))
        assertNull(Updates.parseVersion("latest"))
        assertNull(Updates.parseVersion("v1..2"))
    }

    @Test fun comparesNumerically() {
        assertTrue(Updates.isNewer("v0.6.4", "0.6.3"))
        assertTrue(Updates.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Updates.isNewer("1.0", "0.99.99"))
        assertFalse(Updates.isNewer("v0.6.3", "0.6.3"))
        assertFalse(Updates.isNewer("0.7", "0.7.0"))
        assertFalse(Updates.isNewer("v0.6.2", "0.6.3"))
        assertFalse(Updates.isNewer("nightly", "0.6.3"))
    }

    @Test fun preReleasesComeBeforeTheirFinalVersion() {
        assertTrue(Updates.isNewer("v0.7.0-beta.1", "0.6.4"))
        assertTrue(Updates.isNewer("v0.7.0-beta.2", "0.7.0-beta.1"))
        assertTrue(Updates.isNewer("v0.7.0-beta.10", "0.7.0-beta.9"))
        assertTrue(Updates.isNewer("v0.7.0-rc.1", "0.7.0-beta.3"))
        assertTrue(Updates.isNewer("v0.7.0", "0.7.0-beta.3"))
        assertFalse(Updates.isNewer("v0.7.0-beta.3", "0.7.0"))
        assertFalse(Updates.isNewer("v0.6.4", "0.7.0-beta.1"))
        assertFalse(Updates.isNewer("v0.7.0-beta.1", "0.7.0-beta.1"))
        assertFalse(Updates.isNewer("v0.7.0+build5", "0.7.0"))
        assertEquals("beta.2", Updates.preRelease("v0.7.0-beta.2+x"))
        assertNull(Updates.preRelease("v0.7.0+build5"))
    }

    @Test fun betaChannelPicksTheNewestRelease() {
        fun rel(tag: String, pre: Boolean) = json().put("tag_name", tag).put("prerelease", pre)
        val list = JSONArray().put(rel("v0.7.0-beta.1", true)).put(rel("v0.7.0-beta.2", true))
            .put(rel("v0.6.4", false)).put(json().put("tag_name", "v0.8.0-beta.1").put("draft", true))
        assertEquals("0.7.0-beta.2", Updates.newest(list, allowPreRelease = true)!!.version)
        assertEquals("0.6.4", Updates.newest(list, allowPreRelease = false)!!.version)
        assertEquals("0.7.0", Updates.newest(list.put(rel("v0.7.0", false)), allowPreRelease = true)!!.version)
        assertNull(Updates.newest(JSONArray(), allowPreRelease = true))
    }

    private fun json(extra: String = "", assets: String = APK) = JSONObject(
        """{"tag_name":"v0.6.4","name":"v0.6.4","html_url":"https://github.com/o/r/releases/tag/v0.6.4",
           "body":"  What's new: favourites  ","draft":false,"prerelease":false $extra,"assets":[$assets]}""",
    )

    @Test fun readsLatestRelease() {
        val r = Updates.release(json())!!
        assertEquals("0.6.4", r.version)
        assertEquals("PitstopVideo-v0.6.4.apk", r.apkName)
        assertEquals("https://github.com/o/r/releases/download/v0.6.4/PitstopVideo-v0.6.4.apk", r.apkUrl)
        assertEquals(14866590L, r.apkSize)
        assertEquals("What's new: favourites", r.notes)
    }

    @Test fun skipsDraftsPreReleasesAndReleasesWithoutApk() {
        assertNull(Updates.release(json(""", "x":1""").put("prerelease", true)))
        assertNull(Updates.release(json().put("draft", true)))
        assertNull(Updates.release(json(assets = """{"name":"notes.txt","size":1,"browser_download_url":"https://x/notes.txt"}""")))
        assertNull(Updates.release(json().put("tag_name", "nightly")))
    }

    private companion object {
        const val APK = """{"name":"source.zip","size":5,"browser_download_url":"https://x/s.zip"},
            {"name":"PitstopVideo-v0.6.4.apk","size":14866590,
             "browser_download_url":"https://github.com/o/r/releases/download/v0.6.4/PitstopVideo-v0.6.4.apk"}"""
    }
}
