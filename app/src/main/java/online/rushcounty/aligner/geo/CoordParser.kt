package online.rushcounty.aligner.geo

/**
 * Parses coordinates in the forms people actually paste:
 *  - decimal:          `39.7173, -86.2944`   `39.7173 -86.2944`
 *  - FCC ULS DMS:      `39-43-02.3 N  86-17-39.8 W`
 *  - degree symbols:   `39°43'02.3"N 86°17'39.8"W`
 *  - decimal + hemis:  `39.7173N 86.2944W`
 */
object CoordParser {

    // One coordinate: up to three numeric parts (d, m, s) with an optional hemisphere letter
    // before or after. Separators between parts are anything non-numeric except hemisphere letters.
    private val COORD = Regex(
        """([NSEWnsew])?\s*(-?\d+(?:\.\d+)?)(?:[°:\-\s]+(\d+(?:\.\d+)?))?(?:['′:\-\s]+(\d+(?:\.\d+)?))?\s*["″]?\s*([NSEWnsew])?"""
    )

    /** Returns (lat, lon) or null if the text isn't a recognizable coordinate pair. */
    fun parsePair(text: String): Pair<Double, Double>? {
        val cleaned = text.trim().replace("''", "\"")
        // Fast path: two plain decimals separated by comma/space.
        val plain = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,;\s]\s*(-?\d+(?:\.\d+)?)\s*$""").find(cleaned)
        if (plain != null) {
            val lat = plain.groupValues[1].toDouble()
            val lon = plain.groupValues[2].toDouble()
            return if (valid(lat, lon)) lat to lon else null
        }
        // Split at a comma if present, otherwise after the first hemisphere letter that ends a coordinate.
        val parts = splitPair(cleaned) ?: return null
        val a = parseOne(parts.first) ?: return null
        val b = parseOne(parts.second) ?: return null
        // Hemisphere letters, when present, decide which is lat and which is lon.
        val (lat, lon) = when {
            a.axis == Axis.LON && b.axis != Axis.LON -> b.value to a.value
            else -> a.value to b.value
        }
        return if (valid(lat, lon)) lat to lon else null
    }

    /** Parse a single coordinate (e.g. one text field). */
    fun parseSingle(text: String): Double? = parseOne(text.trim())?.value

    private enum class Axis { LAT, LON, UNKNOWN }
    private data class Parsed(val value: Double, val axis: Axis)

    private fun splitPair(s: String): Pair<String, String>? {
        if (s.contains(',')) {
            val i = s.indexOf(',')
            return s.substring(0, i) to s.substring(i + 1)
        }
        val hemi = Regex("""[NSns]""").find(s)
        if (hemi != null && hemi.range.first > 0) {
            return s.substring(0, hemi.range.last + 1) to s.substring(hemi.range.last + 1)
        }
        // Whitespace split for DMS without hemispheres ("39 43 02.3 -86 17 39.8") is ambiguous; require 2 tokens.
        val tokens = s.split(Regex("""\s+""")).filter { it.isNotBlank() }
        return if (tokens.size == 2) tokens[0] to tokens[1] else null
    }

    private fun parseOne(s: String): Parsed? {
        val m = COORD.matchEntire(s.trim()) ?: return null
        val hemi = (m.groupValues[1].ifEmpty { m.groupValues[5] }).uppercase()
        val degStr = m.groupValues[2]
        val deg = degStr.toDouble()
        val min = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val sec = m.groupValues[4].toDoubleOrNull() ?: 0.0
        if (min >= 60.0 || sec >= 60.0) return null
        val negative = degStr.startsWith("-")
        var v = kotlin.math.abs(deg) + min / 60.0 + sec / 3600.0
        if (negative || hemi == "S" || hemi == "W") v = -v
        val axis = when (hemi) {
            "N", "S" -> Axis.LAT
            "E", "W" -> Axis.LON
            else -> Axis.UNKNOWN
        }
        return Parsed(v, axis)
    }

    private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0
}
