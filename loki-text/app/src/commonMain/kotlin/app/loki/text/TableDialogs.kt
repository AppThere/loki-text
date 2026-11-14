package app.loki.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The table commands that ask for values first, and their dialogs' titles. */
enum class TableDialog(val command: String, val title: String) {
    Split("table.split", "Split Cells"),
    Sort("table.sort", "Sort"),
    Formula("table.formula", "Formula"),
    AltText("table.altText", "Alt Text"),
    CellMargins("table.cellMargins", "Cell Margins"),
    Properties("table.properties", "Table Properties"),
}

/** The dialog a command opens instead of running, if it has one. */
internal fun tableDialogFor(command: String): TableDialog? = TableDialog.entries.firstOrNull { it.command == command }

/** Most rows or columns Split Cells makes from one cell (the engine's limit). */
private const val MAX_SPLIT = 63

/** Split Cells: whole numbers from 1 to 63, or null when either box isn't one. */
internal fun splitParams(columns: String, rows: String, mergeFirst: Boolean): JsonObject? {
    val c = columns.trim().toIntOrNull()?.takeIf { it in 1..MAX_SPLIT } ?: return null
    val r = rows.trim().toIntOrNull()?.takeIf { it in 1..MAX_SPLIT } ?: return null
    return buildJsonObject { put("columns", c); put("rows", r); put("mergeFirst", mergeFirst) }
}

/** Sort: [column] counts from 0, as the engine takes it. */
internal fun sortParams(column: Int, descending: Boolean, header: Boolean): JsonObject =
    buildJsonObject { put("column", column.coerceAtLeast(0)); put("descending", descending); put("header", header) }

/** Formula: the typed formula, with the leading `=` added when it was left out; null when blank. */
internal fun formulaParams(text: String): JsonObject? {
    val t = text.trim().takeIf { it.isNotEmpty() && it != "=" } ?: return null
    return buildJsonObject { put("formula", if (t.startsWith("=")) t else "=$t") }
}

/** Alt Text: both boxes as typed; empty clears. */
internal fun altTextParams(title: String, text: String): JsonObject =
    buildJsonObject { put("title", title.trim()); put("text", text.trim()) }

/** A typed margin in inches as points: zero or more, up to 22 inches; null when it isn't one. */
internal fun parseMargin(text: String): Float? {
    val t = text.trim().removeSuffix("\"").removeSuffix("in").trim()
    if (t.toFloatOrNull() == 0f) return 0f
    return parseInches(t)
}

/** Cell Margins: four boxes in inches (top, left, bottom, right), or null when one isn't a margin. */
internal fun marginParams(values: List<String>): JsonObject? {
    val pts = values.map { parseMargin(it) ?: return null }
    if (pts.size != 4) return null
    return buildJsonObject {
        put("top", pts[0]); put("left", pts[1]); put("bottom", pts[2]); put("right", pts[3])
    }
}

/** What the Table Properties dialog edits, with sizes as typed (inches; blank is automatic). */
internal data class TablePropsForm(
    val align: String,
    val width: String,
    val indent: String,
    val rowHeight: String,
    val exact: Boolean,
    val allowBreak: Boolean,
    val headerRow: Boolean,
    val columnWidth: String,
    val cellWidth: String,
    val valign: String,
)

internal fun tablePropsForm(t: TableState?, headerRow: Boolean): TablePropsForm = TablePropsForm(
    align = t?.align ?: "left",
    width = inchText(t?.width),
    indent = inchText(t?.indent),
    rowHeight = inchText(t?.rowHeight),
    exact = t?.exact == true,
    allowBreak = t?.allowBreak != false,
    headerRow = headerRow,
    columnWidth = inchText(t?.columnWidth),
    cellWidth = inchText(t?.cellWidth),
    valign = t?.valign ?: "top",
)

/**
 * Table Properties: only what changed from [before], so an untouched size is never rewritten. A size
 * box cleared to blank sets it back to automatic (null), except the column width, which always has
 * one. Null when a box holds something that isn't a size.
 */
internal fun propertiesParams(before: TablePropsForm, after: TablePropsForm): JsonObject? {
    var bad = false
    fun size(k: String, b: String, a: String, blankIsAuto: Boolean, zeroOk: Boolean = false): Pair<String, Float?>? {
        if (a.trim() == b.trim()) return null
        if (a.isBlank()) {
            if (!blankIsAuto) bad = true
            return if (blankIsAuto) k to null else null
        }
        val pt = (if (zeroOk) parseMargin(a) else parseInches(a)) ?: run { bad = true; return null }
        return k to pt
    }
    val sizes = listOfNotNull(
        size("width", before.width, after.width, blankIsAuto = true),
        size("indent", before.indent, after.indent, blankIsAuto = false, zeroOk = true),
        size("rowHeight", before.rowHeight, after.rowHeight, blankIsAuto = true),
        size("columnWidth", before.columnWidth, after.columnWidth, blankIsAuto = false),
        size("cellWidth", before.cellWidth, after.cellWidth, blankIsAuto = true),
    )
    if (bad) return null
    return buildJsonObject {
        if (after.align != before.align) put("align", after.align)
        for ((k, v) in sizes) if (v == null) put(k, JsonNull) else put(k, v)
        // The rule goes with a height: a new height, or the same one with the rule changed.
        if (after.exact != before.exact || sizes.any { it.first == "rowHeight" && it.second != null }) put("rowHeightRule", if (after.exact) "exact" else "atLeast")
        if (after.allowBreak != before.allowBreak) put("allowBreak", after.allowBreak)
        if (after.headerRow != before.headerRow) put("headerRow", after.headerRow)
        if (after.valign != before.valign) put("valign", after.valign)
    }
}

/** The dialog for [dialog], started from the caret's table [table]; [on] is the toggles that are on. */
@Composable
fun TableDialogView(dialog: TableDialog, table: TableState?, on: Set<String>, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    when (dialog) {
        TableDialog.Split -> SplitDialog(onSubmit, onDismiss)
        TableDialog.Sort -> SortDialog(table?.columns ?: 0, "table.repeatHeader" in on, onSubmit, onDismiss)
        TableDialog.Formula -> FormulaDialog(onSubmit, onDismiss)
        TableDialog.AltText -> AltTextDialog(table, onSubmit, onDismiss)
        TableDialog.CellMargins -> CellMarginsDialog(table, onSubmit, onDismiss)
        TableDialog.Properties -> PropertiesDialog(table, "table.repeatHeader" in on, onSubmit, onDismiss)
    }
}

@Composable
private fun FormDialog(title: String, params: JsonObject?, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit, body: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() } },
        confirmButton = { TextButton({ params?.let(onSubmit) }, enabled = params != null) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.width(140.dp), onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier)

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

/** One chip per choice, as (label, value); [selected] is a value. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Chips(choices: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, value) in choices) FilterChip(selected = selected == value, onClick = { onPick(value) }, label = { Text(label) })
    }
}

@Composable
private fun SplitDialog(onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var columns by remember { mutableStateOf("2") }
    var rows by remember { mutableStateOf("1") }
    var merge by remember { mutableStateOf(true) }
    FormDialog("Split Cells", splitParams(columns, rows, merge), onSubmit, onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Number of columns", columns) { columns = it.filter(Char::isDigit).take(2) }
            Field("Number of rows", rows) { rows = it.filter(Char::isDigit).take(2) }
        }
        Check("Merge cells before split", merge) { merge = it }
    }
}

@Composable
private fun SortDialog(columns: Int, header: Boolean, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var column by remember { mutableStateOf(0) }
    var descending by remember { mutableStateOf(false) }
    var hasHeader by remember { mutableStateOf(header) }
    FormDialog("Sort", sortParams(column, descending, hasHeader), onSubmit, onDismiss) {
        Heading("Sort by")
        Chips((0 until columns.coerceIn(1, MAX_SPLIT)).map { "Column ${it + 1}" to it.toString() }, column.toString()) { column = it.toIntOrNull() ?: 0 }
        Chips(listOf("Ascending" to "asc", "Descending" to "desc"), if (descending) "desc" else "asc") { descending = it == "desc" }
        Check("My list has a header row", hasHeader) { hasHeader = it }
    }
}

@Composable
private fun FormulaDialog(onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var formula by remember { mutableStateOf("=SUM(ABOVE)") }
    FormDialog("Formula", formulaParams(formula), onSubmit, onDismiss) {
        Field("Formula", formula, Modifier.width(288.dp)) { formula = it }
        Text("For example =SUM(ABOVE), =AVERAGE(LEFT), =A1*B2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun AltTextDialog(table: TableState?, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(table?.altTitle.orEmpty()) }
    var text by remember { mutableStateOf(table?.altText.orEmpty()) }
    FormDialog("Alt Text", altTextParams(title, text), onSubmit, onDismiss) {
        Field("Title", title, Modifier.width(288.dp)) { title = it }
        OutlinedTextField(text, { text = it }, label = { Text("Description") }, minLines = 3, modifier = Modifier.width(288.dp))
    }
}

@Composable
private fun CellMarginsDialog(table: TableState?, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    val start = table?.cellMargins ?: listOf(0f, 5.4f, 0f, 5.4f)
    var values by remember { mutableStateOf(start.map { if (it == 0f) "0\"" else inchText(it) }) }
    fun set(i: Int, v: String) { values = values.toMutableList().also { it[i] = v } }
    FormDialog("Cell Margins", marginParams(values), onSubmit, onDismiss) {
        Heading("Default cell margins (inches)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Top", values[0]) { set(0, it) }
            Field("Bottom", values[2]) { set(2, it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Left", values[1]) { set(1, it) }
            Field("Right", values[3]) { set(3, it) }
        }
    }
}

@Composable
private fun PropertiesDialog(table: TableState?, header: Boolean, onSubmit: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    val before = remember { tablePropsForm(table, header) }
    var f by remember { mutableStateOf(before) }
    FormDialog("Table Properties", propertiesParams(before, f), onSubmit, onDismiss) {
        Text("Sizes are in inches; leave a box blank for automatic.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Heading("Table")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Preferred width", f.width) { f = f.copy(width = it) }
            Field("Indent from left", f.indent) { f = f.copy(indent = it) }
        }
        Chips(listOf("Left" to "left", "Center" to "center", "Right" to "right"), f.align) { f = f.copy(align = it) }
        Heading("Row")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("Height", f.rowHeight) { f = f.copy(rowHeight = it) }
            Chips(listOf("At least" to "atLeast", "Exactly" to "exact"), if (f.exact) "exact" else "atLeast") { f = f.copy(exact = it == "exact") }
        }
        Check("Allow row to break across pages", f.allowBreak) { f = f.copy(allowBreak = it) }
        Check("Repeat as header row at the top of each page", f.headerRow) { f = f.copy(headerRow = it) }
        Heading("Column")
        Field("Width", f.columnWidth) { f = f.copy(columnWidth = it) }
        Heading("Cell")
        Field("Preferred width", f.cellWidth) { f = f.copy(cellWidth = it) }
        Chips(listOf("Top" to "top", "Center" to "center", "Bottom" to "bottom"), f.valign) { f = f.copy(valign = it) }
    }
}
