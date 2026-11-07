package app.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One text box in an Insert dialog. [browse] adds a button that asks for a file. */
class DialogField(val key: String, val label: String, val initial: String = "", val required: Boolean = false, val browse: Boolean = false)

/** The Insert commands that ask for typed input, and the boxes each one shows. */
enum class InsertDialog(val title: String, val command: String, val fields: List<DialogField>) {
    Link("Insert Link", "insert.link", listOf(DialogField("url", "Address", "https://", required = true), DialogField("text", "Text to display"))),
    Bookmark("Bookmark", "insert.bookmark", listOf(DialogField("name", "Bookmark name", required = true))),
    Picture("Insert Picture", "insert.picture", listOf(DialogField("path", "File", required = true, browse = true), DialogField("alt", "Alt text"), DialogField("width", "Width (pt)"))),
    TextBox("Text Box", "insert.textBox", listOf(DialogField("text", "Text"), DialogField("width", "Width (pt)", "200"), DialogField("height", "Height (pt)", "80"))),
    NewComment("New Comment", "review.newComment", listOf(DialogField("text", "Comment", required = true))),
    DateTime("Date & Time", "insert.dateTime", listOf(DialogField("format", "Format", "M/d/yyyy", required = true))),
}

/** Keys whose values are numbers (points) rather than text. */
private val NUMERIC_KEYS = setOf("width", "height")

/**
 * The parameters for [dialog] from what was typed, or null when a required box is empty or a number
 * isn't one. Blank optional boxes are left out so the engine's defaults apply.
 */
fun dialogParams(dialog: InsertDialog, values: Map<String, String>): JsonObject? {
    val out = buildJsonObject {
        for (f in dialog.fields) {
            val v = values[f.key].orEmpty().trim()
            if (v.isEmpty()) { if (f.required) return null; continue }
            if (f.key in NUMERIC_KEYS) {
                val n = v.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: return null
                put(f.key, n)
            } else put(f.key, v)
        }
    }
    return out
}

/** A modal form for [dialog]; OK is enabled once [dialogParams] accepts the boxes. */
@Composable
fun InsertDialogView(dialog: InsertDialog, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    val values = remember(dialog) { dialog.fields.associate { it.key to it.initial }.toMutableMap() }
    var version by remember(dialog) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val params = version.let { dialogParams(dialog, values) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in dialog.fields) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            values[f.key].orEmpty(),
                            { values[f.key] = it; version++ },
                            label = { Text(f.label) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        if (f.browse) TextButton({
                            scope.launch {
                                val p = withContext(Dispatchers.Default) { pickFile(save = false) }
                                if (p != null) { values[f.key] = p; version++ }
                            }
                        }) { Text("Browse…") }
                    }
                }
            }
        },
        confirmButton = { TextButton({ params?.let(onSubmit) }, enabled = params != null) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
