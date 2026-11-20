package online.rushcounty.aligner.data

import android.content.Context

data class Settings(
    /** User sight-in correction added to true heading (degrees). */
    val headingOffsetDeg: Double = 0.0,
    /** Antenna beamwidth (full angle, degrees) shown as a circle around the crosshair. */
    val beamwidthDeg: Double = 30.0,
    /** Multiplier on the computed camera focal length; tweak if markers drift as you pan. */
    val fovScale: Double = 1.0,
    val smoothing: Float = 0.6f,
    val imperial: Boolean = true,
    val nightMode: Boolean = false,
    val haptics: Boolean = true,
    val showHorizon: Boolean = true,
    /** Dashed arc where geostationary satellites sit, as seen from here. */
    val showClarkeBelt: Boolean = true,
    val showSunMoon: Boolean = true,
    /** Hide towers/repeaters farther than this in the AR view (km); 0 = show all. */
    val maxTowerRangeKm: Double = 0.0,
    /** [Category] letters whose markers are switched off. */
    val hiddenCats: String = "",
    val selectedTargetId: String? = null,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        headingOffsetDeg = prefs.getFloat("headingOffsetDeg", 0f).toDouble(),
        beamwidthDeg = prefs.getFloat("beamwidthDeg", 30f).toDouble(),
        fovScale = prefs.getFloat("fovScale", 1f).toDouble(),
        smoothing = prefs.getFloat("smoothing", 0.6f),
        imperial = prefs.getBoolean("imperial", true),
        nightMode = prefs.getBoolean("nightMode", false),
        haptics = prefs.getBoolean("haptics", true),
        showHorizon = prefs.getBoolean("showHorizon", true),
        showClarkeBelt = prefs.getBoolean("showClarkeBelt", true),
        showSunMoon = prefs.getBoolean("showSunMoon", true),
        maxTowerRangeKm = prefs.getFloat("maxTowerRangeKm", 0f).toDouble(),
        hiddenCats = prefs.getString("hiddenCats", "") ?: "",
        selectedTargetId = prefs.getString("selectedTargetId", null),
    )

    fun save(s: Settings) {
        prefs.edit()
            .putFloat("headingOffsetDeg", s.headingOffsetDeg.toFloat())
            .putFloat("beamwidthDeg", s.beamwidthDeg.toFloat())
            .putFloat("fovScale", s.fovScale.toFloat())
            .putFloat("smoothing", s.smoothing)
            .putBoolean("imperial", s.imperial)
            .putBoolean("nightMode", s.nightMode)
            .putBoolean("haptics", s.haptics)
            .putBoolean("showHorizon", s.showHorizon)
            .putBoolean("showClarkeBelt", s.showClarkeBelt)
            .putBoolean("showSunMoon", s.showSunMoon)
            .putFloat("maxTowerRangeKm", s.maxTowerRangeKm.toFloat())
            .putString("hiddenCats", s.hiddenCats)
            .putString("selectedTargetId", s.selectedTargetId)
            .apply()
    }
}
