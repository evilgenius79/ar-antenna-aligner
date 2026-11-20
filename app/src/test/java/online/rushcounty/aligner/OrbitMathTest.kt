package online.rushcounty.aligner

import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.GeoPoint
import online.rushcounty.aligner.sat.OrbitMath
import online.rushcounty.aligner.sat.OrbitalElements
import online.rushcounty.aligner.sat.SatelliteRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class OrbitMathTest {
    private val rushville = GeoPoint(39.6092, -85.4464, 300.0)

    /** Elements for a perfect geostationary satellite sitting over [lonDeg] at [epoch]. */
    private fun idealGeo(lonDeg: Double, epoch: Long) = OrbitalElements(
        name = "IDEAL", noradId = 1, epochMillis = epoch,
        meanMotionRevPerDay = 1.002_737_909_35, eccentricity = 0.0, inclinationDeg = 0.0,
        raanDeg = 0.0, argPerigeeDeg = 0.0,
        meanAnomalyDeg = GeoMath.normalize360(Math.toDegrees(OrbitMath.gmstRad(epoch)) + lonDeg),
    )

    @Test fun idealGeoMatchesClosedFormLookAngles() {
        val epoch = Instant.parse("2026-11-07T18:00:00Z").toEpochMilli()
        for (satLon in listOf(-100.0, -85.4464, -60.0, -130.0)) {
            val el = idealGeo(satLon, epoch)
            val pos = OrbitMath.positionEcef(el, epoch)
            assertEquals(satLon, pos.longitudeDeg, 1e-6)
            val look = OrbitMath.lookAngles(rushville, pos)

            // Textbook spherical-Earth GEO look angles (good to ~0.2° vs the WGS84 version).
            val phi = Math.toRadians(rushville.lat)
            val dl = Math.toRadians(satLon - rushville.lon)
            val c = cos(phi) * cos(dl)
            val elev = Math.toDegrees(atan((c - 6378.137 / 42164.17) / sqrt(1 - c * c)))
            val az = GeoMath.normalize360(180.0 - Math.toDegrees(atan(tan(dl) / sin(phi))))
            assertEquals("el for $satLon", elev, look.elevationDeg, 0.25)
            assertEquals("az for $satLon", 0.0, GeoMath.normalize180(az - look.bearingDeg), 0.25)
        }
    }

    @Test fun idealGeoStaysPut() {
        val epoch = Instant.parse("2026-11-07T00:00:00Z").toEpochMilli()
        val el = idealGeo(-100.0, epoch)
        for (h in listOf(1, 6, 12, 23)) {
            val p = OrbitMath.positionEcef(el, epoch + h * 3_600_000L)
            // J2 secular terms nudge an idealized element set ~0.01°/day; real sats are station-kept.
            assertEquals(-100.0, p.longitudeDeg, 0.05)
            assertEquals(0.0, p.latitudeDeg, 1e-6)
        }
    }

    /**
     * Reference values from Skyfield (full SGP4/SDP4 + IERS frames) for the bundled
     * 2026-11-07 CelesTrak elements, from Rushville at 2026-11-07T18:00Z.
     */
    @Test fun agreesWithSkyfieldForUhfSatcom() {
        val text = javaClass.getResource("/geo-2026-11-07.json")!!.readText()
        val sats = SatelliteRepository.parse(text).associateBy { it.noradId }
        val t = Instant.parse("2026-11-07T18:00:00Z").toEpochMilli()
        // norad to (az, el)
        val ref = mapOf(
            38093 to (202.282 to 42.893), // MUOS-1
            41622 to (207.920 to 37.838), // MUOS-5
            25967 to (115.738 to 5.211), // UFO 10
            40374 to (106.229 to 3.027), // MUOS-3
            23467 to (284.311 to -11.974), // UFO 4 (below horizon)
            20253 to (324.100 to -37.362), // FLTSATCOM 8 (below horizon)
        )
        for ((id, azEl) in ref) {
            val el = sats.getValue(id)
            val look = OrbitMath.lookAngles(rushville, OrbitMath.positionEcef(el, t))
            assertEquals("az ${el.name}", 0.0, GeoMath.normalize180(look.bearingDeg - azEl.first), 0.2)
            assertEquals("el ${el.name}", azEl.second, look.elevationDeg, 0.2)
        }
    }

    @Test fun inclinedSatTracesDailyFigureEight() {
        val text = javaClass.getResource("/geo-2026-11-07.json")!!.readText()
        val muos1 = SatelliteRepository.parse(text).first { it.noradId == 38093 }
        val t = Instant.parse("2026-11-07T18:00:00Z").toEpochMilli()
        val track = OrbitMath.track(muos1, rushville, t)
        val els = track.map { it.elevationDeg }
        val swing = els.max() - els.min()
        // ~5.8° inclination => roughly ±6-7° elevation swing over a day.
        assertTrue("swing $swing", swing in 8.0..16.0)
        val (cAz, cEl) = OrbitMath.trackCenter(track)!!
        assertTrue(abs(GeoMath.normalize180(cAz - 202.0)) < 5)
        assertTrue(cEl in 38.0..48.0)
    }

    @Test fun parsesCelestrakEpoch() {
        assertEquals(
            Instant.parse("2026-11-06T22:48:36.205Z").toEpochMilli(),
            SatelliteRepository.parseEpoch("2026-11-06T22:48:36.205920"),
        )
    }
}
