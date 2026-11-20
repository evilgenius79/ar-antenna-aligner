package online.rushcounty.aligner

import online.rushcounty.aligner.data.Category
import online.rushcounty.aligner.data.SiteTiles
import online.rushcounty.aligner.data.Target
import online.rushcounty.aligner.data.TargetRepository
import online.rushcounty.aligner.data.TargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CategoryTest {
    private fun t(cats: String, type: TargetType = TargetType.TOWER) =
        Target(id = "t$cats$type", name = "x", lat = 0.0, lon = 0.0, type = type, cats = cats)

    @Test fun categoriesOfATarget() {
        assertEquals(listOf(Category.PUBLIC_SAFETY, Category.CELL), Category.of(t("PC")))
        // Hand-entered targets have no letters.
        assertEquals(listOf(Category.MINE), Category.of(t("")))
        assertEquals(listOf(Category.AIRPORT), Category.of(t("", TargetType.AIRPORT)))
        // Unknown letters from a newer data build are ignored.
        assertEquals(listOf(Category.RAILROAD), Category.of(t("Rz")))
        assertEquals(Category.entries.size, Category.entries.map { it.code }.toSet().size)
    }

    @Test fun sharedSiteStaysOnWhileAnyCategoryIsOn() {
        assertTrue(Category.shown(t("PC"), ""))
        assertTrue(Category.shown(t("PC"), "C"))
        assertFalse(Category.shown(t("PC"), "CP"))
        assertFalse(Category.shown(t("R"), "R"))
        assertTrue(Category.shown(t("R"), "PCX"))
        assertFalse(Category.shown(t(""), "X"))
    }

    @Test fun countsFollowMenuOrder() {
        val counts = Category.counts(listOf(t("R"), t("PC"), t("C"), t("")))
        assertEquals(
            listOf(Category.PUBLIC_SAFETY to 1, Category.RAILROAD to 1, Category.CELL to 2, Category.MINE to 1),
            counts,
        )
    }

    @Test fun tilesCarryCategoriesAndOldTilesFallBack() {
        val new = """{"sites":[["fcc:1","CSX",39.6,-85.4,null,"TOWER","B","160.23 MHz.","R"]]}"""
        assertEquals("R", SiteTiles.parse(new)[0].target.cats)
        val old = """{"sites":[["fcc:1","State tower",39.6,-85.4,null,"TOWER","PT",""],["fcc:2","Link",39.6,-85.4,null,"TOWER","M",""]]}"""
        assertEquals(listOf("PC", "M"), SiteTiles.parse(old).map { it.target.cats })
    }

    @Test fun bundledListIsCategorized() {
        val seed = TargetRepository.fromJson(File("src/main/assets/targets.json").readText())
        assertTrue(seed.all { it.cats.isNotEmpty() })
        assertTrue(seed.filter { it.name.startsWith("CSX") }.let { csx -> csx.size >= 5 && csx.all { it.cats == "R" } })
        assertTrue('P' in seed.first { it.name.startsWith("Rushville Police") }.cats)
        // Categories survive a save and reload.
        assertEquals(seed, TargetRepository.fromJson(TargetRepository.toJson(seed)))
    }

    @Test fun olderListsPickUpCategoriesById() {
        val seed = TargetRepository.fromJson(File("src/main/assets/targets.json").readText())
        val v3 = TargetRepository.fromJson(File("src/main/assets/targets_seed_v3.json").readText())
        assertTrue(v3.all { it.cats.isEmpty() })
        val mine = Target(id = "mine", name = "My barn", lat = 39.6, lon = -85.4)
        val renamed = v3[5].copy(name = "Renamed by me")
        val merged = TargetRepository.withCats(TargetRepository.mergeSeed(v3 - v3[5] + renamed + mine, v3, seed), seed)
        assertEquals(seed.size + 1, merged.size)
        assertEquals("", merged.first { it.id == "mine" }.cats)
        // An edited default keeps the edit and still gets its category.
        assertEquals(seed.first { it.id == renamed.id }.cats, merged.first { it.name == "Renamed by me" }.cats)
        assertTrue(merged.filter { it.id != "mine" }.all { it.cats.isNotEmpty() })
    }
}
