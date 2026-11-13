package app.pitstop.video.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.AppManager
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.pitstop.video.graph

class VideoCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = VideoSession()
}

class VideoSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val graph = carContext.graph
        val hub = graph.hub
        val renderer = CarVideoRenderer(carContext, hub)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)

        val speedListener = OnCarDataAvailableListener<Speed> { speed ->
            val v = speed.rawSpeedMetersPerSecond
            if (v.status == CarValue.STATUS_SUCCESS) v.value?.let { graph.driving.onCarSpeed(Math.abs(it)) }
        }
        val carInfo = runCatching { carContext.getCarService(CarHardwareManager::class.java).carInfo }.getOrNull()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                runCatching { carInfo?.addSpeedListener(ContextCompat.getMainExecutor(carContext), speedListener) }
                graph.driving.startPhoneGps("car")
                // Media session: steering-wheel / Bluetooth buttons and keeping the process alive.
                app.pitstop.video.player.PlaybackService.start(carContext)
            }

            // Leaving the app on the car screen (opening Maps, Phone, ...) pauses the video.
            override fun onStop(owner: LifecycleOwner) {
                hub.player.pause()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                hub.player.pause()
                runCatching { carInfo?.removeSpeedListener(speedListener) }
                graph.driving.stopPhoneGps("car")
                renderer.release()
            }
        })
        return PlayerScreen(carContext, hub)
    }
}
