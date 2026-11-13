package app.pitstop.video.ui

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import app.pitstop.video.core.Settings
import app.pitstop.video.player.PlaybackService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.pitstop.video.core.I18n
import app.pitstop.video.core.LocalStrings
import app.pitstop.video.core.Strings
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.pitstop.video.graph

class MainActivity : ComponentActivity() {
    private val inPip = mutableStateOf(false)
    private val fullscreen = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val language by I18n.language.collectAsState()
            CompositionLocalProvider(LocalStrings provides Strings(language)) {
            PitstopTheme {
                androidx.compose.foundation.layout.Box {
                    AppRoot(
                        graph = graph,
                        inPip = inPip.value,
                        fullscreen = fullscreen.value,
                        onFullscreenChange = ::setFullscreen,
                    )
                    // Cold start only (not on rotation / return from background).
                    if (savedInstanceState == null && !inPip.value) OpeningScreen()
                }
            }
            }
        }
        // Android 12+: swipe home while a video plays -> smooth automatic picture-in-picture.
        if (Build.VERSION.SDK_INT >= 31) {
            lifecycleScope.launch {
                graph.hub.state.map { it.isPlaying && !it.carActive }.distinctUntilChanged().collect { auto ->
                    runCatching {
                        setPictureInPictureParams(
                            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
                                .setAutoEnterEnabled(auto).setSeamlessResizeEnabled(true).build()
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (graph.inCarDevice) graph.driving.startPhoneGps("device")
        // The media session service gives background playback, notification and lock-screen controls.
        PlaybackService.start(this)
        graph.hub.setPhoneBackground(false)
    }

    override fun onStop() {
        graph.driving.stopPhoneGps("device")
        if (!isChangingConfigurations && !graph.hub.state.value.carActive) {
            // Screen off / another app: keep the sound (picture paused to save data) or pause.
            if (Settings.backgroundPlay.value) graph.hub.setPhoneBackground(true) else graph.hub.player.pause()
        }
        super.onStop()
    }

    /** Phone full-screen: landscape + hidden system bars. */
    private fun setFullscreen(on: Boolean) {
        fullscreen.value = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Leaving the app while a video plays on the phone shrinks it into picture-in-picture. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = graph.hub.state.value
        if (Build.VERSION.SDK_INT < 31 && graph.hub.player.isPlaying && !s.carActive) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }
}
