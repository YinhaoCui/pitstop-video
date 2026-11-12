package app.pitstop.video

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.pitstop.video.car.VideoCarAppService
import app.pitstop.video.bilibili.BiliApi
import app.pitstop.video.bilibili.BiliCookieJar
import app.pitstop.video.bilibili.BiliSource
import app.pitstop.video.core.PlaylistStore
import app.pitstop.video.core.SourceId
import java.io.File
import app.pitstop.video.core.ThumbnailLoader
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.buildHttpClient
import app.pitstop.video.local.LocalSource
import app.pitstop.video.player.DrivingMonitor
import app.pitstop.video.player.PlaybackHub
import app.pitstop.video.youtube.NewPipeDownloader
import app.pitstop.video.youtube.YouTubeAccount
import app.pitstop.video.youtube.YouTubeSource
import org.schabi.newpipe.extractor.NewPipe

class PitstopApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Android Automotive OS runs the app's own screens; the Android Auto (projection) service is unused there.
        if (graph.isAutomotiveOs) {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, VideoCarAppService::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

/** Hand-wired singletons shared by the phone UI and the Android Auto session. */
class AppGraph(app: Application) {
    val biliCookies = BiliCookieJar(app.getSharedPreferences("bilibili", Context.MODE_PRIVATE))
    val http = buildHttpClient(biliCookies)
    val biliApi = BiliApi(http, biliCookies, app.cacheDir)
    val youTubeAccount = YouTubeAccount(app, http)
    val thumbnails = ThumbnailLoader(app, http)
    val driving = DrivingMonitor(app)
    val playlists = PlaylistStore(File(app.filesDir, "playlists.json"))

    val sources: List<VideoSource> = listOf(
        YouTubeSource(youTubeAccount),
        BiliSource(biliApi, biliCookies),
        LocalSource(app),
    )

    fun source(id: SourceId): VideoSource = sources.first { it.id == id }

    private val devicePrefs = app.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** Android Automotive OS (Google built-in cars): the app runs directly on the car screen. */
    val isAutomotiveOs: Boolean = app.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

    /** True when this device is a car screen, so on-device playback obeys the parked-only lock. */
    var inCarDevice: Boolean
        get() = isAutomotiveOs || devicePrefs.getBoolean("in_car_device", false)
        set(value) {
            devicePrefs.edit().putBoolean("in_car_device", value).apply()
            hub.setInCarDevice(isAutomotiveOs || value)
        }

    /** Created lazily on the main thread (ExoPlayer must be built on a Looper thread). */
    val hub: PlaybackHub by lazy {
        PlaybackHub(app, sources.associateBy { it.id }, http, driving).also { it.setInCarDevice(inCarDevice) }
    }

    init {
        NewPipe.init(NewPipeDownloader(http))
    }
}

val Context.graph: AppGraph get() = (applicationContext as PitstopApp).graph
