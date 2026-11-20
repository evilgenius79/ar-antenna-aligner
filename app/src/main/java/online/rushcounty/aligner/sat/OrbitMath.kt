package online.rushcounty.aligner.sat

import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.GeoPoint
import online.rushcounty.aligner.geo.LookAngles
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean orbital elements as published by CelesTrak (OMM/TLE fields). Angles in degrees. */
data class OrbitalElements(
    val name: String,
    val noradId: Int,
    val epochMillis: Long,
    val meanMotionRevPerDay: Double,
    val eccentricity: Double,
    val inclinationDeg: Double,
    val raanDeg: Double,
    val argPerigeeDeg: Double,
    val meanAnomalyDeg: Double,
)

data class Ecef(val x: Double, val y: Double, val z: Double) {
    /** Longitude of the point directly below, degrees east (-180..180]. */
    val longitudeDeg: Double get() = Math.toDegrees(atan2(y, x))
    val latitudeDeg: Double get() = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
}

/**
 * Two-body Kepler propagation with J2 secular drift of the node and perigee.
 *
 * This is much simpler than SGP4/SDP4 but plenty for pointing an antenna at a geostationary
 * satellite from fresh (days-old) elements: the error is a small fraction of a degree, far
 * inside any UHF antenna's beam. Low-orbit satellites would need full SGP4.
 */
object OrbitMath {
    private const val MU = 398_600.4418 // km^3/s^2
    private const val RE = 6378.137 // km, WGS84 equatorial
    private const val J2 = 1.082_626_68e-3
    private const val WGS84_F = 1 / 298.257_223_563
    private const val WGS84_E2 = WGS84_F * (2 - WGS84_F)
    const val GEO_RADIUS_KM = 42_164.17

    /** Satellite position in Earth-fixed coordinates (km) at [timeMillis] UTC. */
    fun positionEcef(el: OrbitalElements, timeMillis: Long): Ecef {
        val dt = (timeMillis - el.epochMillis) / 1000.0 // s
        val n = el.meanMotionRevPerDay * 2 * PI / 86_400.0 // rad/s
        val a = cbrt(MU / (n * n))
        val e = el.eccentricity
        val i = Math.toRadians(el.inclinationDeg)
        val p = a * (1 - e * e)
        val k = 1.5 * J2 * (RE / p) * (RE / p) * n
        val raan = Math.toRadians(el.raanDeg) - k * cos(i) * dt
        val argp = Math.toRadians(el.argPerigeeDeg) + 0.5 * k * (5 * cos(i) * cos(i) - 1) * dt
        val m = Math.toRadians(el.meanAnomalyDeg) + n * dt

        // Kepler's equation by Newton iteration.
        var ea = if (e < 0.8) m else PI
        repeat(20) {
            val d = (ea - e * sin(ea) - m) / (1 - e * cos(ea))
            ea -= d
            if (abs(d) < 1e-12) return@repeat
        }
        val nu = atan2(sqrt(1 - e * e) * sin(ea), cos(ea) - e)
        val r = a * (1 - e * cos(ea))

        // Perifocal -> inertial (true-equator frame).
        val u = argp + nu
        val xi = r * (cos(raan) * cos(u) - sin(raan) * sin(u) * cos(i))
        val yi = r * (sin(raan) * cos(u) + cos(raan) * sin(u) * cos(i))
        val zi = r * (sin(u) * sin(i))

        // Inertial -> Earth-fixed by Greenwich sidereal angle.
        val th = gmstRad(timeMillis)
        return Ecef(xi * cos(th) + yi * sin(th), -xi * sin(th) + yi * cos(th), zi)
    }

    /** Greenwich mean sidereal time (IAU 1982), radians. UT1 is taken as UTC (<1 s error). */
    fun gmstRad(timeMillis: Long): Double {
        val jd = timeMillis / 86_400_000.0 + 2_440_587.5
        val t = (jd - 2_451_545.0) / 36_525.0
        val deg = 280.460_618_37 + 360.985_647_366_29 * (jd - 2_451_545.0) +
            0.000_387_933 * t * t - t * t * t / 38_710_000.0
        return Math.toRadians(GeoMath.normalize360(deg))
    }

    /** WGS84 geodetic -> Earth-fixed (km). */
    fun observerEcef(o: GeoPoint): Ecef {
        val lat = Math.toRadians(o.lat)
        val lon = Math.toRadians(o.lon)
        val h = o.altMeters / 1000.0
        val nr = RE / sqrt(1 - WGS84_E2 * sin(lat) * sin(lat))
        return Ecef(
            (nr + h) * cos(lat) * cos(lon),
            (nr + h) * cos(lat) * sin(lon),
            (nr * (1 - WGS84_E2) + h) * sin(lat),
        )
    }

    /** Azimuth (true), elevation and slant range (m) from [observer] to an Earth-fixed point. */
    fun lookAngles(observer: GeoPoint, target: Ecef): LookAngles {
        val o = observerEcef(observer)
        val dx = target.x - o.x
        val dy = target.y - o.y
        val dz = target.z - o.z
        val lat = Math.toRadians(observer.lat)
        val lon = Math.toRadians(observer.lon)
        val east = -sin(lon) * dx + cos(lon) * dy
        val north = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val up = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz
        val horiz = sqrt(east * east + north * north)
        return LookAngles(
            bearingDeg = GeoMath.normalize360(Math.toDegrees(atan2(east, north))),
            elevationDeg = Math.toDegrees(atan2(up, horiz)),
            distanceMeters = sqrt(horiz * horiz + up * up) * 1000.0,
        )
    }

    /** Ideal geostationary slot at a longitude (for drawing the Clarke belt). */
    fun geoSlot(longitudeDeg: Double): Ecef {
        val l = Math.toRadians(longitudeDeg)
        return Ecef(GEO_RADIUS_KM * cos(l), GEO_RADIUS_KM * sin(l), 0.0)
    }

    /** Look angles over the next [hours] at [stepMinutes] spacing (a GEO sat's daily figure-8). */
    fun track(el: OrbitalElements, observer: GeoPoint, startMillis: Long, hours: Int = 24, stepMinutes: Int = 10): List<LookAngles> =
        (0..hours * 60 / stepMinutes).map { k ->
            lookAngles(observer, positionEcef(el, startMillis + k * stepMinutes * 60_000L))
        }

    /** Center of a track: midpoint of its azimuth and elevation extremes. */
    fun trackCenter(track: List<LookAngles>): Pair<Double, Double>? {
        if (track.isEmpty()) return null
        val ref = track.first().bearingDeg
        val dAz = track.map { GeoMath.normalize180(it.bearingDeg - ref) }
        val els = track.map { it.elevationDeg }
        val az = GeoMath.normalize360(ref + (dAz.min() + dAz.max()) / 2)
        return az to (els.min() + els.max()) / 2
    }
}
