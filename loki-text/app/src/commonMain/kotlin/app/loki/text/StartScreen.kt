package app.loki.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What the app shows on launch before any document is open: start a new document, open one, or pick up
 * one of the recent ones. A recent document whose file is gone is listed greyed with a note, and can be
 * removed from the list.
 *
 * [recents] is the list to show (newest first); [exists] says whether a file is still there; [now] dates them.
 */
@Composable
internal fun StartScreen(
    recents: List<RecentDocument>,
    exists: (String) -> Boolean,
    now: Long,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onOpenRecent: (RecentDocument) -> Unit,
    onForget: (RecentDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp)) {
            Text(APP_NAME, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { contentDescription = "$APP_NAME start" })
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onNew) { Text("New document") }
                OutlinedButton(onClick = onOpen) { Text("Open…") }
            }
            Spacer(Modifier.height(24.dp))
            Text("Recent", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            if (recents.isEmpty()) {
                Text(
                    "Documents you open or save will be listed here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(recents, key = { it.path }) { r ->
                        val present = exists(r.path)
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = present) { onOpenRecent(r) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                val dim = if (present) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                                Text(r.name, style = MaterialTheme.typography.bodyLarge, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val detail = if (present) listOf(r.folder, ageLabel(r.openedAt, now)).filter { it.isNotEmpty() }.joinToString("  ·  ") else "File not found"
                                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { onForget(r) }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
