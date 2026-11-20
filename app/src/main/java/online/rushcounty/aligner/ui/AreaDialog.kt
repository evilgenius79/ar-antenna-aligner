package online.rushcounty.aligner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import online.rushcounty.aligner.data.SiteKind

private val RADII_MI = listOf(10, 25, 50, 75, 100)

/** Pick a ZIP code (or the current GPS position), a radius and the kinds of site to add. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AreaDialog(
    hasFix: Boolean,
    status: String?,
    busy: Boolean,
    onAdd: (zip: String?, radiusMiles: Int, kinds: Set<SiteKind>) -> Unit,
    onDismiss: () -> Unit,
) {
    var zip by rememberSaveable { mutableStateOf("") }
    var useGps by rememberSaveable { mutableStateOf(false) }
    var radius by rememberSaveable { mutableStateOf(25) }
    var kinds by rememberSaveable {
        mutableStateOf(setOf(SiteKind.TOWER.name, SiteKind.PUBLIC_SAFETY.name, SiteKind.BUSINESS.name))
    }
    val ready = (useGps && hasFix || zip.length == 5) && kinds.isNotEmpty() && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add antennas near…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = zip,
                    onValueChange = { v -> zip = v.filter { it.isDigit() }.take(5); if (zip.isNotEmpty()) useGps = false },
                    label = { Text("ZIP code") }, singleLine = true, enabled = !useGps,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useGps, onCheckedChange = { useGps = it }, enabled = hasFix)
                    Text(if (hasFix) "Use my current location instead" else "Current location (waiting for GPS)")
                }
                Text("Within", fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RADII_MI.forEach { r ->
                        FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("$r mi") })
                    }
                }
                Text("Include", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                SiteKind.entries.forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = k.name in kinds,
                            onCheckedChange = { on -> kinds = if (on) kinds + k.name else kinds - k.name },
                        )
                        Text(k.label, fontSize = 14.sp)
                    }
                }
                Text(
                    if (radius >= 50) "A big radius around a city can add thousands of sites. " +
                        "Settings → “Show towers within” keeps the camera view readable."
                    else "Data: FCC licences and tower registrations. Needs an internet connection.",
                    fontSize = 12.sp,
                )
                if (status != null) Text(status, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = { onAdd(if (useGps) null else zip, radius, kinds.map { SiteKind.valueOf(it) }.toSet()) },
            ) { Text(if (busy) "Working…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
