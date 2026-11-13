package com.ohm.launcher.loc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Compass heading (degrees, 0 = north) from the rotation-vector sensor, corrected for the screen
 * orientation. Many head units have no such sensor — then [heading] stays null and the GPS
 * course is used instead. Only listens while someone needs it (the map when standing still).
 */
class Compass(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()
    val available get() = sensor != null
    private var listening = false
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    fun setActive(on: Boolean) {
        if (sensor == null || on == listening) return
        listening = on
        if (on) sm?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) else { sm?.unregisterListener(this); _heading.value = null }
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        @Suppress("DEPRECATION")
        val rotation = runCatching { ctx.getSystemService(WindowManager::class.java).defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, x, y, remapped)
        SensorManager.getOrientation(remapped, orient)
        val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
        // Low-pass filter along the shortest arc so it doesn't jitter.
        val prev = _heading.value
        _heading.value = if (prev == null) deg else {
            var d = (deg - prev) % 360; if (d > 180) d -= 360; if (d < -180) d += 360
            ((prev + d * 0.15f) + 360) % 360
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
