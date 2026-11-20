package online.rushcounty.aligner.geo

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius (m). */
const val EARTH_RADIUS_M = 6_371_008.8

/**
 * Effective Earth radius for line-of-sight elevation. 4/3 Earth is the standard model for
 * VHF/UHF radio paths (k-factor), which is what the antenna actually cares about.
 */
const val EFFECTIVE_EARTH_RADIUS_M = EARTH_RADIUS_M * 4.0 / 3.0

data class GeoPoint(val lat: Double, val lon: Double, val altMeters: Double = 0.0)

/** Look angles from an observer to a target. Bearing is degrees TRUE (0..360). */
data class LookAngles(
    val bearingDeg: Double,
    val elevationDeg: Double,
    val distanceMeters: Double,
)

object GeoMath {

    /** Great-circle (haversine) ground distance in meters. */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val p1 = Math.toRadians(from.lat)
        val p2 = Math.toRadians(to.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(to.lon - from.lon)
        val a = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial great-circle bearing, degrees true, normalized to [0, 360). */
    fun initialBearingDeg(from: GeoPoint, to: GeoPoint): Double {
        val p1 = Math.toRadians(from.lat)
        val p2 = Math.toRadians(to.lat)
        val dl = Math.toRadians(to.lon - from.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize360(Math.toDegrees(atan2(y, x)))
    }

    /**
     * Elevation angle (degrees, + = above horizontal) from observer to target, including
     * Earth curvature with 4/3-Earth refraction. Matters at long range: a target 70 km
     * away sits ~0.3° lower than flat-earth math would say.
     */
    fun elevationDeg(from: GeoPoint, to: GeoPoint, groundDistance: Double = distanceMeters(from, to)): Double {
        if (groundDistance < 1.0) return if (to.altMeters >= from.altMeters) 90.0 else -90.0
        val drop = groundDistance * groundDistance / (2 * EFFECTIVE_EARTH_RADIUS_M)
        val dh = to.altMeters - from.altMeters - drop
        return Math.toDegrees(atan(dh / groundDistance))
    }

    fun lookAngles(from: GeoPoint, to: GeoPoint): LookAngles {
        val d = distanceMeters(from, to)
        return LookAngles(initialBearingDeg(from, to), elevationDeg(from, to, d), d)
    }

    fun normalize360(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Normalize to (-180, 180]. */
    fun normalize180(deg: Double): Double {
        val r = normalize360(deg)
        return if (r > 180.0) r - 360.0 else r
    }

    private val COMPASS_POINTS = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun compassPoint(bearingDeg: Double): String =
        COMPASS_POINTS[((normalize360(bearingDeg) + 11.25) / 22.5).toInt() % 16]
}
