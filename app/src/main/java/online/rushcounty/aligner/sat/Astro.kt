package online.rushcounty.aligner.sat

import online.rushcounty.aligner.geo.GeoPoint
import online.rushcounty.aligner.geo.LookAngles
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Low-precision Sun and Moon positions (Astronomical Almanac formulas).
 * Sun ≈ 0.01°, Moon ≈ 0.3° — the Sun is good enough to calibrate the compass against.
 * Positions go through Earth-fixed coordinates, so the Moon's ~1° parallax is handled by
 * the same look-angle math used for satellites.
 */
object Astro {
    private const val AU_KM = 149_597_870.7
    private const val EARTH_RADIUS_KM = 6378.137

    private fun jdOffset(timeMillis: Long) = timeMillis / 86_400_000.0 + 2_440_587.5 - 2_451_545.0
    private fun sinD(d: Double) = sin(Math.toRadians(d))
    private fun cosD(d: Double) = cos(Math.toRadians(d))

    fun sunEcef(timeMillis: Long): Ecef {
        val n = jdOffset(timeMillis)
        val l = 280.460 + 0.985_647_4 * n
        val g = 357.528 + 0.985_600_3 * n
        val lambda = l + 1.915 * sinD(g) + 0.020 * sinD(2 * g)
        val rAu = 1.000_14 - 0.016_71 * cosD(g) - 0.000_14 * cosD(2 * g)
        return eclipticToEcef(lambda, 0.0, rAu * AU_KM, n, timeMillis)
    }

    fun moonEcef(timeMillis: Long): Ecef {
        val n = jdOffset(timeMillis)
        val t = n / 36_525.0
        val lambda = 218.32 + 481_267.881 * t +
            6.29 * sinD(135.0 + 477_198.87 * t) - 1.27 * sinD(259.3 - 413_335.36 * t) +
            0.66 * sinD(235.7 + 890_534.22 * t) + 0.21 * sinD(269.9 + 954_397.74 * t) -
            0.19 * sinD(357.5 + 35_999.05 * t) - 0.11 * sinD(186.5 + 966_404.03 * t)
        val beta = 5.13 * sinD(93.3 + 483_202.02 * t) + 0.28 * sinD(228.2 + 960_400.89 * t) -
            0.28 * sinD(318.3 + 6_003.15 * t) - 0.17 * sinD(217.6 - 407_332.21 * t)
        val parallax = 0.9508 + 0.0518 * cosD(135.0 + 477_198.87 * t) + 0.0095 * cosD(259.3 - 413_335.36 * t) +
            0.0078 * cosD(235.7 + 890_534.22 * t) + 0.0028 * cosD(269.9 + 954_397.74 * t)
        return eclipticToEcef(lambda, beta, EARTH_RADIUS_KM / sinD(parallax), n, timeMillis)
    }

    /** Ecliptic (of date) -> equatorial -> Earth-fixed via sidereal time. */
    private fun eclipticToEcef(lambdaDeg: Double, betaDeg: Double, distKm: Double, n: Double, timeMillis: Long): Ecef {
        val eps = 23.439 - 0.000_000_4 * n
        val x = cosD(betaDeg) * cosD(lambdaDeg)
        val y = cosD(eps) * cosD(betaDeg) * sinD(lambdaDeg) - sinD(eps) * sinD(betaDeg)
        val z = sinD(eps) * cosD(betaDeg) * sinD(lambdaDeg) + cosD(eps) * sinD(betaDeg)
        val th = OrbitMath.gmstRad(timeMillis)
        val xi = x * distKm
        val yi = y * distKm
        return Ecef(xi * cos(th) + yi * sin(th), -xi * sin(th) + yi * cos(th), z * distKm)
    }

    /** Geometric look angles plus atmospheric refraction (what you actually see). */
    fun apparent(observer: GeoPoint, body: Ecef): LookAngles {
        val geo = OrbitMath.lookAngles(observer, body)
        return geo.copy(elevationDeg = geo.elevationDeg + refractionDeg(geo.elevationDeg))
    }

    /** Bennett's refraction formula (degrees to add to a geometric altitude); ~0.57° at the horizon. */
    fun refractionDeg(geometricElevationDeg: Double): Double {
        val h = geometricElevationDeg
        if (h < -1.0) return 0.0
        // Saemundsson inverse of Bennett: input is the true (geometric) altitude.
        return 1.02 / tan(Math.toRadians(h + 10.3 / (h + 5.11))) / 60.0
    }

    /** Illuminated fraction of the Moon (0 new … 1 full), from the Sun–Moon elongation. */
    fun moonIllumination(timeMillis: Long): Double {
        val s = sunEcef(timeMillis)
        val m = moonEcef(timeMillis)
        val dot = (s.x * m.x + s.y * m.y + s.z * m.z) /
            (kotlin.math.sqrt(s.x * s.x + s.y * s.y + s.z * s.z) * kotlin.math.sqrt(m.x * m.x + m.y * m.y + m.z * m.z))
        val elongation = kotlin.math.acos(dot.coerceIn(-1.0, 1.0))
        return (1 - cos(elongation)) / 2
    }
}
