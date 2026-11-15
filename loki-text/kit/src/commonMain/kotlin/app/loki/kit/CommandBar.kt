package app.loki.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide

/** A button that opens a menu of [items] by name, each with its tick, its shortcut and, when it can't run, greyed. */
@Composable
fun CommandMenuButton(label: String, items: List<RibbonItem>, disabled: Map<String, String>, on: Set<String>, onRun: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(start = 8.dp, end = 2.dp), modifier = Modifier.height(36.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Lucide.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp)) {
            for (item in items) {
                val off = item.id in disabled
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !off) { open = false; onRun(item.id) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (item.id in on) "✓ " else "") + item.menuLabel,
                        style = MaterialTheme.typography.bodyMedium, color = if (off) MaterialTheme.colorScheme.outline else Color.Unspecified,
                        maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
                    )
                    if (item.shortcut.isNotEmpty()) {
                        Text(displayShortcut(item.shortcut), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 24.dp))
                    }
                }
            }
        }
    }
}

/**
 * The command bar every Craft front end can start from, at every window width: the tabs (an
 * engine's top-level menus, or its ribbon tabs), and under them ONE row that scrolls sideways. In
 * the row each group shows its commands that have an icon as buttons and the rest as a menu under
 * the group's name, so every command of the tab is in the row. [leading] is for the app's own
 * buttons (undo, the all-commands magnifier). An app replaces a group with real controls where it
 * has them; this is the floor.
 */
@Composable
fun CommandBar(
    tabs: List<RibbonTab>,
    disabled: Map<String, String>,
    on: Set<String>,
    /** Has this command an icon? One with an icon and no value to ask for is a button. */
    hasIcon: (id: String) -> Boolean = { false },
    icon: @Composable (id: String) -> Unit = {},
    leading: @Composable () -> Unit = {},
    onRun: (String) -> Unit,
) {
    if (tabs.isEmpty()) return
    var chosen by remember { mutableStateOf(tabs.first().name) }
    val tab = tabs.firstOrNull { it.name == chosen } ?: tabs.first()
    Column(Modifier.fillMaxWidth()) {
        ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), edgePadding = 0.dp, divider = {}) {
            for (t in tabs) Tab(selected = t.name == tab.name, onClick = { chosen = t.name }) {
                Box(Modifier.height(40.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(t.name, style = MaterialTheme.typography.labelLarge, fontWeight = if (t.name == tab.name) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            tab.groups.forEach { g ->
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                val buttons = g.items.filter { hasIcon(it.id) && !it.needsInput }
                val rest = g.items - buttons.toSet()
                for (item in buttons) {
                    IconButton(onClick = { onRun(item.id) }, enabled = item.id !in disabled, modifier = Modifier.size(36.dp)) { icon(item.id) }
                }
                if (rest.isNotEmpty()) CommandMenuButton(if (buttons.isEmpty()) g.name.ifEmpty { tab.name } else "More", rest, disabled, on, onRun)
            }
        }
        HorizontalDivider()
    }
}
