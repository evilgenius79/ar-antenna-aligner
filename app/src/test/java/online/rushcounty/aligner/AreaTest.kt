package online.rushcounty.aligner

import online.rushcounty.aligner.data.SiteKind
import online.rushcounty.aligner.data.SiteTiles
import online.rushcounty.aligner.data.TargetType
import online.rushcounty.aligner.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AreaTest {
    private val tile = """
        {"v":1,"built":"2026-11","fields":["id","name","lat","lon","altMeters","type","tags","note"],"sites":[
        ["fcc:1028174","State tower",39.620083,-85.406889,396.8,"REPEATER","PT","851.4875 MHz."],
        ["fcc:WSS389-1","Fire Dept",39.649222,-85.599417,296.0,"TOWER","P","154.355 MHz."],
        ["fcc:WQII574-1","Co-op",39.602833,-85.451361,null,"REPEATER","B",""],
        ["fcc:WRBY616-3","Internet link",39.5875,-85.571389,316.7,"TOWER","M","Microwave links."]]}
    """.trimIndent()

    @Test fun tilesCoverTheCircle() {
        // Rushville, 25 mi: stays inside one degree of latitude, crosses the -86 meridian.
        assertEquals(setOf("39_-86", "39_-85"), SiteTiles.tilesFor(39.6, -85.43, 40.2).toSet())
        // Small circle well inside a cell.
        assertEquals(listOf("39_-86"), SiteTiles.tilesFor(39.5, -85.5, 10.0))
        // 100 mi reaches two degrees each way in longitude at this latitude.
        val big = SiteTiles.tilesFor(39.6, -85.43, 160.9)
        assertTrue("38_-88" in big && "41_-84" in big)
        assertEquals(4 * 5, big.size)
        // Southern-hemisphere style negative flooring is handled (name is the south-west corner).
        assertEquals(listOf("18_-67"), SiteTiles.tilesFor(18.2, -66.5, 5.0))
    }

    @Test fun parsesTile() {
        val sites = SiteTiles.parse(tile)
        assertEquals(4, sites.size)
        assertEquals("fcc:1028174", sites[0].target.id)
        assertEquals(TargetType.REPEATER, sites[0].target.type)
        assertEquals(396.8, sites[0].target.altMeters!!, 1e-9)
        assertEquals("PT", sites[0].tags)
        assertNull(sites[2].target.altMeters)
        assertNull(sites[2].target.note)
    }

    @Test fun selectsByKindAndDistance() {
        val sites = SiteTiles.parse(tile)
        val rushville = GeoPoint(39.6092, -85.4464)
        val all = SiteKind.entries.toSet()
        // Nearest first.
        assertEquals(
            listOf("fcc:WQII574-1", "fcc:1028174", "fcc:WRBY616-3", "fcc:WSS389-1"),
            SiteTiles.select(sites, rushville, 40.0, all).map { it.id },
        )
        // 5 km only reaches the two in town.
        assertEquals(2, SiteTiles.select(sites, rushville, 5.0, all).size)
        // A site with several tags matches any of them.
        assertEquals(listOf("fcc:1028174"), SiteTiles.select(sites, rushville, 40.0, setOf(SiteKind.TOWER)).map { it.id })
        assertEquals(2, SiteTiles.select(sites, rushville, 40.0, setOf(SiteKind.PUBLIC_SAFETY)).size)
        assertEquals(listOf("fcc:WRBY616-3"), SiteTiles.select(sites, rushville, 40.0, setOf(SiteKind.MICROWAVE)).map { it.id })
    }

    @Test fun zipTableIsWellFormed() {
        val lines = File("src/main/assets/zips.csv").readLines()
        assertTrue(lines.size > 30_000)
        val rushville = lines.first { it.startsWith("46173,") }.split(',')
        assertEquals(39.60, rushville[1].toDouble(), 0.05)
        assertEquals(-85.43, rushville[2].toDouble(), 0.05)
        assertTrue(lines.all { l -> l.split(',').let { it.size == 3 && it[0].length == 5 } })
    }
}
