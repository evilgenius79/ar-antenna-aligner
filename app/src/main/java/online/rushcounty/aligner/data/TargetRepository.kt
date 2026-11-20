package online.rushcounty.aligner.data

import android.content.Context
import online.rushcounty.aligner.geo.CoordParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Targets live in `files/targets.json` (a plain JSON array, same shape as the bundled
 * `assets/targets.json` seed, so lists can be shared between the app and the scanner project).
 */
class TargetRepository(private val context: Context) {

    private val file: File get() = File(context.filesDir, "targets.json")

    private val meta get() = context.getSharedPreferences("targets_meta", Context.MODE_PRIVATE)

    private fun asset(name: String): List<Target> = runCatching {
        fromJson(context.assets.open(name).bufferedReader().use { it.readText() })
    }.getOrElse { emptyList() }

    fun load(): List<Target> {
        val seed = asset("targets.json")
        if (!file.exists()) {
            meta.edit().putInt("seedVersion", SEED_VERSION).apply()
            return seed
        }
        val stored = runCatching { fromJson(file.readText()) }.getOrElse { emptyList() }
        if (meta.getInt("seedVersion", 1) >= SEED_VERSION) return stored
        // The bundled list grew: swap untouched old defaults for the new list, keep the user's own entries.
        val merged = mergeSeed(stored, asset("targets_seed_v1.json") + asset("targets_seed_v2.json"), seed)
        save(merged)
        meta.edit().putInt("seedVersion", SEED_VERSION).apply()
        return merged
    }

    fun save(targets: List<Target>) {
        val tmp = File(context.filesDir, "targets.json.tmp")
        tmp.writeText(toJson(targets))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun resetToSeed(): List<Target> {
        file.delete()
        return load()
    }

    companion object {
        /** Bump when assets/targets.json changes so existing installs pick up the new sites. */
        const val SEED_VERSION = 3

        /**
         * Stored entries that are unmodified copies of an [oldSeed] entry (same name and position)
         * are swapped for the [newSeed] entry with the same id, or dropped if it is gone.
         * Everything else is kept, then [newSeed] entries not yet present are appended.
         */
        fun mergeSeed(stored: List<Target>, oldSeed: List<Target>, newSeed: List<Target>): List<Target> {
            fun same(a: Target, b: Target) =
                a.name == b.name && kotlin.math.abs(a.lat - b.lat) < 1e-6 && kotlin.math.abs(a.lon - b.lon) < 1e-6
            val byId = newSeed.associateBy { it.id }
            val kept = stored.mapNotNull { s ->
                if (oldSeed.any { same(s, it) }) byId[s.id]?.copy(visible = s.visible) else s
            }
            val ids = kept.map { it.id }.toSet()
            return kept + newSeed.filterNot { it.id in ids }
        }

        fun toJson(targets: List<Target>): String {
            val arr = JSONArray()
            targets.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("lat", t.lat)
                    put("lon", t.lon)
                    t.altMeters?.let { put("altMeters", it) }
                    put("type", t.type.name)
                    t.note?.let { put("note", it) }
                    if (!t.visible) put("visible", false)
                })
            }
            return arr.toString(2)
        }

        fun fromJson(text: String): List<Target> {
            val trimmed = text.trim()
            val arr = if (trimmed.startsWith("{")) JSONObject(trimmed).getJSONArray("targets") else JSONArray(trimmed)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                Target(
                    id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = o.optString("name", "Unnamed"),
                    lat = lat,
                    lon = lon,
                    altMeters = if (o.has("altMeters") && !o.isNull("altMeters")) o.getDouble("altMeters") else null,
                    type = TargetType.parse(o.optString("type")),
                    note = o.optString("note").ifBlank { null },
                    visible = o.optBoolean("visible", true),
                )
            }
        }

        /**
         * Import pasted text: a JSON array/object, or CSV-ish lines
         * `name, lat, lon[, altMeters[, type[, note]]]`. Lat/lon may also be a single quoted
         * DMS pair in FCC ULS style. Returns parsed targets (new ids) and the count of lines skipped.
         */
        fun parseImport(text: String): Pair<List<Target>, Int> {
            val t = text.trim()
            if (t.startsWith("[") || t.startsWith("{")) {
                return runCatching { fromJson(t).map { it.copy(id = java.util.UUID.randomUUID().toString()) } to 0 }
                    .getOrElse { emptyList<Target>() to 1 }
            }
            return CsvImport.parse(t)
        }
    }
}

/** Pure-Kotlin CSV import (no Android deps, so it is unit-testable). */
object CsvImport {
    fun parse(text: String): Pair<List<Target>, Int> {
        val out = mutableListOf<Target>()
        var skipped = 0
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val cols = splitCsv(line)
            val target = parseCols(cols)
            if (target != null) out += target
            else if (!looksLikeHeader(cols)) skipped++
        }
        return out to skipped
    }

    private fun parseCols(cols: List<String>): Target? {
        if (cols.size < 2) return null
        val name = cols[0].ifBlank { return null }
        // Form A: name, lat, lon, ...
        val lat = cols.getOrNull(1)?.let(CoordParser::parseSingle)
        val lon = cols.getOrNull(2)?.let(CoordParser::parseSingle)
        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
            return Target(
                name = name, lat = lat, lon = lon,
                altMeters = cols.getOrNull(3)?.toDoubleOrNull(),
                type = TargetType.parse(cols.getOrNull(4)),
                note = cols.drop(5).joinToString(", ").ifBlank { null },
            )
        }
        // Form B: name, "<lat lon pair>", ...
        val pair = CoordParser.parsePair(cols[1]) ?: return null
        return Target(
            name = name, lat = pair.first, lon = pair.second,
            altMeters = cols.getOrNull(2)?.toDoubleOrNull(),
            type = TargetType.parse(cols.getOrNull(3)),
            note = cols.drop(4).joinToString(", ").ifBlank { null },
        )
    }

    private fun looksLikeHeader(cols: List<String>) =
        cols.any { it.equals("lat", true) || it.equals("latitude", true) || it.equals("name", true) }

    /** Minimal CSV split honoring double quotes. */
    fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }
}
