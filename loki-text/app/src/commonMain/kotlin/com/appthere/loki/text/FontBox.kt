package com.appthere.loki.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** [fonts] that contain [query], those starting with it first; all of them for a blank query. */
internal fun fontMatches(query: String, fonts: List<String>): List<String> {
    val q = query.trim().lowercase()
    return if (q.isEmpty()) fonts else fonts.filter { it.lowercase().contains(q) }.sortedBy { !it.lowercase().startsWith(q) }
}

/** A font name to type, over a list of the document's fonts that narrows as it is typed. */
@Composable
internal fun FontBox(label: String, value: String, fonts: List<String>, enabled: Boolean = true, listHeight: Dp = 110.dp, onChange: (String) -> Unit) {
    val matches = remember(value, fonts) { fontMatches(value, fonts).take(200) }
    Column {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        if (enabled && fonts.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().height(listHeight)) {
            items(matches) { f ->
                Text(
                    f,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (f == value) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth()
                        .semantics { selected = f == value }
                        .clickable(role = Role.Button) { onChange(f) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
