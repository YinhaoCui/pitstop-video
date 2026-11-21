package app.pitstop.video

import app.pitstop.video.core.ShellInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellInstallTest {
    private val script = ShellInstall.script("/storage/emulated/0/Android/data/app.pitstop.video/files/update.apk")

    @Test fun copiesTheApkToDownload() {
        assertTrue(script.startsWith("set -e\n"))
        assertTrue("cp '/storage/emulated/0/Android/data/app.pitstop.video/files/update.apk' /sdcard/Download/mirror-video-install.apk" in script)
    }

    /** What makes Android record the Play Store as installer, and what lets the installer read the file. */
    @Test fun opensTheInstallerAsPlayStoreInstall() {
        val start = script.lines().last()
        assertTrue(start.startsWith("am start --grant-read-uri-permission -a android.intent.action.INSTALL_PACKAGE"))
        assertTrue("-d content://media/external/downloads/\$ID " in start)
        assertTrue("--ez android.intent.extra.NOT_UNKNOWN_SOURCE true" in start)
        assertTrue("--es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending" in start)
    }

    @Test fun looksTheFileUpByName() {
        assertTrue("--where \"_display_name='mirror-video-install.apk'\"" in script)
        assertTrue("sed -n 's/.*_id=\\([0-9]*\\).*/\\1/p'" in script)
        assertEquals(1, script.lines().count { it.contains("exit 3") })
    }

    @Test fun refusesPathsItCannotQuote() {
        assertThrows(IllegalArgumentException::class.java) { ShellInstall.script("/sdcard/it's.apk") }
    }
}
