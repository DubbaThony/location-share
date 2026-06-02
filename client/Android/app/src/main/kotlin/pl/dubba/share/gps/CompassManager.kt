package pl.dubba.share.gps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton compass - derives device azimuth from [Sensor.TYPE_ROTATION_VECTOR]
 * (accelerometer + magnetometer + gyroscope fused by the OS), exposed as a
 * [StateFlow] of bearing in degrees clockwise from magnetic north
 * (0 = N, 90 = E, 180 = S, 270 = W). Null while not running OR if the device
 * doesn't have the rotation-vector sensor.
 *
 * Used as the fallback direction source when GPS-derived bearing isn't
 * available (stationary device, no previous fix yet, or user has forced
 * compass-only mode in settings).
 */
object CompassManager {
    private val _bearing = MutableStateFlow<Float?>(null)
    val bearing: StateFlow<Float?> = _bearing.asStateFlow()

    private var sensorManager: SensorManager? = null
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val deg = Math.toDegrees(orientation[0].toDouble())
            _bearing.value = ((deg + 360.0) % 360.0).toFloat()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(context: Context): Boolean {
        if (sensorManager != null) return true
        val sm = context.applicationContext.getSystemService(SensorManager::class.java)
            ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return false
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager = sm
        return true
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        sensorManager = null
        _bearing.value = null
    }
}
