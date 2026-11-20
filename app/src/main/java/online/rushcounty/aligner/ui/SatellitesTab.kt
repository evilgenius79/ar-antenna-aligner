package online.rushcounty.aligner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import online.rushcounty.aligner.geo.GeoPoint
import online.rushcounty.aligner.geo.LookAngles
import online.rushcounty.aligner.sat.OrbitMath
import online.rushcounty.aligner.sat.OrbitalElements
import online.rushcounty.aligner.sat.SatelliteRepository

private class SatRow(val el: OrbitalElements, val subLon: Double, val look: LookAngles?)

@Composable
fun SatellitesTab(vm: AppViewModel) {
    val sats by vm.satellites.collectAsStateWithLifecycle()
    val shown by vm.shownSats.collectAsStateWithLifecycle()
    val status by vm.satStatus.collectAsStateWithLifecycle()
    val fix by vm.location.fix.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var uhfOnly by rememberSaveable { mutableStateOf(true) }
    var aboveOnly by rememberSaveable { mutableStateOf(true) }

    val now = remember(sats) { System.currentTimeMillis() }
    val rows = remember(sats, fix, now) {
        val obs = fix?.let { GeoPoint(it.lat, it.lon, it.altMeters) }
        sats.map { el ->
            val pos = OrbitMath.positionEcef(el, now)
            SatRow(el, pos.longitudeDeg, obs?.let { OrbitMath.lookAngles(it, pos) })
        }
    }
    val filtered = rows.filter { r ->
        (!uhfOnly || SatelliteRepository.DEFAULT_PREFIXES.any { r.el.name.startsWith(it) }) &&
            (!aboveOnly || r.look == null || r.look.elevationDeg > 0) &&
            (query.isBlank() || r.el.name.contains(query.trim(), ignoreCase = true) || r.el.noradId.toString() == query.trim())
    }.sortedWith(compareByDescending<SatRow> { it.el.noradId in shown }.thenByDescending { it.look?.elevationDeg ?: 0.0 })

    val newestEpoch = sats.maxOfOrNull { it.epochMillis }
    val ageDays = newestEpoch?.let { (System.currentTimeMillis() - it) / 86_400_000.0 }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    ageDays == null -> "No orbit data"
                    ageDays < 1 -> "Orbit data from today"
                    else -> "Orbit data ${"%.0f".format(ageDays)} day${if (ageDays >= 1.5) "s" else ""} old"
                } + if (ageDays != null && ageDays > 30) " — update for accurate positions" else "",
                fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = vm::refreshSatellites) { Text("Update from CelesTrak", fontSize = 12.sp) }
        }
        status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(
            query, { query = it }, label = { Text("Search name or NORAD id") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = uhfOnly, onClick = { uhfOnly = !uhfOnly }, label = { Text("UHF milsat families") })
            FilterChip(selected = aboveOnly, onClick = { aboveOnly = !aboveOnly }, label = { Text("Above my horizon") })
        }
        if (fix == null) Text("Waiting for GPS — positions in the sky need your location.", fontSize = 12.sp)
        Text("${filtered.size} shown of ${sats.size}", fontSize = 11.sp)

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.el.noradId }) { r ->
                val on = r.el.noradId in shown
                val selected = settings.selectedTargetId == satTargetId(r.el.noradId)
                Card(
                    Modifier.fillMaxWidth().clickable { vm.aimAtSatellite(r.el.noradId) },
                    colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else CardDefaults.cardColors(),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.el.name, fontWeight = FontWeight.Bold)
                            Text(
                                "over ${Format.longitude(r.subLon)}  ·  incl ${Format.deg(r.el.inclinationDeg)}  ·  #${r.el.noradId}",
                                fontSize = 12.sp,
                            )
                            r.look?.let { l ->
                                Text(
                                    if (l.elevationDeg > 0) "az ${Format.deg(l.bearingDeg)} ${online.rushcounty.aligner.geo.GeoMath.compassPoint(l.bearingDeg)}  ·  el ${Format.signedDeg(l.elevationDeg)}"
                                    else "Below your horizon (el ${Format.signedDeg(l.elevationDeg)})",
                                    fontSize = 13.sp,
                                )
                            }
                            TextButton(onClick = { vm.aimAtSatellite(r.el.noradId) }) { Text("Aim") }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Switch(checked = on, onCheckedChange = { vm.setSatShown(r.el.noradId, it) })
                            Text("in view", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
