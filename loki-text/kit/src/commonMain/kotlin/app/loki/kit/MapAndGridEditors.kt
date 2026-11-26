package app.loki.kit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** A map from names to values as rows of two boxes, to add and remove. */
@Composable
internal fun NamedValuesEditor(shape: ParamShape.NamedValues, value: String, set: (String, String?) -> Unit) {
    // Each row has a number of its own, so removing one doesn't hand its text box's cursor to the next.
    val next = remember { mutableStateOf(0) }
    val rows = remember {
        val start = namedTexts(parseJsonOrNull(value)).ifEmpty { listOf("" to "") }
        mutableStateListOf<Pair<Int, Pair<String, String>>>().apply { start.forEach { add(next.value++ to it) } }
    }
    fun publish() {
        val built = buildNamedValues(shape, rows.map { it.second })
        set(built.getOrNull()?.toString().orEmpty(), built.exceptionOrNull()?.message)
    }
    val range = shape.range
    val valueLabel = if (range != null) "${range.first}–${range.last}" else kt("Value")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { i, (id, row) ->
            key(id) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        row.first, { rows[i] = id to (it to row.second); publish() },
                        label = { Text(kt(shape.nameLabel)) }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        row.second, { rows[i] = id to (row.first to it); publish() },
                        label = { Text(valueLabel) }, singleLine = true,
                        keyboardOptions = if (shape.value.kind == FieldKind.Number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                        modifier = Modifier.width(96.dp),
                    )
                    TextButton({ rows.removeAll { it.first == id }; publish() }) { Text(kt("Remove")) }
                }
            }
        }
        TextButton({ rows.add(next.value++ to ("" to "")); publish() }) { Text(kt("Add")) }
    }
}

/** The grid a fresh [GridEditor] starts as. */
private const val START_ROWS = 3
private const val START_COLUMNS = 3

/** Rows of cells as a grid, with rows and columns to add and take off the end. */
@Composable
internal fun GridEditor(shape: ParamShape.Grid, value: String, set: (String, String?) -> Unit) {
    val cells = remember {
        val start = gridTexts(parseJsonOrNull(value))
        val columns = start.maxOfOrNull { it.size } ?: START_COLUMNS
        val filled = start.ifEmpty { List(START_ROWS) { emptyList<String>() } }.map { row -> List(columns) { row.getOrElse(it) { "" } } }
        mutableStateListOf<List<String>>().apply { addAll(filled) }
    }
    val columns = cells.firstOrNull()?.size ?: 0
    fun publish() {
        val built = buildGrid(shape, cells)
        set(built.getOrNull()?.toString().orEmpty(), built.exceptionOrNull()?.message)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { c, text ->
                        val name = "${kt("Row")} ${r + 1}, ${kt("Column")} ${c + 1}"
                        OutlinedTextField(
                            text, { typed -> cells[r] = cells[r].mapIndexed { i, old -> if (i == c) typed else old }; publish() },
                            singleLine = true, modifier = Modifier.width(120.dp).semantics { contentDescription = name },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ cells.add(List(maxOf(columns, 1)) { "" }); publish() }, enabled = cells.size < shape.maxRows) { Text(kt("Add row")) }
            TextButton({ cells.removeAt(cells.lastIndex); publish() }, enabled = cells.size > 1) { Text(kt("Remove row")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ for (r in cells.indices) cells[r] = cells[r] + ""; publish() }, enabled = columns < shape.maxColumns) { Text(kt("Add column")) }
            TextButton({ for (r in cells.indices) cells[r] = cells[r].dropLast(1); publish() }, enabled = columns > 1) { Text(kt("Remove column")) }
        }
        Text("${cells.size} × $columns", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
