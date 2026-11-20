package online.rushcounty.aligner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val intr by vm.intrinsics.collectAsStateWithLifecycle()
    val fix by vm.location.fix.collectAsStateWithLifecycle()
    BackHandler { vm.show(Screen.AR) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.show(Screen.AR) }) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        Section("Antenna beamwidth: ${s.beamwidthDeg.toInt()}°",
            "Full -3 dB beamwidth of your antenna. Drawn as a dashed circle around the crosshair. " +
                "Typical: 5-el yagi ≈ 60°, 10-el ≈ 40°, long yagi ≈ 25°.")
        Slider(
            value = s.beamwidthDeg.toFloat(), valueRange = 5f..120f,
            onValueChange = { v -> vm.updateSettings { it.copy(beamwidthDeg = v.toInt().toDouble()) } },
        )

        Section(
            "Show towers within: " + if (s.maxTowerRangeKm <= 0.0) "any distance" else Format.distance(s.maxTowerRangeKm * 1000, s.imperial),
            "Hides towers and repeaters farther away than this in the camera view. The one you are aiming at always shows.")
        Slider(
            // Slider runs 1..41; the top step means no limit.
            value = if (s.maxTowerRangeKm <= 0.0) 41f else (s.maxTowerRangeKm / RANGE_STEP_KM).toFloat().coerceIn(1f, 40f),
            valueRange = 1f..41f, steps = 39,
            onValueChange = { v ->
                val n = Math.round(v)
                vm.updateSettings { it.copy(maxTowerRangeKm = if (n >= 41) 0.0 else n * RANGE_STEP_KM) }
            },
        )

        Section("Camera FOV scale: ${"%.3f".format(Locale.US, s.fovScale)}",
            "Leave at 1.000 unless markers slide off their tower as you pan left/right. " +
                "If a marker runs ahead of the real tower when you pan, lower it; if it lags behind, raise it.")
        Slider(
            value = s.fovScale.toFloat(), valueRange = 0.8f..1.2f,
            onValueChange = { v -> vm.updateSettings { it.copy(fovScale = (v * 200).toInt() / 200.0) } },
        )

        Section("Smoothing: ${"%.2f".format(Locale.US, s.smoothing)}",
            "Higher = steadier markers when holding still. Fast motion is never smoothed much.")
        Slider(value = s.smoothing, valueRange = 0f..1f, onValueChange = { v -> vm.updateSettings { it.copy(smoothing = v) } })

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Toggle("Imperial units (mi / ft)", s.imperial) { v -> vm.updateSettings { it.copy(imperial = v) } }
        Toggle("Haptic tick when on target", s.haptics) { v -> vm.updateSettings { it.copy(haptics = v) } }
        Toggle("Show level line + compass ticks", s.showHorizon) { v -> vm.updateSettings { it.copy(showHorizon = v) } }
        Toggle("Night mode (red, dimmed)", s.nightMode) { v -> vm.updateSettings { it.copy(nightMode = v) } }
        Toggle("Show geostationary belt (satellite arc)", s.showClarkeBelt) { v -> vm.updateSettings { it.copy(showClarkeBelt = v) } }
        Toggle("Show Sun and Moon", s.showSunMoon) { v -> vm.updateSettings { it.copy(showSunMoon = v) } }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Section("Sight-in offset: ${Format.signedDeg(s.headingOffsetDeg)}",
            "Set from the AR view with “Sight in”. Applied on top of magnetic declination.")
        TextButton(onClick = vm::clearSightIn, enabled = s.headingOffsetDeg != 0.0) { Text("Clear sight-in offset") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        val i = intr
        Text(
            if (i != null) "Camera: f = ${"%.2f".format(Locale.US, i.focalMm)} mm, active sensor " +
                "${"%.2f".format(Locale.US, i.activeWidthMm)} × ${"%.2f".format(Locale.US, i.activeHeightMm)} mm"
            else "Camera: no metadata (using a typical-phone default FOV)",
            fontSize = 13.sp,
        )
        fix?.let {
            Text("Position: ${Format.coord(it.lat, it.lon)}", fontSize = 13.sp)
            Text("Altitude: ${"%.1f".format(Locale.US, it.altMeters)} m ${if (it.altIsMsl) "AMSL" else "(WGS84 ellipsoid — MSL conversion unavailable)"}", fontSize = 13.sp)
            Text("Magnetic declination: ${Format.signedDeg(it.declinationDeg, 2)} (east +)", fontSize = 13.sp)
        }
    }
}

/** One slider step for the tower range limit: 1 mile. */
private const val RANGE_STEP_KM = 1.609344

@Composable
private fun Section(title: String, help: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(help, fontSize = 12.sp)
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
