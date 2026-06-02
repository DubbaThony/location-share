package pl.dubba.share.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps [LocationManager] + [GnssStatus.Callback] behind StateFlows so the UI
 * (and later the connection service) can observe fixes and satellite status
 * reactively. Singleton so state survives Activity recreation.
 *
 * Permission check is done here too - callers can attempt [start] without
 * pre-checking; returns false if permission is missing.
 */
object GpsManager {

    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix.asStateFlow()

    private val _satStatus = MutableStateFlow<GnssSnapshot?>(null)
    val satStatus: StateFlow<GnssSnapshot?> = _satStatus.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Fires when the system-wide location toggle gets switched OFF while we
     * had GPS running. The receiver below detects it via Android's
     * [LocationManager.MODE_CHANGED_ACTION] broadcast and tears down the GPS
     * listener; ConnectionService observes this to fire the LockLost alert
     * with a system-loc-specific detail (since the regular fix-watchdog path
     * may never fire - if no fix had arrived yet, the watchdog was never
     * scheduled).
     */
    private val _systemLocationDisabled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val systemLocationDisabled: SharedFlow<Unit> = _systemLocationDisabled.asSharedFlow()

    private var locationManager: LocationManager? = null

    /** Context used to register/unregister [modeChangedReceiver]; same one passed to start(). */
    private var registeredCtx: Context? = null

    private val modeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != LocationManager.MODE_CHANGED_ACTION) return
            val lm = locationManager ?: return
            if (!_running.value) return
            if (lm.isLocationEnabled) return // re-enabled or unrelated mode change
            pl.dubba.share.net.ConnectionState.appendLog(
                "✗ GPS - system location turned off mid-session",
            )
            // Just signal. ConnectionService's collector owns the teardown
            // ordering (gpsActive=false → fire LockLost(detail) → stop()) so
            // the generic fix-watchdog path doesn't race and overwrite our
            // specific message.
            _systemLocationDisabled.tryEmit(Unit)
        }
    }

    /** Monotonically increasing counter of fixes received from the system. */
    private val fixCounter = java.util.concurrent.atomic.AtomicLong(0L)

    // --- Stale-fix watchdog ----------------------------------------------------
    //
    // Android's LocationListener has no "lost lock" callback - it just goes
    // quiet when the chip can't see the sky anymore. Result: [_fix] stays at
    // whatever the last good value was, [collectLatest] downstream never fires,
    // no payload flows, but the UI still thinks GPS is green. The watchdog
    // resets on every fix; if it expires (no new fix within
    // ~LOCK_LOSS_FACTOR × polling interval, floored at LOCK_LOSS_FLOOR_MS) we
    // null [_fix], which kicks the meta heartbeat into "acquiring" and gets the
    // viewer back into the right state.

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Current minTime we asked LocationManager for; scales the watchdog. */
    @Volatile private var currentMinTimeMs: Long = 1000L

    private val lostLockRunnable = Runnable {
        if (_fix.value != null) {
            _fix.value = null
            pl.dubba.share.net.ConnectionState.appendLog(
                "GPS - lock lost (no fix for >${lockLossThresholdMs()}ms)",
            )
        }
    }

    private fun lockLossThresholdMs(): Long =
        (currentMinTimeMs * LOCK_LOSS_FACTOR).coerceAtLeast(LOCK_LOSS_FLOOR_MS)

    private const val LOCK_LOSS_FACTOR: Long = 3
    private const val LOCK_LOSS_FLOOR_MS: Long = 5_000L

    private val locationListener = LocationListener { loc ->
        val first = _fix.value == null
        val n = fixCounter.incrementAndGet()
        _fix.value = GpsFix(
            lat = loc.latitude,
            lon = loc.longitude,
            accuracyMeters = loc.accuracy,
            altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            timestampMs = loc.time,
        )
        // Reset the stale-fix watchdog on every successful fix. If it fires,
        // the lostLockRunnable nulls [_fix] which kicks the META heartbeat
        // back into "acquiring" so the viewer sees the truth.
        mainHandler.removeCallbacks(lostLockRunnable)
        mainHandler.postDelayed(lostLockRunnable, lockLossThresholdMs())
        if (first) {
            pl.dubba.share.net.ConnectionState.appendLog(
                "GPS first fix - ±${loc.accuracy.toInt()}m, speed=${if (loc.hasSpeed()) "%.1f".format(loc.speed) else "?"} m/s",
            )
        } else {
            // Every fix logged so we can trace whether the gap is here or
            // downstream when payloads stop flowing.
            pl.dubba.share.net.ConnectionState.appendLog(
                "GPS fix #$n - ±${loc.accuracy.toInt()}m, ${"%.1f".format(loc.speed * 3.6f)} km/h",
            )
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val total = status.satelliteCount
            var used = 0
            var topSnr = 0f
            for (i in 0 until total) {
                if (status.usedInFix(i)) used++
                val snr = status.getCn0DbHz(i)
                if (snr > topSnr) topSnr = snr
            }
            _satStatus.value = GnssSnapshot(total = total, usedInFix = used, topSnrDbHz = topSnr)
        }

        override fun onStarted() {
            _satStatus.value = GnssSnapshot(total = 0, usedInFix = 0, topSnrDbHz = 0f)
        }

        override fun onStopped() {
            _satStatus.value = null
        }
    }

    fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(context: Context, minTimeMs: Long = 1000L, minDistanceMeters: Float = 0f): Boolean {
        if (!hasFineLocationPermission(context)) return false
        if (_running.value) return true
        val lm = context.applicationContext.getSystemService(LocationManager::class.java)
            ?: return false
        // System-level location must be ON, otherwise requestLocationUpdates
        // silently succeeds, _running flips true, the UI's LED goes pending,
        // and no fix ever arrives - visible state is "looks like it's working"
        // for a session that's never going to deliver. Bail here so the
        // caller (and the UI) can react with a real "off" state + toast.
        // (isLocationEnabled is API 28+; minSdk is 29 - safe unconditional.)
        if (!lm.isLocationEnabled) {
            pl.dubba.share.net.ConnectionState.appendLog(
                "✗ GPS start failed - system location is disabled",
            )
            return false
        }
        locationManager = lm
        currentMinTimeMs = minTimeMs
        return try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistanceMeters,
                locationListener,
                Looper.getMainLooper(),
            )
            lm.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
            // Listen for system-wide location toggle flipping OFF while we run.
            // Uses ContextCompat to set the not-exported flag uniformly across
            // API levels (required on 34+ for app-context receivers, no-op below).
            val appCtx = context.applicationContext
            ContextCompat.registerReceiver(
                appCtx,
                modeChangedReceiver,
                IntentFilter(LocationManager.MODE_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registeredCtx = appCtx
            _running.value = true
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            // GPS provider unavailable on this device
            false
        }
    }

    fun stop() {
        if (!_running.value) return
        val lm = locationManager ?: return
        lm.removeUpdates(locationListener)
        lm.unregisterGnssStatusCallback(gnssCallback)
        mainHandler.removeCallbacks(lostLockRunnable)
        // Unregister the mode-change receiver. Safe to call even if we got
        // here FROM inside that receiver - Android documents that flow.
        registeredCtx?.let { ctx ->
            try {
                ctx.unregisterReceiver(modeChangedReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered (paranoia: e.g. start() failed
                // partway and we never got to register).
            }
        }
        registeredCtx = null
        locationManager = null
        _fix.value = null
        _satStatus.value = null
        _running.value = false
    }

    /**
     * Re-registers the location listener with a new minimum-time-between-fixes.
     * No-op if not currently running. Per Android docs, calling
     * `requestLocationUpdates` again with the same listener updates the
     * registration parameters in place - no need to stop / start.
     */
    @SuppressLint("MissingPermission")
    fun updateRate(minTimeMs: Long, minDistanceMeters: Float = 0f): Boolean {
        if (!_running.value) return false
        val lm = locationManager ?: return false
        return try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistanceMeters,
                locationListener,
                Looper.getMainLooper(),
            )
            currentMinTimeMs = minTimeMs
            // If a watchdog is already pending, reschedule with the new
            // threshold so faster polling produces faster lock-loss detection
            // and vice-versa.
            if (_fix.value != null) {
                mainHandler.removeCallbacks(lostLockRunnable)
                mainHandler.postDelayed(lostLockRunnable, lockLossThresholdMs())
            }
            true
        } catch (_: SecurityException) { false }
        catch (_: IllegalArgumentException) { false }
    }
}

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val bearingDeg: Float?,
    val timestampMs: Long,
)

data class GnssSnapshot(
    val total: Int,
    val usedInFix: Int,
    val topSnrDbHz: Float,
)
