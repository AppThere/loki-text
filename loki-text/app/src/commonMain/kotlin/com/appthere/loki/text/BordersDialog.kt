package com.appthere.loki.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal val BORDER_SIDES = listOf("top" to "Top", "bottom" to "Bottom", "left" to "Left", "right" to "Right", "between" to "Between paragraphs")
private val BOX_SIDES = setOf("top", "bottom", "left", "right")
private val BORDER_WIDTHS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2.25f, 3f, 4.5f, 6f)
private val LINE_STYLES = BORDER_STYLES + listOf("Dot dash" to "dotDash", "Wave" to "wave")

/** One side's line: its style, width in points and colour (`RRGGBB`, blank for automatic). */
internal data class BorderLine(val style: String = "single", val width: Float = 0.5f, val color: String = "")

/**
 * The Borders and Shading dialog's fields: the [sides] drawn, the line chosen ([style], [width], [color]), the
 * paragraph's [shading] (`RRGGBB`, blank for none), and the lines its sides had when it opened ([had]).
 */
internal data class BordersForm(
    val sides: Set<String> = emptySet(),
    val style: String = "single",
    val width: Float = 0.5f,
    val color: String = "",
    val shading: String = "",
    val had: Map<String, BorderLine> = emptyMap(),
) {
    val line get() = BorderLine(style, width, color)
}

/** One command a dialog runs. */
internal class DialogStep(val command: String, val params: JsonObject)

/** [steps] as `loki.runSteps` takes them. */
internal fun stepsParams(steps: List<DialogStep>): JsonObject = buildJsonObject {
    putJsonArray("steps") { for (s in steps) add(buildJsonObject { put("command", s.command); put("params", s.params) }) }
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** From `loki.paraLines`. The line shown is the first side's. */
internal fun bordersFormFrom(state: JsonObject): BordersForm {
    val borders = state["borders"] as? JsonObject
    val had = LinkedHashMap<String, BorderLine>()
    for ((side, _) in BORDER_SIDES) {
        val b = borders?.get(side) as? JsonObject ?: continue
        had[side] = BorderLine(
            b.text("style") ?: "single",
            (b["width"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it > 0f } ?: 0.5f,
            b.text("color").orEmpty(),
        )
    }
    val first = had.values.firstOrNull() ?: BorderLine()
    return BordersForm(had.keys.toSet(), first.style, first.width, first.color, state.text("shading").orEmpty(), had)
}

/** [text] as the engine takes a colour, without a `#`; null when it isn't six hex digits. */
private fun hexColor(text: String): String? =
    text.trim().removePrefix("#").uppercase().takeIf { t -> t.length == 6 && t.all { it in "0123456789ABCDEF" } }

/** What is wrong with the colours typed in [f], or null. */
internal fun bordersProblem(f: BordersForm): String? =
    if (listOf(f.color, f.shading).any { it.isNotBlank() && hexColor(it) == null }) tr("A colour is six hex digits, like 1F4E79.") else null

/**
 * The commands for what changed between [start] and [now]: `para.borders` with the sides drawn, and `para.shading`.
 * While the line is left as it was, a side that was already drawn keeps its own, even if the sides differ; choosing
 * another line gives it to all of them.
 */
internal fun bordersSteps(start: BordersForm, now: BordersForm): List<DialogStep> = buildList {
    if (now.sides != start.sides || now.line != start.line) {
        add(DialogStep("para.borders", buildJsonObject {
            putJsonObject("sides") {
                for ((side, _) in BORDER_SIDES) {
                    if (side !in now.sides) continue
                    val line = start.had[side]?.takeIf { now.line == start.line } ?: now.line
                    putJsonObject(side) {
                        put("style", line.style)
                        put("width", line.width)
                        hexColor(line.color)?.let { put("color", it) }
                    }
                }
            }
        }))
    }
    if (now.shading != start.shading) {
        add(DialogStep("para.shading", buildJsonObject {
            val fill = hexColor(now.shading)
            if (fill == null) put("color", JsonNull) else put("color", fill)
            // The paragraphs, also when the selection is some text inside one.
            put("paragraph", true)
        }))
    }
}

/** Paragraph › Borders and Shading: which sides have a line, what the line is, and the paragraph's fill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BordersDialog(start: BordersForm, onApply: (List<DialogStep>) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(start) }
    val problem = bordersProblem(f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Borders and Shading")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Setting"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, sides) in listOf("None" to emptySet(), "Box" to BOX_SIDES, "All" to BOX_SIDES + "between")) {
                        FilterChip(f.sides == sides, { f = f.copy(sides = sides) }, label = { Text(tr(label)) })
                    }
                }
                Text(tr("Sides"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((side, label) in BORDER_SIDES) {
                        FilterChip(side in f.sides, { f = f.copy(sides = if (side in f.sides) f.sides - side else f.sides + side) }, label = { Text(tr(label)) })
                    }
                }
                BordersPreview(f)
                Text(tr("Style"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, id) in LINE_STYLES) FilterChip(f.style == id, { f = f.copy(style = id) }, label = { Text(tr(label)) })
                }
                Text(tr("Width") + " (pt)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (w in BORDER_WIDTHS) FilterChip(f.width == w, { f = f.copy(width = w) }, label = { Text(w.toString().removeSuffix(".0")) })
                }
                ColorChoice(tr("Line color (blank: automatic)"), f.color, FONT_COLORS) { f = f.copy(color = it) }
                ColorChoice(tr("Shading (blank: none)"), f.shading, SHADINGS) { f = f.copy(shading = it) }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
            }
        },
        confirmButton = { TextButton(enabled = problem == null, onClick = { onApply(bordersSteps(start, f)) }) { Text(tr("OK")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** A colour to type as `RRGGBB`, or to pick from [swatches]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoice(label: String, value: String, swatches: List<Swatch>, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, isError = value.isNotBlank() && hexColor(value) == null, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (swatch in swatches) {
            val on = value.equals(swatch.param, ignoreCase = true)
            val name = tr(swatch.name)
            Box(
                Modifier.size(28.dp).background(swatch.color, RoundedCornerShape(4.dp))
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .semantics { contentDescription = name }
                    .selectable(on, role = Role.RadioButton) { onChange(if (on) "" else swatch.param) },
            )
        }
    }
}

/** A sketch of two paragraphs in a box: the fill behind them and a line on each side that is drawn. */
@Composable
private fun BordersPreview(f: BordersForm) {
    val ink = hexColor(f.color)?.let { Color(0xFF000000.toInt() or it.toInt(16)) } ?: MaterialTheme.colorScheme.onSurface
    val text = MaterialTheme.colorScheme.outline
    val fill = hexColor(f.shading)?.let { Color(0xFF000000.toInt() or it.toInt(16)) }
    val drawn = BORDER_SIDES.filter { it.first in f.sides }.joinToString { tr(it.second) }
    val name = if (drawn.isEmpty()) tr("Preview: no borders") else trf("Preview: borders on {sides}", "sides" to drawn)
    Canvas(Modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = name }) {
        val pad = 8.dp.toPx()
        val area = Size(size.width - 2 * pad, size.height - 2 * pad)
        fill?.let { drawRect(it, Offset(pad, pad), area) }
        for (row in 0 until 4) drawRect(text, Offset(2 * pad, pad + area.height * (row * 2 + 1) / 8 - 2f), Size(area.width - 2 * pad, 4f))
        val width = f.width.coerceIn(0.5f, 6f).dp.toPx()
        fun line(side: String, from: Offset, to: Offset) { if (side in f.sides) drawLine(ink, from, to, width) }
        val right = pad + area.width
        val bottom = pad + area.height
        line("top", Offset(pad, pad), Offset(right, pad))
        line("bottom", Offset(pad, bottom), Offset(right, bottom))
        line("left", Offset(pad, pad), Offset(pad, bottom))
        line("right", Offset(right, pad), Offset(right, bottom))
        line("between", Offset(pad, pad + area.height / 2), Offset(right, pad + area.height / 2))
    }
}
