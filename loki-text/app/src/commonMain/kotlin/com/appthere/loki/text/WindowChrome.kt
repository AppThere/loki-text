package com.appthere.loki.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The slim bar across the top in Focus and Full Screen view: the way out (Esc too), and in Focus the ribbon for a
 * moment, in Full Screen view Search.
 */
@Composable
internal fun ImmersiveBar(screen: Screen, ribbonShown: Boolean, onExit: () -> Unit, onPeek: () -> Unit, onSearch: () -> Unit) {
    val dark = screen == Screen.Focus
    val fg = if (dark) Color(0xFFE6E6E6) else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().background(if (dark) Color(0xFF1B1B1B) else MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (dark) "Focus" else "Full screen", color = fg, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        if (dark) TextButton(onClick = onPeek) { Text(if (ribbonShown) "Hide ribbon" else "Show ribbon", color = fg) }
        else TextButton(onClick = onSearch) { Text("Search", color = fg) }
        TextButton(onClick = onExit, modifier = Modifier.semantics { contentDescription = "Leave ${if (dark) "Focus" else "full screen"} (Esc)" }) {
            Text(if (dark) "Leave Focus" else "Leave full screen", color = fg)
        }
    }
}

/** The bar between the two halves of a split window, with the way to end the split. */
@Composable
internal fun SplitBar(onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).height(28.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Split", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClose) { Text("Remove split", style = MaterialTheme.typography.labelMedium) }
    }
}

/** A list of windows to pick from (Switch Windows, View Side by Side); a null id is this window. */
@Composable
internal fun WindowListDialog(title: String, windows: List<Pair<Int?, String>>, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((id, name) in windows) {
                    Text(
                        if (id == null) "$name (this window)" else name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = id != null) { onPick(id) }.padding(vertical = 10.dp),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
