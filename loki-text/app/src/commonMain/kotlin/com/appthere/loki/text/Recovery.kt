package com.appthere.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A copy of unsaved work the engine kept from an earlier run (a crash, or a window closed without saving). */
internal data class RecoveryCopy(val id: String, val name: String, val savedAt: String)

/** What `file.recover` reports: whether recovery runs, whether a job is under way, the copies, and the engine's complaint if any. */
internal data class RecoveryState(val enabled: Boolean, val busy: Boolean, val copies: List<RecoveryCopy>, val error: String?) {
    companion object { val NONE = RecoveryState(false, false, emptyList(), null) }
}

internal fun parseRecoveryState(r: JsonElement?): RecoveryState {
    val o = r as? JsonObject ?: return RecoveryState.NONE
    fun str(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val copies = (o["documents"] as? JsonArray).orEmpty().mapNotNull { e ->
        val d = e as? JsonObject ?: return@mapNotNull null
        RecoveryCopy(str(d, "id") ?: return@mapNotNull null, str(d, "name")?.ifBlank { null } ?: "Untitled", str(d, "savedAt").orEmpty())
    }
    return RecoveryState(
        (o["enabled"] as? JsonPrimitive)?.booleanOrNull == true,
        (o["busy"] as? JsonPrimitive)?.booleanOrNull == true,
        copies,
        str(o, "error"),
    )
}

/** An ISO-8601 time as "2026-11-23 10:44" (the engine's own date and zone, which is the person's own); anything else as it came. */
internal fun savedAtLabel(savedAt: String): String {
    val m = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})""").find(savedAt) ?: return savedAt
    return "${m.groupValues[1]} ${m.groupValues[2]}"
}

/** The copies, each with Restore and Discard. */
@Composable
internal fun RecoveryList(copies: List<RecoveryCopy>, onRestore: (RecoveryCopy) -> Unit, onDiscard: (RecoveryCopy) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        for (c in copies) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (c.savedAt.isNotEmpty()) Text("Kept ${savedAtLabel(c.savedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onRestore(c) }) { Text("Restore") }
                TextButton(onClick = { onDiscard(c) }) { Text("Discard") }
            }
            HorizontalDivider()
        }
    }
}

/** File › Recover Unsaved Documents. */
@Composable
internal fun RecoveryDialog(state: RecoveryState, onRestore: (RecoveryCopy) -> Unit, onDiscard: (RecoveryCopy) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recover unsaved documents") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !state.enabled -> Text("Recovery is not running on this device, so no copies are kept.")
                    state.copies.isEmpty() -> Text("There are no unsaved documents to recover.")
                    else -> {
                        Text("Restore opens a copy as a new, unsaved document; Save As keeps it. Discard deletes the copy for good.", style = MaterialTheme.typography.bodySmall)
                        RecoveryList(state.copies, onRestore, onDiscard)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
