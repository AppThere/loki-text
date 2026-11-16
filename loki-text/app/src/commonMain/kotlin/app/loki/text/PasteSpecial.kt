package app.loki.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Paste Special: the formats the clipboard can be pasted as, as (id, label) from the engine, and
 * the clipboard parameters ([source]: the system clipboard's text, or nothing when it still holds
 * this document's own copy) that go with the choice.
 */
class PasteSpecialRequest(val formats: List<Pair<String, String>>, val source: JsonObject)

/** The formats `edit.pasteSpecial` lists when asked without one: `{"formats": [{"id", "label"}]}`. */
internal fun parsePasteFormats(result: JsonElement?): List<Pair<String, String>> =
    ((result as? JsonObject)?.get("formats") as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        id to ((o["label"] as? JsonPrimitive)?.contentOrNull ?: id)
    }

/** The paste in format [id], with the same clipboard parameters the formats were listed from. */
internal fun pasteSpecialParams(request: PasteSpecialRequest, id: String): JsonObject =
    JsonObject(request.source + ("as" to JsonPrimitive(id)))

/** Paste Special: one choice per format; OK pastes the clipboard as the chosen one. */
@Composable
fun PasteSpecialDialog(request: PasteSpecialRequest, onPaste: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember(request) { mutableStateOf(request.formats.firstOrNull()?.first) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste Special") },
        text = {
            Column {
                Text("Paste as:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                for ((id, label) in request.formats) {
                    Row(Modifier.fillMaxWidth().clickable { chosen = id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen == id, onClick = { chosen = id })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton({ chosen?.let { onPaste(pasteSpecialParams(request, it)) } }, enabled = chosen != null) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
