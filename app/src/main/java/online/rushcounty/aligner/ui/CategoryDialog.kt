package online.rushcounty.aligner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import online.rushcounty.aligner.data.Category

/** Switch whole categories of markers on or off from the camera view. */
@Composable
fun CategoryDialog(
    counts: List<Pair<Category, Int>>,
    hidden: String,
    onToggle: (Category, shown: Boolean) -> Unit,
    onAll: (List<Category>, shown: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val anyHidden = counts.any { it.first.code in hidden }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show markers for") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (counts.isEmpty()) Text("No ground targets yet. Add some from Targets.")
                counts.forEach { (c, n) ->
                    val on = c.code !in hidden
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(c, !on) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { onToggle(c, it) })
                        Text(c.label, modifier = Modifier.weight(1f))
                        Text("$n", fontSize = 13.sp)
                    }
                }
                Text(
                    "A site shared by several groups stays on while any of them is ticked. " +
                        "The target you are aiming at always shows.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (counts.isNotEmpty()) {
                TextButton(onClick = { onAll(counts.map { it.first }, anyHidden) }) {
                    Text(if (anyHidden) "Show all" else "Hide all")
                }
            }
        },
    )
}
