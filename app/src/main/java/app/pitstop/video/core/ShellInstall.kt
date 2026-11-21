package app.pitstop.video.core

/**
 * The shell commands that install an APK so that Android records the Play Store as its installer
 * (what real cars' Android Auto wants from Mirror Video, see `update/PlayInstall.kt`). They only have
 * that effect when the *shell* user runs them: adb (`tools/install-mirror-video.sh`, the same steps)
 * or, on the phone, Shizuku (`update/ShizukuInstall.kt`). Pure, unit-tested.
 */
object ShellInstall {
    /** Name of the copy in the phone's Download folder (overwritten by every install). */
    const val STAGED_NAME = "mirror-video-install.apk"

    /**
     * A `sh` script: copies [apkPath] (a file the shell user can read, e.g. in the app's external files
     * folder) to Download, waits for MediaStore to index it (the system installer takes `content://`
     * links, not `file://`) and opens the system installer on it. Exits non-zero when a step fails.
     */
    fun script(apkPath: String): String {
        require('\'' !in apkPath && '\n' !in apkPath) { "unsupported path" }
        val staged = "/sdcard/Download/$STAGED_NAME"
        return listOf(
            "set -e",
            "cp '$apkPath' $staged",
            "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$staged >/dev/null",
            "ID=",
            "for i in 1 2 3 4 5 6 7 8 9 10; do",
            "  ID=\$(content query --uri content://media/external/downloads --projection _id" +
                " --where \"_display_name='$STAGED_NAME'\" | sed -n 's/.*_id=\\([0-9]*\\).*/\\1/p' | tail -1)",
            "  [ -n \"\$ID\" ] && break",
            "  sleep 1",
            "done",
            "[ -n \"\$ID\" ] || { echo 'The phone did not index the file' >&2; exit 3; }",
            // --grant-read-uri-permission: without it the installer may get no access and close silently.
            "am start --grant-read-uri-permission -a android.intent.action.INSTALL_PACKAGE" +
                " -d content://media/external/downloads/\$ID -t application/vnd.android.package-archive" +
                " --ez android.intent.extra.NOT_UNKNOWN_SOURCE true" +
                " --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending >/dev/null",
        ).joinToString("\n")
    }
}
