package online.rushcounty.aligner

import online.rushcounty.aligner.data.Target
import online.rushcounty.aligner.data.TargetRepository
import online.rushcounty.aligner.data.TargetType
import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SeedTest {
    private fun asset(name: String) = TargetRepository.fromJson(File("src/main/assets/$name").readText())

    @Test fun bundledListIsSane() {
        val seed = asset("targets.json")
        assertTrue(seed.size >= 100)
        assertEquals("ids must be unique and stable", seed.size, seed.map { it.id }.toSet().size)
        assertEquals("names must be unique", seed.size, seed.map { it.name }.toSet().size)
        // Everything except KIND is within ~25 mi of Rushville.
        val rushville = GeoPoint(39.6092, -85.4464)
        seed.filter { it.type != TargetType.AIRPORT }.forEach {
            assertTrue(it.name, GeoMath.distanceMeters(rushville, GeoPoint(it.lat, it.lon)) < 40_000)
        }
        // The police tower sits at the police HQ, not at the mis-filed FCC coordinates west of it.
        val pd = seed.firstOrNull { it.name.startsWith("Rushville Police") }
        assertNotNull(pd)
        assertEquals(39.6248, pd!!.lat, 0.001)
        assertEquals(-85.4468, pd.lon, 0.001)
    }

    @Test fun upgradeKeepsUserEntriesAndReplacesOldDefaults() {
        val old = asset("targets_seed_v1.json") + asset("targets_seed_v2.json")
        val new = asset("targets.json")
        val mine = Target(id = "mine", name = "My barn", lat = 39.6, lon = -85.4)
        val edited = old[1].copy(name = "Renamed by me")
        val stored = old.drop(2).map { if (it.id == "fcc:1028174") it.copy(visible = false) else it } + mine + edited
        val merged = TargetRepository.mergeSeed(stored, old, new)
        assertEquals(new.size + 2, merged.size)
        // Untouched defaults are refreshed to the new entry but keep their hidden/shown state.
        assertEquals(new.first { it.id == "fcc:1028174" }.copy(visible = false), merged.first { it.id == "fcc:1028174" })
        assertTrue(merged.any { it.id == "mine" })
        assertTrue(merged.any { it.name == "Renamed by me" })
        assertEquals(1, merged.count { it.name == "KIND Indianapolis Intl" })
        // Running it again changes nothing.
        assertEquals(merged, TargetRepository.mergeSeed(merged, old, new))
    }
}
