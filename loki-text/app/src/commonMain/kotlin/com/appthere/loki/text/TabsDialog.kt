package com.appthere.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal val TAB_ALIGNS = listOf("left" to "Left", "center" to "Center", "right" to "Right", "decimal" to "Decimal", "bar" to "Bar")
internal val TAB_LEADERS = listOf("none" to "None", "dot" to "Dots", "hyphen" to "Dashes", "underscore" to "Line", "middleDot" to "Middle dots")

/** Stops this close together are one stop, as the engine counts them. */
private const val SAME_POS = 0.05f

/** The most stops the engine keeps on a paragraph, and the farthest one may stand from the text's edge, in points. */
private const val MAX_STOPS = 64
private const val MAX_POS = 1584f

/** The Tabs dialog's fields: the stops in force on the paragraph, and the document's default tab interval in points. */
internal data class TabsForm(val stops: List<TabMark> = emptyList(), val defaultTab: Float = 36f)

/** From `loki.paraLines`. */
internal fun tabsFormFrom(state: JsonObject): TabsForm = TabsForm(
    stops = parseTabMarks(state["tabs"]),
    defaultTab = (state["defaultTab"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it > 0f } ?: 36f,
)

private fun same(a: Float, b: Float) = abs(a - b) < SAME_POS

/** [f] with [stop], which takes the place of one already at its position. */
internal fun withStop(f: TabsForm, stop: TabMark): TabsForm = f.copy(stops = (f.stops.filterNot { same(it.pos, stop.pos) } + stop).sortedBy { it.pos })

internal fun withoutStop(f: TabsForm, pos: Float): TabsForm = f.copy(stops = f.stops.filterNot { same(it.pos, pos) })

/**
 * The `para.tabs` params for what changed between [start] and [now]; null when nothing did. Only the changes are
 * sent, so other selected paragraphs keep stops of their own; taking every stop away clears the inherited ones too.
 */
internal fun tabsParams(start: TabsForm, now: TabsForm): JsonObject? {
    val out = buildJsonObject {
        if (now.stops.isEmpty() && start.stops.isNotEmpty()) put("clearAll", true) else {
            val gone = start.stops.filter { s -> now.stops.none { same(it.pos, s.pos) } }
            if (gone.isNotEmpty()) putJsonArray("clear") { gone.forEach { add(JsonPrimitive(it.pos)) } }
            val set = now.stops.filter { it !in start.stops }
            if (set.isNotEmpty()) putJsonArray("set") {
                set.forEach { t -> add(buildJsonObject { put("pos", t.pos); put("align", t.align); put("leader", t.leader) }) }
            }
        }
        if (abs(now.defaultTab - start.defaultTab) >= 0.001f) put("default", now.defaultTab)
    }
    return out.takeIf { it.isNotEmpty() }
}

/**
 * Paragraph › Tabs: the paragraph's stops to pick from, a position with an alignment and a leader to set or clear,
 * and the default interval. Nothing reaches the document until OK.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TabsDialog(start: TabsForm, onApply: (JsonObject?) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(start) }
    var unit by remember { mutableStateOf(LengthUnit.Inches) }
    // The boxes hold text so a half-typed number is not rewritten under the cursor; null follows the value.
    var position by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf<String?>(null) }
    var align by remember { mutableStateOf("left") }
    var leader by remember { mutableStateOf("none") }
    val at = parseLength(position, unit)?.takeIf { it <= MAX_POS }
    val picked = at?.let { p -> f.stops.firstOrNull { same(it.pos, p) } }
    val intervalBad = interval?.let { t -> parseLength(t, unit)?.takeIf { it >= 1f && it <= MAX_POS } == null } == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Tabs")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (u in LengthUnit.entries) FilterChip(unit == u, { unit = u; interval = null; position = at?.let { lengthText(it, u) }.orEmpty() }, label = { Text(u.label) })
                }
                Text(tr("Tab stops"), style = MaterialTheme.typography.labelLarge)
                if (f.stops.isEmpty()) Text(tr("None"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                for (stop in f.stops) {
                    val kind = tr(TAB_ALIGNS.firstOrNull { it.first == stop.align }?.second ?: stop.align)
                    val lead = TAB_LEADERS.firstOrNull { it.first == stop.leader && it.first != "none" }?.second
                    Text(
                        listOfNotNull("${lengthText(stop.pos, unit)} ${unit.label}", kind, lead?.let { tr(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (stop == picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().selectable(stop == picked, role = Role.RadioButton) {
                            position = lengthText(stop.pos, unit); align = stop.align; leader = stop.leader
                        },
                    )
                }
                OutlinedTextField(
                    position, { position = it },
                    label = { Text(tr("Tab stop position") + " (${unit.label})") }, singleLine = true,
                    isError = position.isNotBlank() && at == null, modifier = Modifier.fillMaxWidth(),
                )
                Text(tr("Alignment"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in TAB_ALIGNS) FilterChip(align == id, { align = id }, label = { Text(tr(label)) })
                }
                Text(tr("Leader"), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in TAB_LEADERS) FilterChip(leader == id, { leader = id }, label = { Text(tr(label)) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    // The picked stop keeps its own position, which the box shows rounded.
                    TextButton(
                        { at?.let { p -> f = withStop(f, TabMark(picked?.pos ?: p, align, leader)) } },
                        enabled = at != null && (picked != null || f.stops.size < MAX_STOPS),
                    ) { Text(tr("Set")) }
                    TextButton({ picked?.let { f = withoutStop(f, it.pos); position = "" } }, enabled = picked != null) { Text(tr("Clear")) }
                    TextButton({ f = f.copy(stops = emptyList()); position = "" }, enabled = f.stops.isNotEmpty()) { Text(tr("Clear All")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        interval ?: lengthText(f.defaultTab, unit),
                        { t -> interval = t; parseLength(t, unit)?.takeIf { it >= 1f && it <= MAX_POS }?.let { f = f.copy(defaultTab = it) } },
                        label = { Text(tr("Default tab stops") + " (${unit.label})") }, singleLine = true, isError = intervalBad, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = !intervalBad, onClick = { onApply(tabsParams(start, f)) }) { Text(tr("OK")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
