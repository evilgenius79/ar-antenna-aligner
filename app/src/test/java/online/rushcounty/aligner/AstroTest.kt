package online.rushcounty.aligner

import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.GeoPoint
import online.rushcounty.aligner.sat.Astro
import online.rushcounty.aligner.sat.OrbitMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Reference values: Skyfield + JPL DE421, apparent topocentric positions without refraction,
 * from Rushville (39.6092, -85.4464, 300 m). Positions below the horizon still test the geometry.
 */
class AstroTest {
    private val rushville = GeoPoint(39.6092, -85.4464, 300.0)

    private data class Ref(val iso: String, val body: String, val az: Double, val el: Double)

    private val refs = listOf(
        Ref("2026-11-07T18:00:00Z", "sun", 189.926, 33.378),
        Ref("2026-11-07T18:00:00Z", "moon", 211.103, 29.130),
        Ref("2026-11-08T03:00:00Z", "sun", 295.626, -50.912),
        Ref("2026-11-08T03:00:00Z", "moon", 314.703, -60.712),
        Ref("2026-11-20T09:30:00Z", "sun", 87.625, -34.794),
        Ref("2026-11-20T09:30:00Z", "moon", 289.911, -18.086),
        Ref("2026-06-21T17:30:00Z", "sun", 168.864, 73.572),
        Ref("2026-06-21T17:30:00Z", "moon", 90.747, -2.142),
    )

    @Test fun sunAndMoonMatchSkyfield() {
        for (r in refs) {
            val t = Instant.parse(r.iso).toEpochMilli()
            val ecef = if (r.body == "sun") Astro.sunEcef(t) else Astro.moonEcef(t)
            val look = OrbitMath.lookAngles(rushville, ecef)
            // Sun is used for compass calibration, so hold it tight; the Moon is a marker only.
            val tol = if (r.body == "sun") 0.05 else 0.5
            // Azimuth error shrinks toward the zenith, so compare it on the sky (scaled by cos el).
            val azErrOnSky = GeoMath.normalize180(look.bearingDeg - r.az) * Math.cos(Math.toRadians(r.el))
            assertEquals("az ${r.body} ${r.iso}", 0.0, azErrOnSky, tol)
            assertEquals("el ${r.body} ${r.iso}", r.el, look.elevationDeg, tol)
        }
    }

    @Test fun moonIllumination() {
        assertEquals(0.025, Astro.moonIllumination(Instant.parse("2026-11-07T18:00:00Z").toEpochMilli()), 0.03)
        assertEquals(0.780, Astro.moonIllumination(Instant.parse("2026-11-20T09:30:00Z").toEpochMilli()), 0.03)
    }

    @Test fun refraction() {
        assertEquals(0.48, Astro.refractionDeg(0.0), 0.03) // ~29' for the true altitude at the horizon
        assertTrue(Astro.refractionDeg(45.0) < 0.02)
        assertEquals(0.0, Astro.refractionDeg(-5.0), 1e-9)
    }
}
