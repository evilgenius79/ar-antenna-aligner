package online.rushcounty.aligner.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

data class OrientationState(
    /** Row-major 3x3 device->world (magnetic ENU), remapped for display rotation. Null until first sample. */
    val rotation: FloatArray? = null,
    /** SensorManager.SENSOR_STATUS_* reported for the magnetometer (calibration state). */
    val magAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    /** Rotation-vector estimated heading accuracy in degrees, if the device reports it. */
    val headingAccuracyDeg: Float? = null,
    val available: Boolean = true,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * TYPE_ROTATION_VECTOR (fused gyro + accel + mag) with adaptive quaternion smoothing:
 * heavy smoothing when the phone is held still (kills jitter), light when it is moving (no lag).
 */
class OrientationProvider(private val context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _state = MutableStateFlow(OrientationState(available = rotSensor != null))
    val state: StateFlow<OrientationState> = _state.asStateFlow()

    /** Smoothing strength 0 (none) .. 1 (max). */
    @Volatile var smoothing: Float = 0.6f

    private val qNew = FloatArray(4)
    private var q: FloatArray? = null // w, x, y, z
    private val rRaw = FloatArray(9)
    private var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    fun start() {
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // Registered only so we get onAccuracyChanged for the compass-calibration indicator.
        magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm.unregisterListener(this)
        q = null
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magAccuracy = accuracy
            _state.value = _state.value.copy(magAccuracy = accuracy)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (event.accuracy != magAccuracy) {
                    magAccuracy = event.accuracy
                    _state.value = _state.value.copy(magAccuracy = magAccuracy)
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> onRotation(event)
        }
    }

    private fun onRotation(event: SensorEvent) {
        SensorManager.getQuaternionFromVector(qNew, event.values) // w, x, y, z
        val cur = q
        if (cur == null) {
            q = qNew.copyOf()
        } else {
            var dot = cur[0] * qNew[0] + cur[1] * qNew[1] + cur[2] * qNew[2] + cur[3] * qNew[3]
            if (dot < 0) { // same rotation, opposite hemisphere: flip to take the short path
                for (i in 0..3) qNew[i] = -qNew[i]
                dot = -dot
            }
            val angleDeg = Math.toDegrees(2.0 * acos(dot.coerceAtMost(1f).toDouble())).toFloat()
            // Base alpha from the smoothing setting; ramps to 1 as angular change grows.
            val base = 1f - 0.95f * smoothing.coerceIn(0f, 1f)
            val alpha = (base + angleDeg / 4f).coerceIn(base, 1f)
            for (i in 0..3) cur[i] += alpha * (qNew[i] - cur[i])
            val n = sqrt(cur[0] * cur[0] + cur[1] * cur[1] + cur[2] * cur[2] + cur[3] * cur[3])
            for (i in 0..3) cur[i] /= n
        }
        val s = q!!
        // getRotationMatrixFromVector wants x, y, z, w.
        SensorManager.getRotationMatrixFromVector(rRaw, floatArrayOf(s[1], s[2], s[3], s[0]))
        val r = remapForDisplay(rRaw)

        val headingAcc = if (event.values.size > 4 && event.values[4] >= 0f && abs(event.values[4]) < 7f)
            Math.toDegrees(event.values[4].toDouble()).toFloat() else null

        _state.value = OrientationState(
            rotation = r,
            magAccuracy = magAccuracy,
            headingAccuracyDeg = headingAcc,
            available = true,
        )
    }

    // context is the Application, which has no display of its own (context.display throws on API 30+),
    // so ask DisplayManager for the built-in screen instead.
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private fun displayRotation(): Int =
        runCatching { displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation }.getOrNull() ?: Surface.ROTATION_0

    private fun remapForDisplay(r: FloatArray): FloatArray {
        val out = FloatArray(9)
        when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
            else -> r.copyInto(out)
        }
        return out
    }
}
