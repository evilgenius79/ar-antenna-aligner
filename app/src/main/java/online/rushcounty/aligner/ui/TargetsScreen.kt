package online.rushcounty.aligner.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import online.rushcounty.aligner.data.Category
import online.rushcounty.aligner.data.Target
import online.rushcounty.aligner.data.TargetType
import online.rushcounty.aligner.geo.CoordParser
import online.rushcounty.aligner.geo.GeoMath
import online.rushcounty.aligner.geo.GeoPoint
import online.rushcounty.aligner.sensors.Fix

@Composable
fun TargetsScreen(vm: AppViewModel) {
    val targets by vm.targets.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val fix by vm.location.fix.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var editing by remember { mutableStateOf<Target?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Target?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var addingArea by remember { mutableStateOf(false) }
    val areaStatus by vm.areaStatus.collectAsStateWithLifecycle()
    val areaBusy by vm.areaBusy.collectAsStateWithLifecycle()
    var satTab by rememberSaveable { mutableStateOf(settings.selectedTargetId?.startsWith("sat:") == true) }

    BackHandler { vm.show(Screen.AR) }

    var query by rememberSaveable { mutableStateOf("") }
    val sorted = remember(targets, fix, query) {
        val f = fix
        val q = query.trim()
        val shown = if (q.isEmpty()) targets
        else targets.filter { it.name.contains(q, ignoreCase = true) || it.note?.contains(q, ignoreCase = true) == true }
        if (f == null) shown
        else shown.sortedBy { GeoMath.distanceMeters(GeoPoint(f.lat, f.lon), GeoPoint(it.lat, it.lon)) }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.show(Screen.AR) }) { Text("‹ Back") }
            Text("Targets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (!satTab) Button(onClick = { creating = true }) { Text("+ Add") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !satTab, onClick = { satTab = false }, label = { Text("Ground") })
            FilterChip(selected = satTab, onClick = { satTab = true }, label = { Text("Satellites") })
        }
        if (satTab) {
            SatellitesTab(vm)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { importing = true }) { Text("Import") }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, "targets.json")
                    putExtra(Intent.EXTRA_TEXT, vm.exportJson())
                }
                context.startActivity(Intent.createChooser(send, "Export targets"))
            }) { Text("Export") }
            TextButton(onClick = { confirmReset = true }) { Text("Defaults") }
            TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
        Button(onClick = { vm.clearAreaStatus(); addingArea = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Add antennas near a ZIP code")
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Search name, frequency or call sign (${sorted.size})") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
        val counts = remember(targets) { Category.counts(targets) }
        if (counts.size > 1) {
            // Tap a category to hide or show all of its markers in the camera view.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val anyHidden = counts.any { it.first.code in settings.hiddenCats }
                TextButton(onClick = { vm.setCategoriesShown(counts.map { it.first }, anyHidden) }) {
                    Text(if (anyHidden) "Show all" else "Hide all")
                }
                counts.forEach { (c, n) ->
                    val on = c.code !in settings.hiddenCats
                    FilterChip(
                        selected = on, onClick = { vm.setCategoryShown(c, !on) },
                        label = { Text("${c.label} $n", fontSize = 12.sp) },
                    )
                }
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sorted, key = { it.id }) { t ->
                TargetRow(
                    t = t,
                    fix = fix,
                    imperial = settings.imperial,
                    selected = t.id == settings.selectedTargetId,
                    categoryOff = !Category.shown(t, settings.hiddenCats),
                    onAim = { vm.select(t.id); vm.show(Screen.AR) },
                    onEdit = { editing = t },
                    onToggle = { vm.toggleVisible(t.id) },
                    onDelete = { confirmDelete = t },
                )
            }
        }
    }

    if (creating || editing != null) {
        EditTargetDialog(
            initial = editing,
            fix = fix,
            onDismiss = { creating = false; editing = null },
            onSave = { vm.upsert(it); creating = false; editing = null },
        )
    }
    if (importing) {
        ImportDialog(
            onDismiss = { importing = false },
            onImport = { text ->
                val (n, skipped) = vm.import(text)
                Toast.makeText(context, "Imported $n" + if (skipped > 0) ", skipped $skipped line(s)" else "", Toast.LENGTH_LONG).show()
                if (n > 0) importing = false
            },
        )
    }
    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${t.name}?") },
            confirmButton = { TextButton(onClick = { vm.delete(t.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (addingArea) {
        AreaDialog(
            hasFix = fix != null,
            status = areaStatus,
            busy = areaBusy,
            onAdd = { zip, radius, kinds -> vm.addArea(zip, radius, kinds) },
            onDismiss = { addingArea = false },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the whole list?") },
            text = { Text("Removes every ground target (${targets.size}). Satellites are not affected. Export first if you want a copy.") },
            confirmButton = { TextButton(onClick = { vm.clearTargets(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset targets?") },
            text = { Text("Replaces your list with the bundled defaults (Rush County, Indiana). Export first if you want to keep your edits.") },
            confirmButton = { TextButton(onClick = { vm.resetTargets(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TargetRow(
    t: Target, fix: Fix?, imperial: Boolean, selected: Boolean, categoryOff: Boolean,
    onAim: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onAim),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name + if (!t.visible) "  (hidden)" else if (categoryOff) "  (category off)" else "",
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                Text(
                    if (t.cats.isEmpty()) t.type.label else Category.of(t).take(2).joinToString(" · ") { it.label },
                    fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (fix != null) {
                val obs = GeoPoint(fix.lat, fix.lon, fix.altMeters)
                val l = GeoMath.lookAngles(obs, t.toGeoPoint(fix.altMeters))
                Text(
                    "${Format.deg(l.bearingDeg)} ${GeoMath.compassPoint(l.bearingDeg)}  ·  ${Format.distance(l.distanceMeters, imperial)}  ·  elev ${Format.signedDeg(l.elevationDeg, 2)}",
                    fontSize = 13.sp,
                )
            }
            Text(
                Format.coord(t.lat, t.lon) + (t.altMeters?.let { "  ·  ${Format.height(it, imperial)} AMSL" } ?: ""),
                fontSize = 12.sp,
            )
            t.note?.let { Text(it, fontSize = 12.sp, maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAim) { Text("Aim") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onToggle) { Text(if (t.visible) "Hide" else "Show") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun EditTargetDialog(initial: Target?, fix: Fix?, onDismiss: () -> Unit, onSave: (Target) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var coords by remember { mutableStateOf(initial?.let { Format.coord(it.lat, it.lon) } ?: "") }
    var alt by remember { mutableStateOf(initial?.altMeters?.let { "%.1f".format(java.util.Locale.US, it) } ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: TargetType.TOWER) }
    var note by remember { mutableStateOf(initial?.note ?: "") }

    val parsed = CoordParser.parsePair(coords)
    val altVal = alt.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val altOk = alt.isBlank() || altVal != null
    val ok = name.isNotBlank() && parsed != null && altOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add target" else "Edit target") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    coords, { coords = it },
                    label = { Text("Lat, Lon") },
                    supportingText = {
                        Text(
                            if (parsed != null) "→ ${Format.coord(parsed.first, parsed.second)}"
                            else "Decimal (39.7173, -86.2944) or FCC DMS (39-43-02.3 N 86-17-39.8 W)"
                        )
                    },
                    isError = coords.isNotBlank() && parsed == null,
                )
                if (fix != null) {
                    TextButton(onClick = {
                        coords = Format.coord(fix.lat, fix.lon)
                        alt = "%.1f".format(java.util.Locale.US, fix.altMeters)
                    }) { Text("Use my current location") }
                }
                OutlinedTextField(
                    alt, { alt = it },
                    label = { Text("Antenna height AMSL (m) — optional") },
                    supportingText = { Text("Ground elevation + antenna height. Blank = assume level with you.") },
                    isError = !altOk, singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TargetType.entries.filter { !it.generated }.forEach { tt ->
                        FilterChip(selected = type == tt, onClick = { type = tt }, label = { Text(tt.label, fontSize = 12.sp) })
                    }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note (freq, callsign…)") })
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                val p = parsed ?: return@TextButton
                val base = initial ?: Target(name = name.trim(), lat = p.first, lon = p.second)
                onSave(
                    base.copy(
                        name = name.trim(), lat = p.first, lon = p.second, altMeters = altVal,
                        type = type, note = note.trim().ifBlank { null },
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import targets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Paste a JSON list (same format as Export), or CSV lines:\n" +
                        "name, lat, lon, altMeters, type, note\n" +
                        "e.g.  WZX813, 39.607278, -85.443861, 326, TOWER, 155.130",
                    fontSize = 12.sp,
                )
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.let { text = it.toString() }
                }) { Text("Paste from clipboard") }
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(200.dp))
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onImport(text) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
