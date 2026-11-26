@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.loki.kit.FORM_COLORS
import app.loki.text.engine.ObjectFrame
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One item of a SmartArt graphic's outline: its text, how far in (0–7), and its own colour if it has one. */
data class SmartArtItem(val text: String, val level: Int = 0, val color: String? = null)

/** The deepest an item goes. */
private const val MAX_LEVEL = 7

/** The selected SmartArt's items, as `loki.commandState` reports them; null when no SmartArt is selected. */
internal fun parseSmartArt(e: JsonElement?): List<SmartArtItem>? {
    val o = e as? JsonObject ?: return null
    return (o["items"] as? JsonArray).orEmpty().mapNotNull { i ->
        val io = i as? JsonObject ?: return@mapNotNull null
        SmartArtItem(
            (io["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            ((io["level"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, MAX_LEVEL),
            (io["color"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
        )
    }
}

/** `smartArt.items` for [items]: the whole outline in one step. */
internal fun smartArtItemsParams(items: List<SmartArtItem>): JsonObject = buildJsonObject {
    put("items", buildJsonArray {
        for (i in items) add(buildJsonObject {
            put("text", i.text)
            if (i.level > 0) put("level", i.level)
            i.color?.let { put("color", it) }
        })
    })
}

/** The outline with item [at] one level further in: no further than one below the item before it (the first stays at the top). */
internal fun demoteItem(items: List<SmartArtItem>, at: Int): List<SmartArtItem> {
    if (at <= 0 || at >= items.size) return items
    val most = (items[at - 1].level + 1).coerceAtMost(MAX_LEVEL)
    return items.mapIndexed { i, it -> if (i == at) it.copy(level = (it.level + 1).coerceAtMost(most)) else it }
}

/** The outline with item [at] one level further out, and the items under it with it so they stay under it. */
internal fun promoteItem(items: List<SmartArtItem>, at: Int): List<SmartArtItem> {
    val item = items.getOrNull(at) ?: return items
    if (item.level == 0) return items
    var end = at + 1
    while (end < items.size && items[end].level > item.level) end++
    return items.mapIndexed { i, it -> if (i in at until end) it.copy(level = (it.level - 1).coerceAtLeast(0)) else it }
}

/** The outline with a new empty item after [at], at its level. */
internal fun addItemAfter(items: List<SmartArtItem>, at: Int): List<SmartArtItem> {
    val level = items.getOrNull(at)?.level ?: 0
    val i = (at + 1).coerceIn(0, items.size)
    return items.take(i) + SmartArtItem("", level) + items.drop(i)
}

/** The outline without item [at]; the items under it come out a level so none is left more than one below its parent. */
internal fun removeItem(items: List<SmartArtItem>, at: Int): List<SmartArtItem> {
    if (at !in items.indices || items.size <= 1) return items
    val out = items.toMutableList().apply { removeAt(at) }
    for (i in out.indices) {
        val most = if (i == 0) 0 else out[i - 1].level + 1
        if (out[i].level > most) out[i] = out[i].copy(level = most)
    }
    return out
}

/**
 * SmartArt Design › Text Pane: the graphic's outline, an item to a row, indented by level. Text is written as it is typed
 * and sent when the box is left; the buttons move an item in or out, add one after it, or take it away. Every change
 * is one `smartArt.items`.
 */
@Composable
internal fun SmartArtTextPane(items: List<SmartArtItem>?, run: suspend (String, JsonObject) -> Result<JsonElement?>, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    // The outline as typed; it starts again from the document's whenever that changes.
    var draft by remember(items) { mutableStateOf(items.orEmpty()) }
    fun send(next: List<SmartArtItem>) {
        draft = next
        scope.launch { error = run("smartArt.items", smartArtItemsParams(next)).exceptionOrNull()?.message.orEmpty() }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (items == null) {
            Text("Select a SmartArt graphic to type its text here.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        draft.forEachIndexed { i, item ->
            // The text across the pane, indented by level; its buttons under it, so a narrow pane still shows the words.
            Column(Modifier.padding(start = (item.level * 16).dp)) {
                OutlinedTextField(
                    item.text, { t -> draft = draft.mapIndexed { j, it -> if (j == i) it.copy(text = t) else it } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Item ${i + 1}, level ${item.level + 1}" }
                        .onFocusChanged { if (!it.isFocused && draft != items) send(draft) },
                )
                FlowRow {
                    TextButton({ send(promoteItem(draft, i)) }, enabled = item.level > 0) { Text(tr("Out")) }
                    TextButton({ send(demoteItem(draft, i)) }, enabled = i > 0) { Text(tr("In")) }
                    TextButton({ send(addItemAfter(draft, i)) }) { Text(app.loki.kit.kt("Add")) }
                    TextButton({ send(removeItem(draft, i)) }, enabled = draft.size > 1) { Text(app.loki.kit.kt("Remove")) }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

/** Line widths the Format Shape pane offers, in points. */
private val LINE_WIDTHS = listOf(0.5f, 1f, 2.25f, 4.5f)

/**
 * Shape Format › Format Shape: the selected shape's fill and line (a colour, or none; the line's width) and its size and
 * place on the page. Each choice is one command: `shape.fill`, `shape.outline` or `arrange.bounds`.
 */
@Composable
internal fun FormatShapePane(obj: ObjectFrame?, run: suspend (String, JsonObject) -> Result<JsonElement?>, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    fun go(cmd: String, p: JsonObject) { scope.launch { error = run(cmd, p).exceptionOrNull()?.message.orEmpty() } }
    var lineColor by remember { mutableStateOf<String?>("000000") }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (obj == null || obj.kind == "image") {
            Text("Select a shape or text box to format it here.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text("Fill", style = MaterialTheme.typography.titleSmall)
        ColorRow(none = "No fill") { hex -> go("shape.fill", buildJsonObject { if (hex == null) put("color", JsonNull) else put("color", hex) }) }
        HorizontalDivider()
        Text("Line", style = MaterialTheme.typography.titleSmall)
        ColorRow(none = "No line") { hex -> lineColor = hex; go("shape.outline", buildJsonObject { if (hex == null) put("color", JsonNull) else put("color", hex) }) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in LINE_WIDTHS) FilterChip(false, {
                go("shape.outline", buildJsonObject { put("color", lineColor ?: "000000"); put("width", w) })
            }, label = { Text("$w pt") })
        }
        HorizontalDivider()
        Text("Size and position", style = MaterialTheme.typography.titleSmall)
        // Inches, as the rulers show them; sent in points.
        @Composable fun inch(label: String, pt: Float, set: (Float) -> JsonObject) {
            var text by remember(pt) { mutableStateOf(formatInches(pt)) }
            OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text("$label (in)") },
                modifier = Modifier.width(140.dp).onFocusChanged {
                    if (!it.isFocused) text.trim().removeSuffix("\"").toFloatOrNull()?.takeIf { v -> v.isFinite() && v >= 0f }?.let { v ->
                        if (formatInches(v * 72f) != formatInches(pt)) go("arrange.bounds", set(v * 72f))
                    }
                },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            inch("Width", obj.w) { v -> buildJsonObject { put("width", v) } }
            inch("Height", obj.h) { v -> buildJsonObject { put("height", v) } }
            if (obj.floating) {
                inch("Left", obj.x) { v -> buildJsonObject { put("page", obj.page); put("x", v); put("y", obj.y) } }
                inch("Top", obj.y) { v -> buildJsonObject { put("page", obj.page); put("x", obj.x); put("y", v) } }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

/** [pt] points as inches, to two places, without trailing zeros. */
internal fun formatInches(pt: Float): String {
    val v = kotlin.math.round(pt / 72f * 100f) / 100f
    return if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
}

@Composable
private fun ColorRow(none: String, pick: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(false, { pick(null) }, label = { Text(none) })
        for (hex in FORM_COLORS) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = app.loki.kit.formColor(hex) ?: androidx.compose.ui.graphics.Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                onClick = { pick(hex) },
                modifier = Modifier.padding(2.dp).width(28.dp).semantics { contentDescription = "Colour $hex" },
            ) { Text(" ", Modifier.padding(4.dp)) }
        }
    }
}
