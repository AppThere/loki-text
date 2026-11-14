package app.loki.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SlidersHorizontal

/** One line of the all-commands list: a group heading (command null) or a command. */
private class CommandRow(val heading: String?, val command: CommandInfo?)

/**
 * Every command the engine has, by place (the ribbon's tabs, then File and the rest), with a
 * search box: the way to anything on any screen. A command that asks for values opens its form
 * ([onRun] decides); one that only takes optional ones has an Options button for the form.
 */
@Composable
fun AllCommandsDialog(
    commands: List<CommandInfo>,
    profile: CommandProfile,
    initialSection: String?,
    /** Commands that can't run now, with the reason shown under each. */
    disabled: Map<String, String>,
    /** Toggles that are on, shown ticked. */
    on: Set<String>,
    /** Draws a command's icon, or nothing when it has none. */
    icon: @Composable (id: String) -> Unit,
    /** The order of a section's groups, as a rank per group name; groups it gives the same rank keep the engine's order. */
    groupRank: (section: String, group: String) -> Int = { _, _ -> 0 },
    onRun: (String) -> Unit,
    onOptions: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val listed = remember(commands) { commands.filter(profile::isListed) }
    val sections = remember(listed) { catalogSections(listed, profile) }
    var query by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(initialSection?.takeIf { it in sections } ?: sections.firstOrNull().orEmpty()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(8.dp).widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(0.92f), shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search commands") }, modifier = Modifier.weight(1f).padding(8.dp))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (query.isBlank() && sections.isNotEmpty()) {
                    ScrollableTabRow(selectedTabIndex = sections.indexOf(section).coerceAtLeast(0), edgePadding = 0.dp, divider = {}) {
                        sections.forEach { name -> Tab(selected = name == section, onClick = { section = name }, text = { Text(name, style = MaterialTheme.typography.labelLarge) }) }
                    }
                }
                HorizontalDivider()
                val rows = remember(query, section, listed) {
                    val out = ArrayList<CommandRow>()
                    val q = query.trim()
                    val hits = if (q.isEmpty()) listed.filter { it.tab.ifEmpty { "Other" } == section } else listed.filter { matchesQuery(it, q) }
                    // Grouped under the place each is found: the group on a tab, the whole path in a search.
                    val places = hits.groupBy { if (q.isEmpty()) it.path.drop(1).joinToString(" › ").ifEmpty { it.tab } else it.location }
                    // Groups in the order the app's ribbon shows them (the engine lists them by first command); the sort is stable.
                    val ordered = if (q.isEmpty()) places.entries.sortedBy { e -> groupRank(section, e.key.substringBefore(" › ")) } else places.entries
                    for ((place, group) in ordered) {
                        out += CommandRow(place, null)
                        group.mapTo(out) { CommandRow(null, it) }
                    }
                    out
                }
                if (rows.isEmpty()) Text("No command matches.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(rows) { r ->
                        val c = r.command
                        if (c == null) {
                            Text(r.heading.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                        } else {
                            val why = disabled[c.id]
                            Row(Modifier.fillMaxWidth().clickable(enabled = why == null) { onDismiss(); onRun(c.id) }.padding(start = 16.dp, end = 8.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.CenterStart) { icon(c.id) }
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    Text(
                                        (if (c.id in on) "✓ " else "") + c.label + (if (c.needsInput) "…" else ""),
                                        style = MaterialTheme.typography.bodyMedium, color = if (why == null) Color.Unspecified else MaterialTheme.colorScheme.outline,
                                    )
                                    if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                if (c.shortcut.isNotEmpty()) Text(displayShortcut(c.shortcut), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                // Optional values: the command runs as it is, and this opens its form.
                                if (!c.needsInput && c.hasParams) {
                                    IconButton(onClick = { onDismiss(); onOptions(c.id) }, enabled = why == null, modifier = Modifier.size(36.dp)) {
                                        Icon(Lucide.SlidersHorizontal, contentDescription = "${c.label} options", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

