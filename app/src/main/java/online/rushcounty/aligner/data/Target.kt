package online.rushcounty.aligner.data

import online.rushcounty.aligner.geo.GeoPoint
import java.util.UUID

enum class TargetType(val label: String) {
    AIRPORT("Airport"),
    TOWER("Tower"),
    REPEATER("Repeater"),
    CUSTOM("Custom"),
    /** Generated from orbital elements; never stored in targets.json. */
    SATELLITE("Satellite"),
    /** Sun or Moon, computed live. */
    CELESTIAL("Sun/Moon");

    /** Types the app computes itself rather than the user entering them. */
    val generated: Boolean get() = this == SATELLITE || this == CELESTIAL

    companion object {
        fun parse(s: String?): TargetType =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: CUSTOM
    }
}

/**
 * An RF target to aim at.
 * [altMeters] is the antenna height AMSL; null = unknown, treated as level with the observer
 * (curvature is still applied), which is fine for distant sites.
 */
data class Target(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val lat: Double,
    val lon: Double,
    val altMeters: Double? = null,
    val type: TargetType = TargetType.CUSTOM,
    val note: String? = null,
    val visible: Boolean = true,
) {
    fun toGeoPoint(observerAlt: Double): GeoPoint = GeoPoint(lat, lon, altMeters ?: observerAlt)
}
