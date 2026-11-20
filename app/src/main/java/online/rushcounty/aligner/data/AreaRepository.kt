package online.rushcounty.aligner.data

import android.content.Context
import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.GeoPoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.floor

/** What kinds of antenna site to pull in for an area. Letters match the `tags` field in the tiles. */
enum class SiteKind(val tag: Char, val label: String) {
    TOWER('T', "Registered towers (cell, broadcast, tall masts)"),
    PUBLIC_SAFETY('P', "Police, fire, EMS, government"),
    BUSINESS('B', "Business, utility, school, railroad radio"),
    MICROWAVE('M', "Microwave-only link sites"),
}

/** One antenna site from a tile, with the kinds it belongs to. */
class Site(val target: Target, val tags: String)

/**
 * Antenna sites for anywhere in the US, built from FCC licence and tower-registration data
 * (see `tools/build_tiles.py`) and published as one small file per 1-degree cell.
 */
object SiteTiles {
    /** Where the tiles are published. */
    const val BASE_URL = "https://raw.githubusercontent.com/evilgenius79/ar-antenna-aligner/data/tiles/"

    private const val KM_PER_DEG = 111.32

    /** Tile names (south-west corner, e.g. `39_-86`) that a circle touches. */
    fun tilesFor(lat: Double, lon: Double, radiusKm: Double): List<String> {
        val dLat = radiusKm / KM_PER_DEG
        val dLon = radiusKm / (KM_PER_DEG * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        val out = ArrayList<String>()
        for (la in floor(lat - dLat).toInt()..floor(lat + dLat).toInt()) {
            for (lo in floor(lon - dLon).toInt()..floor(lon + dLon).toInt()) out += "${la}_$lo"
        }
        return out
    }

    fun parse(json: String): List<Site> {
        val arr = JSONObject(json).getJSONArray("sites")
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONArray(i)
            Site(
                Target(
                    id = s.getString(0),
                    name = s.getString(1),
                    lat = s.getDouble(2),
                    lon = s.getDouble(3),
                    altMeters = if (s.isNull(4)) null else s.getDouble(4),
                    type = TargetType.parse(s.getString(5)),
                    note = s.optString(7).ifBlank { null },
                ),
                tags = s.getString(6),
            )
        }
    }

    /** Sites of the wanted kinds inside the circle, nearest first. */
    fun select(sites: List<Site>, center: GeoPoint, radiusKm: Double, kinds: Set<SiteKind>): List<Target> {
        val wanted = kinds.map { it.tag }
        return sites.asSequence()
            .filter { s -> wanted.any { it in s.tags } }
            .map { it.target to GeoMath.distanceMeters(center, GeoPoint(it.target.lat, it.target.lon)) }
            .filter { it.second <= radiusKm * 1000 }
            .sortedBy { it.second }
            .map { it.first }
            .toList()
    }
}

class AreaRepository(private val context: Context) {

    /** Centre of a 5-digit ZIP code from the bundled Census table, or null if unknown. */
    fun zipCenter(zip: String): GeoPoint? {
        val key = zip.trim().take(5)
        if (key.length != 5 || !key.all { it.isDigit() }) return null
        context.assets.open("zips.csv").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith(key)) continue
                val c = line.split(',')
                if (c.size >= 3 && c[0] == key) return GeoPoint(c[1].toDouble(), c[2].toDouble())
            }
        }
        return null
    }

    /** Blocking; call off the main thread. Downloads every tile the circle touches. */
    fun fetch(center: GeoPoint, radiusKm: Double, kinds: Set<SiteKind>, onProgress: (done: Int, total: Int) -> Unit): List<Target> {
        val names = SiteTiles.tilesFor(center.lat, center.lon, radiusKm)
        val all = ArrayList<Site>()
        names.forEachIndexed { i, name ->
            onProgress(i, names.size)
            downloadTile(name)?.let { all += SiteTiles.parse(it) }
        }
        return SiteTiles.select(all, center, radiusKm, kinds)
    }

    /** Null when the tile does not exist (open water, or outside the US). */
    private fun downloadTile(name: String): String? {
        val conn = URL("${SiteTiles.BASE_URL}$name.json.gz").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "AntennaAligner/1 (Android)")
            // Ask for the stored bytes as they are; the file itself is gzip.
            conn.setRequestProperty("Accept-Encoding", "identity")
            return when (conn.responseCode) {
                200 -> GZIPInputStream(conn.inputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }
                404 -> null
                else -> error("Server returned HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
