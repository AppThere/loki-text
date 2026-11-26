package app.loki.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/** The narrowest column the dialog lets a person make, in points (a quarter inch). */
internal const val MIN_COLUMN = 18f

/** Where the columns go, as `layout.columns`'s `apply` names it, and what the dialog calls it. */
internal val COLUMN_APPLY = listOf("section" to "This section", "forward" to "This point forward", "document" to "Whole document", "selection" to "Selected text")

/**
 * The Columns dialog's fields, lengths in points. [widths] (width, space after) is used only when
 * the columns are not [equal]; [textWidth] is the room between the section's margins.
 */
internal data class ColumnsForm(
    val count: Int = 1,
    val equal: Boolean = true,
    val space: Float = 36f,
    val widths: List<Pair<Float, Float>> = emptyList(),
    val separator: Boolean = false,
    val apply: String = "section",
    val textWidth: Float = 468f,
) {
    /** Each column's width and the space after it, as they will be laid out. */
    val laidOut: List<Pair<Float, Float>>
        get() = if (equal || count == 1 || widths.size != count) {
            val w = (textWidth - space * (count - 1)) / count
            List(count) { i -> w to if (i < count - 1) space else 0f }
        } else widths
}

private fun JsonObject.f(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }

/** From `layout.section`; [selected] is whether text is selected, which makes Selected text the place the columns go. */
internal fun columnsFormFrom(section: JsonObject, selected: Boolean): ColumnsForm {
    val c = section["columns"] as? JsonObject ?: JsonObject(emptyMap())
    val count = ((c["count"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(1, 12)
    val widths = (c["widths"] as? JsonArray).orEmpty().mapNotNull { e ->
        val pair = e as? JsonArray ?: return@mapNotNull null
        val w = (pair.getOrNull(0) as? JsonPrimitive)?.floatOrNull ?: return@mapNotNull null
        w to ((pair.getOrNull(1) as? JsonPrimitive)?.floatOrNull ?: 0f)
    }
    val tw = (section.f("pageW") ?: 612f) - (section.f("marginLeft") ?: 72f) - (section.f("marginRight") ?: 72f) - (section.f("gutter") ?: 0f)
    return ColumnsForm(
        count = count,
        equal = widths.size != count || count == 1,
        space = (c.f("space") ?: 36f).coerceIn(0f, 300f),
        widths = if (widths.size == count && count > 1) widths else emptyList(),
        separator = (c["separator"] as? JsonPrimitive)?.booleanOrNull == true,
        apply = if (selected) "selection" else "section",
        textWidth = tw.coerceAtLeast(MIN_COLUMN),
    )
}

/** [f] with [n] columns: equal ones stay equal; unequal ones are shared out afresh, keeping the first gap. */
internal fun withCount(f: ColumnsForm, n: Int): ColumnsForm {
    val count = n.coerceIn(1, 12)
    if (f.equal || count == 1) return f.copy(count = count, widths = emptyList())
    val gap = f.laidOut.firstOrNull()?.second?.takeIf { it > 0f } ?: f.space
    val w = (f.textWidth - gap * (count - 1)) / count
    return f.copy(count = count, widths = List(count) { i -> w to if (i < count - 1) gap else 0f })
}

/** [f] with equal columns turned on or off; turned off, each column starts at the width it had. */
internal fun withEqual(f: ColumnsForm, equal: Boolean): ColumnsForm =
    if (equal) f.copy(equal = true, widths = emptyList()) else f.copy(equal = false, widths = f.laidOut)

/** The presets of the ribbon's Columns menu: One, Two, Three, and two columns with the left or right one narrow. */
internal fun withPreset(f: ColumnsForm, preset: String): ColumnsForm = when (preset) {
    "left", "right" -> {
        val narrow = (f.textWidth - f.space) / 3f
        val wide = (f.textWidth - f.space) - narrow
        f.copy(count = 2, equal = false, widths = if (preset == "left") listOf(narrow to f.space, wide to 0f) else listOf(wide to f.space, narrow to 0f))
    }
    else -> f.copy(count = preset.toIntOrNull()?.coerceIn(1, 3) ?: 1, equal = true, widths = emptyList())
}

/** The preset [f] is, if it is one: `1`–`3` for equal columns, `left` or `right`. */
internal fun presetOf(f: ColumnsForm): String? {
    if (f.equal || f.count == 1) return f.count.takeIf { it <= 3 }?.toString()
    if (f.count != 2) return null
    val (a, b) = f.laidOut
    return when {
        abs(a.first * 2 - b.first) < 1f -> "left"
        abs(b.first * 2 - a.first) < 1f -> "right"
        else -> null
    }
}

/**
 * [f] with column [i]'s width set to [w]. The next column (the one before, for the last) gives or
 * takes the difference, so the columns still fill the text width.
 */
internal fun withWidth(f: ColumnsForm, i: Int, w: Float): ColumnsForm {
    val cols = f.laidOut.toMutableList()
    if (i !in cols.indices || cols.size < 2) return f
    val other = if (i < cols.size - 1) i + 1 else i - 1
    val d = w - cols[i].first
    cols[i] = w to cols[i].second
    cols[other] = (cols[other].first - d) to cols[other].second
    return f.copy(equal = false, widths = cols)
}

/** [f] with the space after column [i] set to [s]; for equal columns the one space they share. */
internal fun withSpace(f: ColumnsForm, i: Int, s: Float): ColumnsForm {
    if (f.equal || f.count == 1) return f.copy(space = s)
    val cols = f.laidOut.toMutableList()
    if (i !in 0 until cols.size - 1) return f
    val next = i + 1
    val d = s - cols[i].second
    cols[i] = cols[i].first to s
    // The column after the gap gives or takes the room.
    cols[next] = (cols[next].first - d) to cols[next].second
    return f.copy(widths = cols)
}

/** Why [f] can't be applied, or null when it can. */
internal fun columnsProblem(f: ColumnsForm): String? {
    val cols = f.laidOut
    return when {
        cols.any { it.first < MIN_COLUMN - 0.01f } -> tr("A column is narrower than a quarter inch.")
        cols.any { it.second < 0f } -> tr("A space between columns is less than zero.")
        cols.sumOf { (it.first + it.second).toDouble() } > f.textWidth + 0.5f -> tr("The columns are wider than the text.")
        else -> null
    }
}

/** The `layout.columns` params for [f]: the count and space for equal columns, else each width and space. */
internal fun columnsParams(f: ColumnsForm): JsonObject = buildJsonObject {
    val r = { x: Float -> kotlin.math.round(x * 100f) / 100f }
    if (f.equal || f.count == 1) {
        put("count", f.count)
        put("space", r(f.space))
    } else {
        put("widths", buildJsonArray { for ((w, s) in f.laidOut) add(buildJsonArray { add(JsonPrimitive(r(w))); add(JsonPrimitive(r(s))) }) })
    }
    put("separator", f.separator)
    put("apply", f.apply)
}

/**
 * Layout › Columns › More Columns: presets, the number of columns, each column's width and the
 * space after it, a line between them and where they go, with a picture of the page.
 */
@Composable
internal fun ColumnsDialog(start: ColumnsForm, selected: Boolean, onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(start) }
    var unit by remember { mutableStateOf(LengthUnit.Inches) }
    // Text as typed, by field, so a half-typed number is not rewritten under the cursor; cleared whenever the numbers change elsewhere.
    var texts by remember { mutableStateOf(mapOf<String, String>()) }
    var countText by remember { mutableStateOf<String?>(null) }
    fun set(next: ColumnsForm) { f = next; texts = emptyMap() }
    fun bad(key: String) = texts[key]?.let { parseLength(it, unit) == null } == true
    val problem = columnsProblem(f)
    val countBad = countText?.trim()?.toIntOrNull()?.let { it !in 1..12 } ?: (countText != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Columns")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Presets"), style = MaterialTheme.typography.labelLarge)
                val preset = presetOf(f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in listOf("1" to "One", "2" to "Two", "3" to "Three", "left" to "Left", "right" to "Right")) {
                        FilterChip(preset == id, { countText = null; set(withPreset(f, id)) }, label = { Text(tr(label)) })
                    }
                }
                ColumnsPreview(f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        countText ?: f.count.toString(),
                        { t -> countText = t; t.trim().toIntOrNull()?.takeIf { it in 1..12 }?.let { set(withCount(f, it)) } },
                        label = { Text(tr("Number of columns")) }, singleLine = true, isError = countBad, modifier = Modifier.width(160.dp),
                    )
                    Row(Modifier.toggleable(f.separator, role = Role.Checkbox) { f = f.copy(separator = it) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.separator, null)
                        Text(tr("Line between"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (u in LengthUnit.entries) FilterChip(unit == u, { unit = u; texts = emptyMap() }, label = { Text(u.label) })
                }
                Text(tr("Width and spacing") + " (${unit.label})", style = MaterialTheme.typography.labelLarge)
                val cols = f.laidOut
                for ((i, c) in cols.withIndex()) {
                    // Equal columns show one row: their width, worked out, and the space they share.
                    if (f.equal && i > 0) break
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (f.equal && f.count > 1) tr("All") else "${i + 1}", Modifier.width(32.dp))
                        OutlinedTextField(
                            texts["w$i"] ?: lengthText(c.first, unit),
                            { t -> texts = mapOf("w$i" to t); parseLength(t, unit)?.let { w -> f = withWidth(f, i, w) } },
                            label = { Text(tr("Width")) }, singleLine = true, isError = bad("w$i"),
                            enabled = !f.equal && f.count > 1, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            texts["s$i"] ?: lengthText(if (f.equal) f.space else c.second, unit),
                            { t -> texts = mapOf("s$i" to t); parseLength(t, unit)?.let { s -> f = withSpace(f, i, s) } },
                            label = { Text(tr("Spacing")) }, singleLine = true, isError = bad("s$i"),
                            enabled = f.count > 1 && (f.equal || i < cols.size - 1), modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.toggleable(f.equal, enabled = f.count > 1, role = Role.Checkbox) { set(withEqual(f, it)) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.equal, null, enabled = f.count > 1)
                    Text(tr("Equal column width"))
                }
                Text(tr("Apply to"), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in COLUMN_APPLY) if (id != "selection" || selected) FilterChip(f.apply == id, { f = f.copy(apply = id) }, label = { Text(tr(label)) })
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null && !countBad && texts.keys.none { bad(it) }, onClick = { onApply(columnsParams(f)) }) { Text(tr("OK")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** The page with its columns as blocks of text, and the line between them if there is one. */
@Composable
private fun ColumnsPreview(f: ColumnsForm) {
    val paper = MaterialTheme.colorScheme.surfaceVariant
    val text = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val name = trf("Preview: {n} columns", "n" to f.count) + if (f.separator) ", " + tr("with a line between") else ""
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = name }) {
        val pageW = size.height * 0.77f
        val left = (size.width - pageW) / 2f
        drawRect(paper, Offset(left, 0f), Size(pageW, size.height))
        drawRect(ink, Offset(left, 0f), Size(pageW, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        val pad = pageW * 0.12f
        val scale = (pageW - 2 * pad) / f.textWidth.coerceAtLeast(1f)
        var x = left + pad
        val rows = 8
        val pitch = (size.height - 2 * pad) / rows
        for ((i, c) in f.laidOut.withIndex()) {
            val w = (c.first * scale).coerceAtLeast(1f)
            for (r in 0 until rows) drawRect(text, Offset(x, pad + r * pitch), Size(w, pitch * 0.45f))
            x += w
            if (f.separator && i < f.count - 1) {
                val mid = x + c.second * scale / 2f
                drawLine(ink, Offset(mid, pad), Offset(mid, size.height - pad), 1f)
            }
            x += c.second * scale
        }
    }
}
