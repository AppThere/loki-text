package app.loki.text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

/** The Font dialog's fields. Text fields stay text until OK so a half-typed number is never sent. */
internal data class FontForm(
    val font: String = "",
    val size: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val smallCaps: Boolean = false,
    val caps: Boolean = false,
    val hidden: Boolean = false,
    /** `RRGGBB`, or `auto`. */
    val color: String = "auto",
    /** Extra space between characters, points. */
    val spacing: String = "0",
)

private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** The dialog's starting values from `format.state`. A property the selection mixes shows blank/off. */
internal fun fontFormFrom(state: JsonObject): FontForm = FontForm(
    font = state.str("font").orEmpty(),
    size = sizeText((state["size"] as? JsonPrimitive)?.floatOrNull),
    bold = state.bool("bold"),
    italic = state.bool("italic"),
    underline = state.bool("underline"),
    strike = state.bool("strike"),
    superscript = state.bool("superscript"),
    subscript = state.bool("subscript"),
    smallCaps = state.bool("smallCaps"),
    caps = state.bool("caps"),
    hidden = state.bool("hidden"),
    color = state.str("color")?.removePrefix("#")?.ifEmpty { null } ?: "auto",
    spacing = sizeText((state["spacing"] as? JsonPrimitive)?.floatOrNull ?: 0f),
)

/**
 * The `format.set` props for what the user changed between [start] and [now]. Only changed fields
 * are sent, so formatting the dialog can't show (a mixed selection) is left as it is. A size,
 * spacing or colour that doesn't parse is ignored rather than guessed.
 */
internal fun fontChanges(start: FontForm, now: FontForm): JsonObject = buildJsonObject {
    if (now.font.trim().isNotEmpty() && now.font.trim() != start.font.trim()) put("font", now.font.trim())
    if (now.size.trim() != start.size.trim()) parseFontSize(now.size)?.let { put("size", it) }
    if (now.bold != start.bold) put("bold", now.bold)
    if (now.italic != start.italic) put("italic", now.italic)
    if (now.underline != start.underline) put("underline", if (now.underline) "single" else "none")
    if (now.strike != start.strike) put("strike", now.strike)
    if (now.superscript != start.superscript || now.subscript != start.subscript) {
        put("vertAlign", if (now.superscript) "superscript" else if (now.subscript) "subscript" else "baseline")
    }
    if (now.smallCaps != start.smallCaps) put("smallCaps", now.smallCaps)
    if (now.caps != start.caps) put("caps", now.caps)
    if (now.hidden != start.hidden) put("hidden", now.hidden)
    if (now.spacing.trim() != start.spacing.trim()) now.spacing.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.let { put("spacing", it.coerceIn(-100f, 100f)) }
    if (now.color.trim().lowercase() != start.color.trim().lowercase()) {
        val c = now.color.trim()
        if (c.equals("auto", ignoreCase = true)) put("color", "Auto")
        else parseHexColor(c)?.let { (r, g, b) -> put("color", buildJsonObject { put("Rgb", JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b)))) }) }
    }
}

/** The Font dialog: font list, size, effects, colour and spacing, with a sample. OK hands back the changes. */
@Composable
internal fun FontDialog(start: FontForm, fonts: List<String>, onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var form by remember { mutableStateOf(start) }
    val matches = remember(form.font, fonts) {
        val q = form.font.trim().lowercase()
        if (q.isEmpty()) fonts else fonts.filter { it.lowercase().contains(q) }.sortedBy { !it.lowercase().startsWith(q) }
    }
    val sizeOk = form.size.isBlank() || parseFontSize(form.size) != null
    val spacingOk = form.spacing.isBlank() || form.spacing.trim().toFloatOrNull()?.isFinite() == true
    val colorOk = form.color.equals("auto", ignoreCase = true) || parseHexColor(form.color.trim()) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Font") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.font, { form = form.copy(font = it) }, label = { Text("Font") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
                    items(matches.take(200)) { f ->
                        Text(
                            f,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (f == form.font) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { form = form.copy(font = f) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(form.size, { form = form.copy(size = it) }, label = { Text("Size (pt)") }, singleLine = true, isError = !sizeOk, modifier = Modifier.weight(1f))
                    OutlinedTextField(form.color, { form = form.copy(color = it.removePrefix("#")) }, label = { Text("Color (RRGGBB or auto)") }, singleLine = true, isError = !colorOk, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(form.spacing, { form = form.copy(spacing = it) }, label = { Text("Character spacing (pt)") }, singleLine = true, isError = !spacingOk, modifier = Modifier.fillMaxWidth())
                Row {
                    Column(Modifier.weight(1f)) {
                        Check("Bold", form.bold) { form = form.copy(bold = it) }
                        Check("Italic", form.italic) { form = form.copy(italic = it) }
                        Check("Underline", form.underline) { form = form.copy(underline = it) }
                        Check("Strikethrough", form.strike) { form = form.copy(strike = it) }
                        Check("Hidden", form.hidden) { form = form.copy(hidden = it) }
                    }
                    Column(Modifier.weight(1f)) {
                        Check("Superscript", form.superscript) { form = form.copy(superscript = it, subscript = if (it) false else form.subscript) }
                        Check("Subscript", form.subscript) { form = form.copy(subscript = it, superscript = if (it) false else form.superscript) }
                        Check("Small caps", form.smallCaps) { form = form.copy(smallCaps = it, caps = if (it) false else form.caps) }
                        Check("All caps", form.caps) { form = form.copy(caps = it, smallCaps = if (it) false else form.smallCaps) }
                    }
                }
                val ink = parseHexColor(form.color.trim())?.let { (r, g, b) -> Color(r, g, b) } ?: Color.Black
                Box(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), contentAlignment = Alignment.Center) {
                    val pt = (parseFontSize(form.size) ?: 12f).coerceIn(6f, 40f)
                    var text = "AaBbYyZz"
                    if (form.caps || form.smallCaps) text = text.uppercase()
                    Text(
                        text,
                        style = TextStyle(
                            fontSize = pt.sp,
                            fontWeight = if (form.bold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (form.italic) FontStyle.Italic else FontStyle.Normal,
                            color = ink,
                            letterSpacing = (form.spacing.trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f).coerceIn(-10f, 20f).sp,
                            textDecoration = TextDecoration.combine(
                                listOfNotNull(TextDecoration.Underline.takeIf { form.underline }, TextDecoration.LineThrough.takeIf { form.strike }),
                            ),
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = sizeOk && spacingOk && colorOk, onClick = { onApply(fontChanges(start, form)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
