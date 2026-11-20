package online.rushcounty.aligner.ar

import online.rushcounty.aligner.geo.GeoMath
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A 3-vector. ENU = (east, north, up). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun norm() = sqrt(dot(this))
}

/** Where a world direction lands on screen. */
data class ScreenPoint(
    val x: Float,
    val y: Float,
    /** True if the direction is in front of the camera (may still be off the edges of the view). */
    val inFront: Boolean,
)

/**
 * Camera pose + intrinsics snapshot, used to project world directions onto the screen.
 *
 * @param r 3x3 row-major rotation matrix (device -> world) from TYPE_ROTATION_VECTOR, already
 *   remapped for display rotation. World frame is Android's: X east, Y MAGNETIC north, Z up.
 *   Device frame: X right, Y up (screen), Z out of the screen; the back camera looks along -Z.
 * @param headingCorrectionDeg added to magnetic headings to get TRUE headings
 *   (declination, east-positive, plus the user's sight-in offset).
 * @param focalPx camera focal length in screen pixels (square pixels assumed).
 */
class Projector(
    private val r: FloatArray,
    val headingCorrectionDeg: Double,
    val viewWidth: Float,
    val viewHeight: Float,
    val focalPx: Float,
) {
    init {
        require(r.size >= 9)
    }

    /** Unit vector in the sensor's world frame (magnetic ENU) for a TRUE bearing + elevation. */
    fun worldDir(trueBearingDeg: Double, elevationDeg: Double): Vec3 {
        val az = Math.toRadians(trueBearingDeg - headingCorrectionDeg)
        val el = Math.toRadians(elevationDeg)
        return Vec3(cos(el) * sin(az), cos(el) * cos(az), sin(el))
    }

    /** World -> device frame: d = R^T w. */
    fun toDevice(w: Vec3): Vec3 = Vec3(
        r[0] * w.x + r[3] * w.y + r[6] * w.z,
        r[1] * w.x + r[4] * w.y + r[7] * w.z,
        r[2] * w.x + r[5] * w.y + r[8] * w.z,
    )

    /** Pinhole projection of a TRUE bearing/elevation onto the view. */
    fun project(trueBearingDeg: Double, elevationDeg: Double): ScreenPoint {
        val d = toDevice(worldDir(trueBearingDeg, elevationDeg))
        val depth = -d.z
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        if (depth <= 1e-6) {
            // Behind the camera: return the direction it lies in, pushed far off-screen,
            // so callers can still draw an edge arrow pointing the right way.
            val len = sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(1e-9)
            val far = 1e6f
            return ScreenPoint(cx + (d.x / len).toFloat() * far, cy - (d.y / len).toFloat() * far, false)
        }
        return ScreenPoint(
            cx + focalPx * (d.x / depth).toFloat(),
            cy - focalPx * (d.y / depth).toFloat(),
            true,
        )
    }

    fun isOnScreen(p: ScreenPoint, marginPx: Float = 0f): Boolean =
        p.inFront && p.x >= -marginPx && p.x <= viewWidth + marginPx &&
            p.y >= -marginPx && p.y <= viewHeight + marginPx

    /** Camera boresight (the -Z device axis) in world coordinates. */
    fun boresightWorld(): Vec3 = Vec3(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())

    /** True heading of the camera boresight, 0..360. */
    fun cameraHeadingTrueDeg(): Double {
        val b = boresightWorld()
        return GeoMath.normalize360(Math.toDegrees(atan2(b.x, b.y)) + headingCorrectionDeg)
    }

    /** Camera pitch: + looking up, - looking down. */
    fun cameraPitchDeg(): Double = Math.toDegrees(asin(boresightWorld().z.coerceIn(-1.0, 1.0)))

    /** Roll of the screen relative to the horizon: + = rotated clockwise. */
    fun rollDeg(): Double = Math.toDegrees(atan2(-r[6].toDouble(), r[7].toDouble()))

    /** Angle between the camera boresight and a target direction (degrees). */
    fun offBoresightDeg(trueBearingDeg: Double, elevationDeg: Double): Double {
        // atan2(|a×b|, a·b) stays accurate for tiny angles, where acos(a·b) does not.
        val a = boresightWorld()
        val b = worldDir(trueBearingDeg, elevationDeg)
        val cross = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
        return Math.toDegrees(atan2(cross.norm(), a.dot(b)))
    }

    /** Screen radius (px) of a cone of the given half-angle around the boresight. */
    fun coneRadiusPx(halfAngleDeg: Double): Float =
        (focalPx * kotlin.math.tan(Math.toRadians(halfAngleDeg.coerceIn(0.0, 89.0)))).toFloat()

    val horizontalFovDeg: Double get() = Math.toDegrees(2 * kotlin.math.atan(viewWidth / 2.0 / focalPx))
    val verticalFovDeg: Double get() = Math.toDegrees(2 * kotlin.math.atan(viewHeight / 2.0 / focalPx))

    companion object {
        /**
         * Device->world rotation (row-major, same convention as the sensor's) for a level camera
         * looking along a heading/pitch. [headingDeg] is in the sensor's world frame, i.e. the
         * TRUE heading minus the projector's heading correction.
         */
        fun rotationFor(headingDeg: Double, pitchDeg: Double): FloatArray {
            val h = Math.toRadians(headingDeg)
            val p = Math.toRadians(pitchDeg)
            // Device axes in world ENU: X = right, -Z = boresight, Y = right x boresight (up).
            val rx = cos(h); val ry = -sin(h); val rz = 0.0
            val bx = cos(p) * sin(h); val by = cos(p) * cos(h); val bz = sin(p)
            val ux = ry * bz - rz * by; val uy = rz * bx - rx * bz; val uz = rx * by - ry * bx
            return floatArrayOf(
                rx.toFloat(), ux.toFloat(), (-bx).toFloat(),
                ry.toFloat(), uy.toFloat(), (-by).toFloat(),
                rz.toFloat(), uz.toFloat(), (-bz).toFloat(),
            )
        }

        /**
         * Focal length in view pixels for a camera preview scaled with FILL_CENTER (center-crop).
         *
         * @param focalMm lens focal length
         * @param sensorWmm / sensorHmm active sensor area in the sensor's native (landscape) orientation
         * @param viewW / viewH the on-screen preview size in px (portrait or landscape)
         */
        fun focalPxForFillCenter(
            focalMm: Double,
            sensorWmm: Double,
            sensorHmm: Double,
            viewW: Float,
            viewH: Float,
        ): Float {
            val sensorLong = maxOf(sensorWmm, sensorHmm)
            val sensorShort = minOf(sensorWmm, sensorHmm)
            val viewLong = maxOf(viewW, viewH).toDouble()
            val viewShort = minOf(viewW, viewH).toDouble()
            // Image is displayed with its long side along the view's long side, scaled to fill.
            val scale = maxOf(viewLong / sensorLong, viewShort / sensorShort) // px per mm of sensor
            return (focalMm * scale).toFloat()
        }

        /** Focal length (px) that gives a horizontal FOV, for when camera metadata is unavailable. */
        fun focalPxForHorizontalFov(hfovDeg: Double, viewW: Float): Float =
            (viewW / 2.0 / kotlin.math.tan(Math.toRadians(hfovDeg / 2))).toFloat()
    }
}
