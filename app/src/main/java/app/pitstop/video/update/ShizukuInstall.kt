package app.pitstop.video.update

import android.content.pm.PackageManager
import app.pitstop.video.core.ShellInstall
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku

/**
 * Mirror Video's way to update itself on the phone. Android only records the Play Store as installer
 * (see [PlayInstall]) when the *shell* user starts the install; Shizuku (a separate app the user sets
 * up once over wireless debugging) lets this app run commands as that user, so it can do what
 * `tools/install-mirror-video.sh` does from a computer.
 */
object ShizukuInstall {
    private const val REQUEST = 7401

    /** Shizuku is installed and its service is running. */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)

    private fun granted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Asks the user (Shizuku's own dialog) when needed. False when Shizuku isn't running or they said no. */
    suspend fun ensurePermission(): Boolean {
        if (!running()) return false
        if (granted()) return true
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) return false
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQUEST) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            runCatching { Shizuku.requestPermission(REQUEST) }.onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /**
     * Opens the system installer on [apk] as the shell user ([ShellInstall.script]); the user confirms
     * there. [apk] must be readable by the shell: a file in the app's *external* files folder. Blocking.
     */
    fun install(apk: File) {
        val process = shell(ShellInstall.script(apk.absolutePath))
        val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
        process.inputStream.close()
        val code = process.waitFor()
        if (code != 0) throw IOException(error.lineSequence().lastOrNull { it.isNotBlank() } ?: "exit $code")
    }

    /**
     * `Shizuku.newProcess` is the simple way to run one command, but the library hides it (it prefers a
     * bound user service); it is still there in Shizuku 13, so it is called by reflection.
     */
    private fun shell(script: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
    }
}
