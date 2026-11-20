package online.rushcounty.aligner.sat

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Geostationary satellite elements from CelesTrak's public "geo" group (OMM JSON).
 * A snapshot ships in assets so it works offline; [download] refreshes it into app storage.
 */
class SatelliteRepository(private val context: Context) {

    private val file: File get() = File(context.filesDir, "geo.json")
    private val prefs = context.getSharedPreferences("satellites", Context.MODE_PRIVATE)

    fun load(): List<OrbitalElements> {
        val text = if (file.exists()) file.readText()
        else context.assets.open("geo.json").bufferedReader().use { it.readText() }
        return runCatching { parse(text) }.getOrElse { emptyList() }
    }

    /** Blocking; call off the main thread. Returns the fresh list, or throws. */
    fun download(): List<OrbitalElements> {
        val conn = URL(URL_GEO).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "AntennaAligner/1 (Android)")
            if (conn.responseCode != 200) error("CelesTrak returned HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val list = parse(text) // validate before replacing the cache
            if (list.isEmpty()) error("CelesTrak returned no satellites")
            File(context.filesDir, "geo.json.tmp").apply { writeText(text) }.renameTo(file)
            return list
        } finally {
            conn.disconnect()
        }
    }

    /** NORAD ids the user has chosen to show in the AR view. */
    fun loadShown(all: List<OrbitalElements>): Set<Int> {
        val saved = prefs.getStringSet("shown", null)
        if (saved != null) return saved.mapNotNull { it.toIntOrNull() }.toSet()
        // First run: the UHF SATCOM birds this feature was built for.
        return all.filter { el -> DEFAULT_PREFIXES.any { el.name.startsWith(it) } }.map { it.noradId }.toSet()
    }

    fun saveShown(ids: Set<Int>) {
        prefs.edit().putStringSet("shown", ids.map { it.toString() }.toSet()).apply()
    }

    companion object {
        const val URL_GEO = "https://celestrak.org/NORAD/elements/gp.php?GROUP=geo&FORMAT=json"
        val DEFAULT_PREFIXES = listOf("UFO", "FLTSATCOM", "MUOS", "SKYNET 5")

        fun parse(text: String): List<OrbitalElements> {
            val arr = JSONArray(text)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                runCatching {
                    OrbitalElements(
                        name = o.getString("OBJECT_NAME").trim(),
                        noradId = o.getInt("NORAD_CAT_ID"),
                        epochMillis = parseEpoch(o.getString("EPOCH")),
                        meanMotionRevPerDay = o.getDouble("MEAN_MOTION"),
                        eccentricity = o.getDouble("ECCENTRICITY"),
                        inclinationDeg = o.getDouble("INCLINATION"),
                        raanDeg = o.getDouble("RA_OF_ASC_NODE"),
                        argPerigeeDeg = o.getDouble("ARG_OF_PERICENTER"),
                        meanAnomalyDeg = o.getDouble("MEAN_ANOMALY"),
                    )
                }.getOrNull()
            }
        }

        /** CelesTrak epochs are UTC without a zone suffix, e.g. 2026-11-06T22:48:36.205920. */
        fun parseEpoch(s: String): Long =
            LocalDateTime.parse(s.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
