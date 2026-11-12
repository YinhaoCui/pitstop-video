package app.pitstop.video

import android.app.Application
import android.content.Context
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
    }
}

/** Hand-wired singletons shared by the phone UI and the Android Auto session. */
class AppGraph(app: Application) {
    val biliCookies = BiliCookieJar(app.getSharedPreferences("bilibili", Context.MODE_PRIVATE))
    val http = buildHttpClient(biliCookies)
    val biliApi = BiliApi(http, biliCookies)
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

    /** Created lazily on the main thread (ExoPlayer must be built on a Looper thread). */
    val hub: PlaybackHub by lazy { PlaybackHub(app, sources.associateBy { it.id }, http, driving) }

    init {
        NewPipe.init(NewPipeDownloader(http))
    }
}

val Context.graph: AppGraph get() = (applicationContext as PitstopApp).graph
