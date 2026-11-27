package com.appthere.loki.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal val DROP_CAP_POSITIONS = listOf("none" to "None", "dropped" to "Dropped", "margin" to "In margin")

/** The Drop Cap dialog's fields. [font] is the letter's font; [textFont] the paragraph's, which the letter has when it has none of its own. */
internal data class DropCapForm(
    val position: String = "none",
    val font: String = "",
    val textFont: String = "",
    val lines: String = "3",
    /** Points. */
    val distance: Float = 0f,
)

/** From `loki.dropCap`. */
internal fun dropCapFormFrom(state: JsonObject): DropCapForm {
    fun s(k: String) = (state[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val text = s("textFont").orEmpty()
    return DropCapForm(
        position = s("position")?.takeIf { p -> DROP_CAP_POSITIONS.any { it.first == p } } ?: "none",
        font = s("font") ?: text,
        textFont = text,
        lines = ((state["lines"] as? JsonPrimitive)?.intOrNull ?: 3).coerceIn(1, 10).toString(),
        distance = (state["distance"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }?.coerceIn(0f, 1584f) ?: 0f,
    )
}

/** Lines to drop as typed, or null when it isn't a whole number from 1 to 10. */
internal fun dropLines(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..10 }

/**
 * The `insert.dropCap` params for [f]; null while the lines are not a number from 1 to 10. A font
 * that is the paragraph's own, or blank, is left out so the letter follows the text's font.
 */
internal fun dropCapParams(f: DropCapForm): JsonObject? {
    if (f.position == "none") return buildJsonObject { put("position", "none") }
    val lines = dropLines(f.lines) ?: return null
    return buildJsonObject {
        put("position", f.position)
        put("lines", lines)
        put("distance", f.distance.coerceIn(0f, 1584f))
        f.font.trim().takeIf { it.isNotEmpty() && it != f.textFont }?.let { put("font", it) }
    }
}

/** Insert › Drop Cap › Drop Cap Options: where the big first letter goes, its font, how many lines it drops and its distance from the text. */
@Composable
internal fun DropCapDialog(start: DropCapForm, fonts: List<String>, onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(start) }
    var unit by remember { mutableStateOf(LengthUnit.Points) }
    var distanceText by remember { mutableStateOf<String?>(null) }
    val on = f.position != "none"
    val distanceBad = distanceText?.let { t -> parseLength(t, unit)?.let { it > 1584f } ?: true } == true
    val params = dropCapParams(f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Drop Cap")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Position"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in DROP_CAP_POSITIONS) FilterChip(f.position == id, { f = f.copy(position = id) }, label = { Text(tr(label)) })
                }
                DropCapPreview(f.position, dropLines(f.lines) ?: 3)
                FontBox(tr("Font"), f.font, fonts, enabled = on) { f = f.copy(font = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        f.lines, { f = f.copy(lines = it) }, label = { Text(tr("Lines to drop")) }, singleLine = true, enabled = on,
                        isError = on && dropLines(f.lines) == null, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        distanceText ?: lengthText(f.distance, unit),
                        { t -> distanceText = t; parseLength(t, unit)?.takeIf { it <= 1584f }?.let { f = f.copy(distance = it) } },
                        label = { Text(tr("Distance from text") + " (${unit.label})") }, singleLine = true, enabled = on,
                        isError = on && distanceBad, modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (u in LengthUnit.entries) FilterChip(unit == u, { unit = u; distanceText = null }, label = { Text(u.label) }, enabled = on)
                }
            }
        },
        confirmButton = { TextButton(enabled = params != null && !(on && distanceBad), onClick = { params?.let(onApply) }) { Text(tr("OK")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** A sketch of the paragraph: grey lines of text, and the letter as a block [lines] lines tall, dropped into the text or standing in the margin. */
@Composable
private fun DropCapPreview(position: String, lines: Int) {
    val ink = MaterialTheme.colorScheme.onSurface
    val text = MaterialTheme.colorScheme.outline
    val name = when (position) {
        "dropped" -> trf("Preview: the first letter dropped {n} lines into the text", "n" to lines)
        "margin" -> trf("Preview: the first letter {n} lines tall in the margin", "n" to lines)
        else -> tr("Preview: no drop cap")
    }
    Canvas(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = name }) {
        val rows = 7
        val pitch = size.height / rows
        val margin = size.width * 0.18f
        val capW = pitch * lines * 0.8f
        for (r in 0 until rows) {
            val y = r * pitch + pitch * 0.35f
            val indent = if (position == "dropped" && r < lines) capW + pitch * 0.3f else 0f
            drawRect(text, Offset(margin + indent, y), Size(size.width - margin - indent - 4f, pitch * 0.3f))
        }
        if (position != "none") {
            val x = if (position == "margin") margin - capW - pitch * 0.3f else margin
            drawRect(ink, Offset(x, pitch * 0.15f), Size(capW, pitch * lines - pitch * 0.3f))
        }
    }
}
