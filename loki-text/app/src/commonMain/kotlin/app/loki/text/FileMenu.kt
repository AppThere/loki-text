package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu

/** One entry of the File menu: the command id (which picks the icon), its label and shortcut. */
internal class FileMenuItem(val id: String, val label: String, val shortcut: String = "")

/**
 * The File menu's entries in order; a null is a divider. `file.*` and `review.*` ids are engine
 * commands (the ones that write a file ask where first; the rest report, or open their form);
 * `app.*` ids are this app's own: the theme, the all-commands list, About.
 */
internal val FILE_MENU: List<FileMenuItem?> = listOf(
    FileMenuItem("file.new", "New", "Ctrl+N"),
    FileMenuItem("file.open", "Open…", "Ctrl+O"),
    null,
    FileMenuItem("file.save", "Save", "Ctrl+S"),
    FileMenuItem("file.saveAs", "Save As…"),
    FileMenuItem("file.saveTemplate", "Save as Template…"),
    null,
    FileMenuItem("file.exportPdf", "Export PDF…"),
    FileMenuItem("file.exportPng", "Export Page as PNG…"),
    FileMenuItem("file.print", "Print…", "Ctrl+P"),
    null,
    FileMenuItem("file.info", "Info"),
    FileMenuItem("file.properties", "Properties…"),
    FileMenuItem("file.options", "Options…"),
    null,
    FileMenuItem("app.theme.system", "Theme: System"),
    FileMenuItem("app.theme.light", "Theme: Light"),
    FileMenuItem("app.theme.dark", "Theme: Dark"),
    null,
    FileMenuItem("app.commands", "All Commands…"),
    FileMenuItem("app.about", "About $APP_NAME"),
)

/** The menu button in the top panel; [onRun] gets the chosen entry's id. */
@Composable
internal fun FileMenuButton(checked: String? = null, onRun: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        Icon(Lucide.Menu, contentDescription = "Menu", modifier = Modifier.size(18.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in FILE_MENU) {
                if (item == null) { HorizontalDivider(); continue }
                DropdownMenuItem(
                    text = { Text((if (item.id == checked) "✓ " else "") + item.label) },
                    leadingIcon = { if (hasRibbonIcon(item.id)) RibbonIcon(item.id) },
                    trailingIcon = if (item.shortcut.isEmpty()) null else {
                        { Text(displayShortcut(item.shortcut), style = MaterialTheme.typography.labelSmall) }
                    },
                    onClick = { open = false; onRun(item.id) },
                )
            }
        }
    }
}
