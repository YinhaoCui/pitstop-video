package app.pitstop.video.update

import android.content.Context
import android.os.Build

/**
 * Nav build (Mirror Video) only. Android Auto shows a sideloaded Car App Library *navigation* app on
 * real cars only when the Play Store is recorded as its installer (otherwise: `CAR.VALIDATOR: Package
 * DENIED; failed all other checks`).
 *
 * Android records that only for an install started from adb: the system installer intent
 * `ACTION_INSTALL_PACKAGE` with `EXTRA_INSTALLER_PACKAGE_NAME=com.android.vending`, sent by the shell
 * (`tools/install-mirror-video.sh`). Sent by the app itself, the extra is ignored and the installer
 * becomes `com.google.android.packageinstaller`; `adb install -i com.android.vending` doesn't count
 * either (initiated by `com.android.shell`). Any normal install from the phone resets it, so the app
 * only installs its own updates through Shizuku ([ShizukuInstall]: the same intent, sent by the shell user).
 */
object PlayInstall {
    private const val PLAY_STORE = "com.android.vending"

    /** True when Android Auto will accept the installed app: the Play Store is its recorded installer. */
    fun isRecordedAsPlay(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)
}
