package app.loki.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.serialization.json.JsonObject

/** An engine command with its parameters. */
class PaneStep(val command: String, val params: JsonObject = JsonObject(emptyMap()))

/**
 * Something a pane row or header offers: [steps] run in order on the engine, or [ui] names a
 * command the app runs its own way (one that opens a dialog or a form).
 */
class PaneAction(val label: String, val steps: List<PaneStep> = emptyList(), val ui: String? = null)

/** One line of a pane: tapping it runs [tap]; [actions] are its buttons. */
class PaneRow(val title: String, val detail: String = "", val tap: PaneAction? = null, val actions: List<PaneAction> = emptyList())

/** What a pane shows: its [rows] (or [empty], when there are none) under the [header] actions. */
class PaneContent(val rows: List<PaneRow>, val empty: String, val header: List<PaneAction> = emptyList())

/**
 * A task pane that lists [content]: a title with a close button, the header actions, an [error]
 * line when the last action failed, and the rows (each at least a finger tall). [onAction] gets
 * the action of a tapped row or button. An app builds [content] from a command's answer.
 */
@Composable
fun PaneList(title: String, content: PaneContent, error: String, onAction: (PaneAction) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Lucide.X, contentDescription = "Close $title pane", modifier = Modifier.size(18.dp)) }
        }
        if (content.header.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (a in content.header) TextButton({ onAction(a) }) { Text(a.label, maxLines = 1) }
            }
        }
        if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (content.rows.isEmpty()) Text(content.empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(content.rows) { r ->
                Row(
                    Modifier.fillMaxWidth().then(if (r.tap != null) Modifier.clickable { onAction(r.tap) } else Modifier).heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (r.detail.isNotEmpty()) Text(r.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    for (a in r.actions) TextButton({ onAction(a) }) { Text(a.label, maxLines = 1) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
