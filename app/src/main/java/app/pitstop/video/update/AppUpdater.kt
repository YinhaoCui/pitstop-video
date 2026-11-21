package app.pitstop.video.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import app.pitstop.video.BuildConfig
import app.pitstop.video.core.AppRelease
import app.pitstop.video.core.I18n
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.Updates
import app.pitstop.video.graph
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Check for updates": reads the latest release of the app's GitHub repository ([BuildConfig.UPDATE_REPO]),
 * downloads its APK and hands it to Android's package installer, which asks the user to confirm.
 *
 * Android only installs an update signed with the same key as the installed app, so the APK is checked
 * (package name, version code, signing certificate) before the installer is opened.
 */
class AppUpdater(private val context: Context, private val http: OkHttpClient) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: AppRelease) : State
        /** [progress] 0..1, null while the size is unknown. */
        data class Downloading(val release: AppRelease, val progress: Float?) : State
        /** Handed to the system installer; the app restarts on the new version once the user confirms. */
        data class Installing(val release: AppRelease) : State
        /** "Install unknown apps" is off for this app: the user has to allow it in system settings first. */
        data class NeedsPermission(val release: AppRelease) : State
        data class Failed(val message: String, val release: AppRelease? = null) : State
    }

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    /** Check once a day when the app opens, and offer a new version. */
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    fun setAutoCheck(on: Boolean) {
        _autoCheck.value = on
        prefs.edit().putBoolean(KEY_AUTO, on).apply()
    }

    private val _beta = MutableStateFlow(prefs.getBoolean(KEY_BETA, false))
    /**
     * Beta channel: GitHub pre-releases (tags like v0.7.0-beta.1) are offered too. They install over the
     * app like any update. Turning it off doesn't go back: the app stays on the beta until a newer final
     * version is released (Android doesn't install a lower version code over a higher one).
     */
    val beta: StateFlow<Boolean> = _beta.asStateFlow()

    fun setBeta(on: Boolean) {
        _beta.value = on
        // The next launch check looks again, for the channel now chosen.
        prefs.edit().putBoolean(KEY_BETA, on).remove(KEY_LAST_CHECK).apply()
        if (_state.value is State.UpToDate || _state.value is State.Available || _state.value is State.Failed) _state.value = State.Idle
    }

    val currentVersion: String = installedInfo()?.versionName.orEmpty()

    /**
     * Mirror Video (nav build) only installs through Shizuku, which keeps the Play Store as its recorded
     * installer (see [PlayInstall], [ShizukuInstall]); without it, it only offers the release page.
     */
    val canInstall: Boolean get() = !BuildConfig.NAV_ROUTE || ShizukuInstall.running()

    /** Asks GitHub for the latest release (or the newest pre-release, on the beta channel). */
    suspend fun check(): State {
        _state.value = State.Checking
        val result = try {
            val release = latestRelease()
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (release != null && Updates.isNewer(release.version, currentVersion)) State.Available(release) else State.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            State.Failed(I18n.s.updateCheckFailed(e.message ?: e.javaClass.simpleName))
        }
        _state.value = result
        return result
    }

    /**
     * The launch check: at most once a day, when [autoCheck] is on. Returns a release to offer, or null
     * (none, the user already said "Later" to it, or the check failed: no error is shown at launch).
     */
    suspend fun checkIfDue(): AppRelease? {
        if (!_autoCheck.value || _state.value !is State.Idle) return null
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < DAY_MS) return null
        val r = (check() as? State.Available)?.release
        if (r == null && _state.value is State.Failed) _state.value = State.Idle
        return r?.takeIf { it.tag != prefs.getString(KEY_SKIPPED, null) }
    }

    /** "Later" on the launch prompt: don't offer this version again automatically (Settings still shows it). */
    fun skip(release: AppRelease) = prefs.edit().putString(KEY_SKIPPED, release.tag).apply()

    /** Runs on the app's scope, so leaving Settings or closing the prompt doesn't stop a download. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Starts [downloadAndInstall] unless an update is already downloading / installing. Call on the main thread. */
    fun start(release: AppRelease) {
        // Mirror Video is never installed the usual way: that would make Android Auto hide it (see PlayInstall).
        if (!canInstall) return
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = State.Downloading(release, null)
        scope.launch { downloadAndInstall(release) }
    }

    /**
     * Mirror Video, installed from the phone (Android Auto hides it): installs the newest release again
     * through Shizuku, which records the Play Store as installer. The same version is fine here.
     */
    fun startRepair() {
        if (!BuildConfig.NAV_ROUTE || !canInstall) return
        if (_state.value is State.Checking || _state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = State.Checking
        scope.launch {
            val release = try {
                latestRelease()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(I18n.s.updateCheckFailed(e.message ?: e.javaClass.simpleName))
                return@launch
            }
            if (release == null) _state.value = State.Failed(I18n.s.updateCheckFailed("no release"))
            else downloadAndInstall(release, allowSameVersion = true)
        }
    }

    fun startCheck() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        scope.launch { check() }
    }

    /** Back from the permission screen: continue when it was granted. */
    fun resumeAfterPermission() {
        // Mirror Video: back from the system installer without installing (a success replaces the app).
        (_state.value as? State.Installing)?.let { if (BuildConfig.NAV_ROUTE) _state.value = State.Available(it.release) }
        val s = _state.value as? State.NeedsPermission ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) start(s.release)
    }

    /** Downloads [release]'s APK, checks it and opens the system installer. */
    private suspend fun downloadAndInstall(release: AppRelease, allowSameVersion: Boolean = false) {
        val nav = BuildConfig.NAV_ROUTE
        if (!nav && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release)
            return
        }
        try {
            if (nav && !ShizukuInstall.ensurePermission()) throw UserFacingException(I18n.s.shizukuDenied)
            _state.value = State.Downloading(release, null)
            // Shizuku's shell can't read the app's private cache: the external files folder instead.
            val dir = if (nav) context.getExternalFilesDir(null) ?: throw IOException("No external storage") else context.cacheDir
            val apk = download(release, File(dir, "updates"))
            verify(apk, allowSameVersion)
            _state.value = State.Installing(release)
            if (nav) withContext(Dispatchers.IO) { ShizukuInstall.install(apk) } else install(apk)
        } catch (e: CancellationException) {
            _state.value = State.Available(release)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName, release)
        }
    }

    /** System screen where the user allows this app to install apps (Android 8+). */
    fun permissionIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- steps ----

    private suspend fun latestRelease(): AppRelease? = withContext(Dispatchers.IO) {
        val beta = _beta.value
        // releases/latest never returns a pre-release; the beta channel reads the recent releases instead.
        val path = if (beta) "releases?per_page=30" else "releases/latest"
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/$path")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        // Its own client: no site cookies on requests to GitHub.
        http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build().newCall(req).execute().use { resp ->
            // 404: the repository has no published release yet.
            if (resp.code == 404) return@use null
            if (!resp.isSuccessful) throw IOException("GitHub HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            // Each build updates to its own APK in the release (parked / nav).
            val nav = BuildConfig.NAV_ROUTE
            if (beta) Updates.newest(JSONArray(body), allowPreRelease = true, nav = nav) else Updates.release(JSONObject(body), nav = nav)
        }
    }

    private suspend fun download(release: AppRelease, dir: File): File = withContext(Dispatchers.IO) {
        dir.deleteRecursively(); dir.mkdirs()
        val file = File(dir, "update.apk")
        val client = http.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) { shown = pct; _state.value = State.Downloading(release, done.toFloat() / total) }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) throw IOException(I18n.s.updateDownloadIncomplete)
        }
        file
    }

    /** Same package, a higher version code and the same signing key as the installed app. */
    private fun verify(apk: File, allowSameVersion: Boolean = false) {
        val archive = archiveInfo(apk) ?: throw UserFacingException(I18n.s.updateBadApk)
        if (archive.packageName != context.packageName) throw UserFacingException(I18n.s.updateOtherPackage(archive.packageName))
        val installed = installedInfo() ?: throw UserFacingException(I18n.s.updateBadApk)
        val older = versionCode(archive).compareTo(versionCode(installed)).let { if (allowSameVersion) it < 0 else it <= 0 }
        if (older) throw UserFacingException(I18n.s.updateNotNewer)
        if (signatures(archive) != signatures(installed)) throw UserFacingException(I18n.s.updateWrongKey)
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** The installer's answer (see [InstallResultReceiver]). */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (_state.value as? State.Installing)?.release
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> State.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { State.Available(it) } ?: State.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                State.Failed(I18n.s.updateWrongKey, release)
            PackageInstaller.STATUS_FAILURE_STORAGE -> State.Failed(I18n.s.updateNoSpace, release)
            else -> State.Failed(I18n.s.updateInstallFailed(message ?: "status $status"), release)
        }
    }

    // ---- package info ----

    private fun installedInfo(): PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, signingFlag())
    }.getOrNull()

    private fun archiveInfo(apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlag())

    @Suppress("DEPRECATION")
    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else info.signatures
        return sigs.orEmpty().filterNotNull().map { it.toCharsString() }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private companion object {
        const val TAG = "AppUpdater"
        const val KEY_AUTO = "auto_check"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_SKIPPED = "skipped_tag"
        const val KEY_BETA = "beta_channel"
        const val DAY_MS = 24 * 60 * 60_000L
    }
}

/**
 * Status of an install session: asks the user to confirm (the system's install screen), then reports
 * the outcome to [AppUpdater]. On success the app process is replaced by the new version.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            return
        }
        context.graph.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
