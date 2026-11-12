package app.pitstop.video.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hysteresis on speed: becomes "moving" above [startMps], and only "stopped" again after the
 * speed has stayed below [stopMps] for [stopHoldMs]. Unknown speed keeps the last state.
 */
class MotionLatch(
    private val startMps: Float = 2.5f,   // ~9 km/h
    private val stopMps: Float = 0.8f,    // ~3 km/h
    private val stopHoldMs: Long = 3_000,
) {
    var moving = false
        private set
    private var slowSince: Long? = null

    fun update(speedMps: Float?, nowMs: Long): Boolean {
        if (speedMps == null) return moving
        if (speedMps >= startMps) {
            moving = true
            slowSince = null
        } else if (moving && speedMps <= stopMps) {
            val since = slowSince ?: nowMs.also { slowSince = it }
            if (nowMs - since >= stopHoldMs) {
                moving = false
                slowSince = null
            }
        } else if (speedMps > stopMps) {
            slowSince = null
        }
        return moving
    }
}

/**
 * Tells the player whether the car is moving. Video on the car screen is only allowed while parked.
 * Signals: car speed from Android Auto (preferred), phone GPS speed as a fallback.
 */
class DrivingMonitor(private val context: Context) {
    enum class Signal { NONE, CAR, PHONE_GPS }

    private val latch = MotionLatch()
    private val _moving = MutableStateFlow(false)
    val moving: StateFlow<Boolean> = _moving.asStateFlow()
    private val _signal = MutableStateFlow(Signal.NONE)
    val signal: StateFlow<Signal> = _signal.asStateFlow()
    private val _speedMps = MutableStateFlow<Float?>(null)
    val speedMps: StateFlow<Float?> = _speedMps.asStateFlow()

    private var lastCarSpeedAt = 0L

    fun onCarSpeed(mps: Float) {
        lastCarSpeedAt = SystemClock.elapsedRealtime()
        _signal.value = Signal.CAR
        report(mps)
    }

    private fun onPhoneSpeed(mps: Float) {
        // Ignore GPS while the car is feeding us fresh speed data.
        if (SystemClock.elapsedRealtime() - lastCarSpeedAt < 5_000) return
        _signal.value = Signal.PHONE_GPS
        report(mps)
    }

    private fun report(mps: Float) {
        _speedMps.value = mps
        _moving.value = latch.update(mps, SystemClock.elapsedRealtime())
    }

    private val locationListener = LocationListener { loc -> if (loc.hasSpeed()) onPhoneSpeed(loc.speed) }
    private var gpsActive = false
    /** GPS is shared by the Android Auto session and the on-device player; it runs while anyone needs it. */
    private val gpsUsers = mutableSetOf<String>()

    fun hasLocationPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startPhoneGps(user: String) {
        gpsUsers += user
        if (gpsActive || !hasLocationPermission()) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener, Looper.getMainLooper())
            gpsActive = true
        }
    }

    fun stopPhoneGps(user: String) {
        gpsUsers -= user
        if (!gpsActive || gpsUsers.isNotEmpty()) return
        context.getSystemService(LocationManager::class.java)?.removeUpdates(locationListener)
        gpsActive = false
    }
}
