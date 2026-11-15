package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

/** A check box that can also be "mixed" (null): the selected paragraphs differ. */
internal typealias Flag = Boolean?

/**
 * The Paragraph dialog's fields. A number is text, blank while the selected paragraphs differ; a
 * [Flag] or [align] is null for the same reason. Lengths are in points.
 */
internal data class ParaForm(
    val align: String? = "left",
    val left: String = "0",
    val right: String = "0",
    val first: String = "0",
    val before: String = "0",
    val after: String = "0",
    val line: String = "1",
    val keepNext: Flag = false,
    val keepLines: Flag = false,
    val pageBreakBefore: Flag = false,
    val widowControl: Flag = true,
    val contextualSpacing: Flag = false,
)

internal val ALIGNMENTS = listOf("left" to "Left", "center" to "Centered", "right" to "Right", "justify" to "Justified")

private fun JsonElement?.number(): String = sizeText((this as? JsonPrimitive)?.floatOrNull).let { if (this == null || this is JsonNull) "" else it }

private fun JsonElement?.flag(): Flag = (this as? JsonPrimitive)?.booleanOrNull

/** The dialog's starting values from `para.state`; a field the paragraphs differ in is blank/null. */
internal fun paraFormFrom(state: JsonObject): ParaForm = ParaForm(
    align = (state["align"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
    left = state["left"].number(),
    right = state["right"].number(),
    first = state["indentFirst"].number(),
    before = state["spaceBefore"].number(),
    after = state["spaceAfter"].number(),
    line = ((state["lineSpacing"] as? JsonObject)?.get("value")).number(),
    keepNext = state["keepNext"].flag(),
    keepLines = state["keepLines"].flag(),
    pageBreakBefore = state["pageBreakBefore"].flag(),
    widowControl = state["widowControl"].flag(),
    contextualSpacing = state["contextualSpacing"].flag(),
)

private fun length(text: String, min: Float, max: Float): Float? = text.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(min, max)

/**
 * The `para.set` params for what changed between [start] and [now]: `props` for the paragraph
 * properties and `onPage` for alignment and indents as seen on the page. Null when nothing changed.
 * Blank or unparseable numbers are left alone.
 */
internal fun paraChanges(start: ParaForm, now: ParaForm): JsonObject? {
    val onPage = buildJsonObject {
        if (now.align != null && now.align != start.align) put("align", now.align)
        if (now.left != start.left) length(now.left, -1584f, 1584f)?.let { put("left", it) }
        if (now.right != start.right) length(now.right, -1584f, 1584f)?.let { put("right", it) }
    }
    val props = buildJsonObject {
        if (now.first != start.first) length(now.first, -1584f, 1584f)?.let { put("indentFirst", it) }
        if (now.before != start.before) length(now.before, 0f, 1584f)?.let { put("spaceBefore", it) }
        if (now.after != start.after) length(now.after, 0f, 1584f)?.let { put("spaceAfter", it) }
        if (now.line != start.line) length(now.line, 0.5f, 5f)?.let { m ->
            put("lineSpacing", buildJsonObject { put("rule", "multiple"); put("value", m) })
        }
        fun flag(key: String, a: Flag, b: Flag) { if (b != null && b != a) put(key, b) }
        flag("keepNext", start.keepNext, now.keepNext)
        flag("keepLines", start.keepLines, now.keepLines)
        flag("pageBreakBefore", start.pageBreakBefore, now.pageBreakBefore)
        flag("widowControl", start.widowControl, now.widowControl)
        flag("contextualSpacing", start.contextualSpacing, now.contextualSpacing)
    }
    if (onPage.isEmpty() && props.isEmpty()) return null
    return buildJsonObject { put("props", props); put("onPage", onPage) }
}

/** The Paragraph dialog: alignment, indents, spacing and the line/page break options. */
@Composable
internal fun ParagraphDialog(start: ParaForm, onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(start) }
    fun bad(text: String, min: Float, max: Float) = text.isNotBlank() && length(text, min, max) == null
    val invalid = bad(f.left, -1584f, 1584f) || bad(f.right, -1584f, 1584f) || bad(f.first, -1584f, 1584f) ||
        bad(f.before, 0f, 1584f) || bad(f.after, 0f, 1584f) || bad(f.line, 0.5f, 5f)
    @Composable
    fun Num(label: String, value: String, modifier: Modifier, set: (String) -> Unit) =
        OutlinedTextField(value, set, label = { Text(label) }, singleLine = true, modifier = modifier, placeholder = { Text("—") })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paragraph") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Alignment", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, name) in ALIGNMENTS) FilterChip(f.align == id, { f = f.copy(align = id) }, label = { Text(name) })
                }
                Text("Indentation (pt)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("Left", f.left, Modifier.weight(1f)) { f = f.copy(left = it) }
                    Num("Right", f.right, Modifier.weight(1f)) { f = f.copy(right = it) }
                    Num("First line", f.first, Modifier.weight(1f)) { f = f.copy(first = it) }
                }
                Text("Spacing", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("Before (pt)", f.before, Modifier.weight(1f)) { f = f.copy(before = it) }
                    Num("After (pt)", f.after, Modifier.weight(1f)) { f = f.copy(after = it) }
                    Num("Line (×)", f.line, Modifier.weight(1f)) { f = f.copy(line = it) }
                }
                Flag("Don't add space between paragraphs of the same style", f.contextualSpacing) { f = f.copy(contextualSpacing = it) }
                Text("Line and page breaks", style = MaterialTheme.typography.labelLarge)
                Flag("Widow/orphan control", f.widowControl) { f = f.copy(widowControl = it) }
                Flag("Keep with next", f.keepNext) { f = f.copy(keepNext = it) }
                Flag("Keep lines together", f.keepLines) { f = f.copy(keepLines = it) }
                Flag("Page break before", f.pageBreakBefore) { f = f.copy(pageBreakBefore = it) }
            }
        },
        confirmButton = { TextButton(enabled = !invalid, onClick = { onApply(paraChanges(start, f) ?: JsonObject(emptyMap())) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A tri-state check box: a click on a mixed one turns it on for all the paragraphs. */
@Composable
private fun Flag(label: String, value: Flag, onChange: (Boolean) -> Unit) {
    val next = value != true
    Row(Modifier.fillMaxWidth().clickable { onChange(next) }, verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(
            when (value) { null -> ToggleableState.Indeterminate; true -> ToggleableState.On; false -> ToggleableState.Off },
            { onChange(next) },
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
