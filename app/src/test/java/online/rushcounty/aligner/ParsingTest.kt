package online.rushcounty.aligner

import online.rushcounty.aligner.data.CsvImport
import online.rushcounty.aligner.data.TargetType
import online.rushcounty.aligner.geo.CoordParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {
    private fun pair(s: String) = assertNotNull("parse '$s'", CoordParser.parsePair(s)).let { CoordParser.parsePair(s)!! }

    @Test fun decimalPairs() {
        assertEquals(39.7173 to -86.2944, pair("39.7173, -86.2944"))
        assertEquals(39.7173 to -86.2944, pair("39.7173 -86.2944"))
        assertEquals(39.7173 to -86.2944, pair("  39.7173,-86.2944 "))
    }

    @Test fun fccDms() {
        val (lat, lon) = pair("39-36-26.2 N 85-26-37.9 W")
        assertEquals(39.607278, lat, 1e-5)
        assertEquals(-85.443861, lon, 1e-5)
        val (lat2, lon2) = pair("39-36-26.2N, 85-26-37.9W")
        assertEquals(39.607278, lat2, 1e-5)
        assertEquals(-85.443861, lon2, 1e-5)
    }

    @Test fun degreeSymbols() {
        val (lat, lon) = pair("39°43'02.3\"N 86°17'39.8\"W")
        assertEquals(39.717306, lat, 1e-5)
        assertEquals(-86.294389, lon, 1e-5)
    }

    @Test fun lonFirstWithHemispheresIsSwapped() {
        val (lat, lon) = pair("85.4438W, 39.6072N")
        assertEquals(39.6072, lat, 1e-9)
        assertEquals(-85.4438, lon, 1e-9)
    }

    @Test fun rejectsGarbage() {
        assertNull(CoordParser.parsePair("hello"))
        assertNull(CoordParser.parsePair("95.0, 10.0"))
        assertNull(CoordParser.parsePair("39-75-00 N 85-00-00 W"))
    }

    @Test fun csvImport() {
        val text = """
            name,lat,lon,alt,type,note
            WZX813, 39.607278, -85.443861, 326, TOWER, 155.130
            "Raleigh, Mays", 39-44-33.2 N, 85-25-58.9 W, , REPEATER, "a, b"
            KIND, "39.7173 -86.2944", 241, airport
            not a target line
        """.trimIndent()
        val (list, skipped) = CsvImport.parse(text)
        assertEquals(3, list.size)
        assertEquals(1, skipped)
        assertEquals("WZX813", list[0].name)
        assertEquals(326.0, list[0].altMeters!!, 1e-9)
        assertEquals(TargetType.TOWER, list[0].type)
        assertEquals("Raleigh, Mays", list[1].name)
        assertEquals(39.742556, list[1].lat, 1e-5)
        assertEquals(-85.433028, list[1].lon, 1e-5)
        assertNull(list[1].altMeters)
        assertEquals("a, b", list[1].note)
        assertEquals(TargetType.AIRPORT, list[2].type)
        assertEquals(-86.2944, list[2].lon, 1e-9)
    }
}
