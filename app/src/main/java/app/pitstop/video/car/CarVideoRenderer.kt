package app.pitstop.video.car

import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import app.pitstop.video.player.PlaybackHub

/**
 * Receives the Android Auto drawing surface and mirrors a [CarPresentation] onto it through a
 * private VirtualDisplay. This lets us use normal Android views (PlayerView) on the car screen.
 */
class CarVideoRenderer(
    private val carContext: CarContext,
    private val hub: PlaybackHub,
) : SurfaceCallback {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: CarPresentation? = null
    private var visibleArea: Rect? = null

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "PitstopCarDisplay", container.width, container.height, container.dpi, surface, 0
        )
        virtualDisplay = vd
        presentation = CarPresentation(carContext, vd.display, hub).also { p ->
            p.show()
            visibleArea?.let(p::setVisibleArea)
        }
        hub.setCarActive(true)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        presentation?.setVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) = Unit

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    /** A tap brings back Android Auto's buttons; show the title with them, but never pause. */
    override fun onClick(x: Float, y: Float) = hub.pokeInfo()

    fun release() {
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        hub.setCarActive(false)
    }
}
