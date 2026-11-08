package app.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt

/** A measurement unit for the dialog's lengths; the engine always works in points. */
internal enum class LengthUnit(val label: String, val pt: Float) {
    Inches("in", 72f), Centimetres("cm", 72f / 2.54f), Points("pt", 1f)
}

/** Standard paper sizes, portrait, in points (the engine's own table). */
internal val PAPER_SIZES = listOf(
    Triple("Letter", 612f, 792f), Triple("Legal", 612f, 1008f), Triple("Executive", 522f, 756f),
    Triple("A3", 841.89f, 1190.55f), Triple("A4", 595.28f, 841.89f), Triple("A5", 419.53f, 595.28f),
    Triple("B4 (JIS)", 728.5f, 1031.8f), Triple("B5 (JIS)", 515.9f, 728.5f), Triple("Tabloid", 792f, 1224f),
    Triple("Statement", 396f, 612f), Triple("Envelope #10", 297f, 684f), Triple("Envelope DL", 311.8f, 623.6f),
)

/** The standard size these dimensions match in either orientation (within half a point), or null for a custom one. */
internal fun paperName(w: Float, h: Float): String? = PAPER_SIZES.firstOrNull { (_, pw, ph) ->
    (abs(pw - w) < 0.5f && abs(ph - h) < 0.5f) || (abs(pw - h) < 0.5f && abs(ph - w) < 0.5f)
}?.first

internal val VERTICAL_ALIGNS = listOf("top" to "Top", "center" to "Center", "justify" to "Justified", "bottom" to "Bottom")

/** The section as the dialog shows it: lengths in points, so conversion to the shown unit stays in one place. */
internal data class PageForm(
    val width: Float = 612f,
    val height: Float = 792f,
    val landscape: Boolean = false,
    val top: Float = 72f,
    val bottom: Float = 72f,
    val left: Float = 72f,
    val right: Float = 72f,
    val gutter: Float = 0f,
    val valign: String = "top",
)

private fun JsonObject.pt(key: String, fallback: Float) = (this[key] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() } ?: fallback

/** From `layout.section`. */
internal fun pageFormFrom(section: JsonObject): PageForm = PageForm(
    width = section.pt("pageW", 612f),
    height = section.pt("pageH", 792f),
    landscape = (section["landscape"] as? JsonPrimitive)?.booleanOrNull == true,
    top = section.pt("marginTop", 72f),
    bottom = section.pt("marginBottom", 72f),
    left = section.pt("marginLeft", 72f),
    right = section.pt("marginRight", 72f),
    gutter = section.pt("gutter", 0f),
    valign = (section["valign"] as? JsonPrimitive)?.contentOrNull ?: "top",
)

/** A length in [unit] as it is typed: at most two decimals, no trailing zeros. */
internal fun lengthText(pt: Float, unit: LengthUnit): String {
    val v = (pt / unit.pt * 100).roundToInt() / 100f
    return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}

/** Points from text typed in [unit]; null when it isn't a finite, non-negative number. */
internal fun parseLength(text: String, unit: LengthUnit): Float? =
    text.trim().toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f && it <= 100_000f }?.times(unit.pt)

/** The `layout.pageLayout` params for what changed; null when nothing did. */
internal fun pageChanges(start: PageForm, now: PageForm): JsonObject? {
    val o = buildJsonObject {
        if (abs(start.width - now.width) >= 0.01f || abs(start.height - now.height) >= 0.01f) { put("width", now.width); put("height", now.height) }
        if (start.landscape != now.landscape) put("landscape", now.landscape)
        fun m(key: String, a: Float, b: Float) { if (abs(a - b) >= 0.01f) put(key, b) }
        m("top", start.top, now.top); m("bottom", start.bottom, now.bottom)
        m("left", start.left, now.left); m("right", start.right, now.right); m("gutter", start.gutter, now.gutter)
        if (start.valign != now.valign) put("valign", now.valign)
    }
    return o.takeIf { it.isNotEmpty() }
}

/** Page Setup: paper size, orientation, margins and vertical alignment, in inches, centimetres or points. */
@Composable
internal fun PageSetupDialog(start: PageForm, onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var unit by remember { mutableStateOf(LengthUnit.Inches) }
    var form by remember { mutableStateOf(start) }
    // The fields hold text so a half-typed number is not rewritten under the cursor.
    var texts by remember { mutableStateOf(mapOf<String, String>()) }
    fun shown(key: String, pt: Float) = texts[key] ?: lengthText(pt, unit)
    fun edit(key: String, text: String, set: (PageForm, Float) -> PageForm) {
        texts = texts + (key to text)
        parseLength(text, unit)?.let { form = set(form, it) }
    }
    fun bad(key: String) = texts[key]?.let { parseLength(it, unit) == null } == true
    // The page's wide side is its width in landscape.
    fun turn(landscape: Boolean, w: Float, h: Float) = if (landscape) maxOf(w, h) to minOf(w, h) else minOf(w, h) to maxOf(w, h)
    val name = paperName(form.width, form.height)
    val room = form.left + form.right + form.gutter <= form.width - 36f && form.top + form.bottom <= form.height - 36f
    val invalid = listOf("w", "h", "t", "b", "l", "r", "g").any { bad(it) } || !room

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Page Setup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (u in LengthUnit.entries) FilterChip(unit == u, { unit = u; texts = emptyMap() }, label = { Text(u.label) })
                }
                Text("Paper", style = MaterialTheme.typography.labelLarge)
                // A wrapping set of chips for the named sizes.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in PAPER_SIZES.chunked(4)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((n, w, h) in row) FilterChip(name == n, {
                            val (pw, ph) = turn(form.landscape, w, h)
                            form = form.copy(width = pw, height = ph); texts = emptyMap()
                        }, label = { Text(n) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(shown("w", form.width), { edit("w", it) { f, v -> f.copy(width = v) } }, label = { Text("Width (${unit.label})") }, singleLine = true, isError = bad("w"), modifier = Modifier.weight(1f))
                    OutlinedTextField(shown("h", form.height), { edit("h", it) { f, v -> f.copy(height = v) } }, label = { Text("Height (${unit.label})") }, singleLine = true, isError = bad("h"), modifier = Modifier.weight(1f))
                }
                Text("Orientation", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((land, label) in listOf(false to "Portrait", true to "Landscape")) FilterChip(form.landscape == land, {
                        if (form.landscape != land) {
                            val (pw, ph) = turn(land, form.width, form.height)
                            form = form.copy(landscape = land, width = pw, height = ph); texts = emptyMap()
                        }
                    }, label = { Text(label) })
                }
                Text("Margins (${unit.label})", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(shown("t", form.top), { edit("t", it) { f, v -> f.copy(top = v) } }, label = { Text("Top") }, singleLine = true, isError = bad("t"), modifier = Modifier.weight(1f))
                    OutlinedTextField(shown("b", form.bottom), { edit("b", it) { f, v -> f.copy(bottom = v) } }, label = { Text("Bottom") }, singleLine = true, isError = bad("b"), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(shown("l", form.left), { edit("l", it) { f, v -> f.copy(left = v) } }, label = { Text("Left") }, singleLine = true, isError = bad("l"), modifier = Modifier.weight(1f))
                    OutlinedTextField(shown("r", form.right), { edit("r", it) { f, v -> f.copy(right = v) } }, label = { Text("Right") }, singleLine = true, isError = bad("r"), modifier = Modifier.weight(1f))
                    OutlinedTextField(shown("g", form.gutter), { edit("g", it) { f, v -> f.copy(gutter = v) } }, label = { Text("Gutter") }, singleLine = true, isError = bad("g"), modifier = Modifier.weight(1f))
                }
                if (!room) Text("The margins leave no room for text on this page.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                Text("Vertical alignment", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in VERTICAL_ALIGNS) FilterChip(form.valign == id, { form = form.copy(valign = id) }, label = { Text(label) })
                }
            }
        },
        confirmButton = { TextButton(enabled = !invalid, onClick = { onApply(pageChanges(start, form) ?: JsonObject(emptyMap())) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
