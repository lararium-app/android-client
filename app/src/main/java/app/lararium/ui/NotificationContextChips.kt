package app.lararium.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lararium.assistant.NotificationSummary
import app.lararium.assistant.ScreenContextPolicy
import app.lararium.assistant.ScreenContextProvider

/** One offered notification line; [checked] defaults to false and is user-controlled. */
class ContextEntry(val summary: NotificationSummary) {
    var checked by mutableStateOf(false)
}

fun checkedContextSummaries(entries: List<ContextEntry>): List<NotificationSummary> =
    entries.filter { it.checked }.map { it.summary }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationContextChips(
    entries: MutableList<ContextEntry>,
    onExcludePackage: (String) -> Unit
) {
    if (entries.isEmpty()) return
    var expanded by remember { mutableStateOf(true) }
    var menuFor by remember { mutableStateOf<ContextEntry?>(null) }
    val appContext = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Context from recent notifications (${entries.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            entries.toList().forEach { entry ->
                val pkg = entry.summary.packageName
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { entry.checked = !entry.checked },
                                onLongClick = { menuFor = entry }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = entry.checked, onCheckedChange = { entry.checked = it })
                        Text(
                            "${ScreenContextProvider.appLabel(appContext, pkg)}: ${ScreenContextPolicy.displayLine(entry.summary)}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuFor === entry, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text("Exclude $pkg") },
                            onClick = {
                                menuFor = null
                                onExcludePackage(pkg)
                            }
                        )
                    }
                }
            }
        }
    }
}
