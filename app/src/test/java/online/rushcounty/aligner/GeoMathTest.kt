package online.rushcounty.aligner

import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    private val rushville = GeoPoint(39.6092, -85.4464, 300.0)
    private val kind = GeoPoint(39.7173, -86.2944, 241.0)

    @Test fun rushvilleToKindIsWnwAbout45Miles() {
        val l = GeoMath.lookAngles(rushville, kind)
        assertTrue("bearing ${l.bearingDeg}", l.bearingDeg in 278.0..283.0)
        // ~280° is the W sector on a 16-point rose (258.75..281.25), just shy of WNW.
        assertEquals("W", GeoMath.compassPoint(l.bearingDeg))
        val miles = l.distanceMeters / 1609.344
        assertTrue("miles $miles", miles in 44.0..47.0)
        // 59 m lower and ~73 km away: curvature puts it well below the horizontal.
        assertTrue("elev ${l.elevationDeg}", l.elevationDeg in -0.4..-0.2)
    }

    @Test fun cardinalBearings() {
        val o = GeoPoint(40.0, -86.0)
        assertEquals(0.0, GeoMath.initialBearingDeg(o, GeoPoint(40.1, -86.0)), 1e-6)
        assertEquals(180.0, GeoMath.initialBearingDeg(o, GeoPoint(39.9, -86.0)), 1e-6)
        assertEquals(90.0, GeoMath.initialBearingDeg(o, GeoPoint(40.0, -85.99)), 0.01)
        assertEquals(270.0, GeoMath.initialBearingDeg(o, GeoPoint(40.0, -86.01)), 0.01)
    }

    @Test fun distanceOneDegreeLatitude() {
        assertEquals(111_195.0, GeoMath.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 50.0)
    }

    @Test fun nearbyTallTowerHasPositiveElevation() {
        val o = GeoPoint(39.6, -85.44, 290.0)
        val t = GeoPoint(39.609, -85.44, 340.0) // ~1 km north, 50 m higher
        assertEquals(Math.toDegrees(Math.atan(50.0 / 1000.8)), GeoMath.elevationDeg(o, t), 0.05)
    }

    @Test fun normalize() {
        assertEquals(350.0, GeoMath.normalize360(-10.0), 1e-9)
        assertEquals(-10.0, GeoMath.normalize180(350.0), 1e-9)
        assertEquals(180.0, GeoMath.normalize180(180.0), 1e-9)
        assertEquals("N", GeoMath.compassPoint(359.0))
        assertEquals("E", GeoMath.compassPoint(91.0))
    }
}
