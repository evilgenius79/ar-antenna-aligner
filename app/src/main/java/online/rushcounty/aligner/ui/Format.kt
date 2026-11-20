package online.rushcounty.aligner.ui

import java.util.Locale
import kotlin.math.abs

object Format {
    fun distance(m: Double, imperial: Boolean): String = if (imperial) {
        val mi = m / 1609.344
        if (mi < 0.2) "%.0f ft".format(Locale.US, m * 3.28084)
        else if (mi < 10) "%.2f mi".format(Locale.US, mi)
        else if (mi < 1000) "%.1f mi".format(Locale.US, mi) else "%,.0f mi".format(Locale.US, mi)
    } else {
        if (m < 1000) "%.0f m".format(Locale.US, m)
        else if (m < 10_000) "%.2f km".format(Locale.US, m / 1000)
        else if (m < 1_000_000) "%.1f km".format(Locale.US, m / 1000) else "%,.0f km".format(Locale.US, m / 1000)
    }

    fun height(m: Double, imperial: Boolean): String =
        if (imperial) "%.0f ft".format(Locale.US, m * 3.28084) else "%.0f m".format(Locale.US, m)

    fun deg(d: Double, decimals: Int = 1): String = "%.${decimals}f°".format(Locale.US, d)

    fun signedDeg(d: Double, decimals: Int = 1): String = "%+.${decimals}f°".format(Locale.US, d)

    /** Longitude as e.g. "99.8°W". */
    fun longitude(lon: Double): String =
        "%.1f°%s".format(Locale.US, abs(lon), if (lon < 0) "W" else "E")

    fun coord(lat: Double, lon: Double): String = "%.5f, %.5f".format(Locale.US, lat, lon)

    /** Plain-language aiming hint, e.g. "Turn right 12.3°  ·  Tilt up 0.4°". */
    fun guidance(dAz: Double, dEl: Double): String {
        val h = when {
            abs(dAz) < 0.5 -> "Heading ✓"
            dAz > 0 -> "Turn right ${deg(abs(dAz))}"
            else -> "Turn left ${deg(abs(dAz))}"
        }
        val v = when {
            abs(dEl) < 0.5 -> "Tilt ✓"
            dEl > 0 -> "Tilt up ${deg(abs(dEl))}"
            else -> "Tilt down ${deg(abs(dEl))}"
        }
        return "$h  ·  $v"
    }
}
