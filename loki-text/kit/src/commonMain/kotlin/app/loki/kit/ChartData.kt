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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** A chart's data as a table: a category per row, a series per column, the cells as typed (blank for no value). */
data class ChartTable(val categories: List<String>, val series: List<String>, val cells: List<List<String>>) {
    fun cell(row: Int, col: Int) = cells.getOrNull(row)?.getOrNull(col).orEmpty()
    fun withCell(row: Int, col: Int, text: String) = copy(cells = cells.mapIndexed { r, cs -> if (r != row) cs else cs.mapIndexed { c, old -> if (c == col) text else old } })
    fun withCategory(row: Int, text: String) = copy(categories = categories.mapIndexed { r, old -> if (r == row) text else old })
    fun withSeriesName(col: Int, text: String) = copy(series = series.mapIndexed { c, old -> if (c == col) text else old })
    fun withRow() = copy(categories = categories + "", cells = cells + listOf(series.map { "" }))
    fun withoutRow(row: Int) = copy(categories = categories.filterIndexed { r, _ -> r != row }, cells = cells.filterIndexed { r, _ -> r != row })
    fun withColumn() = copy(series = series + "Series ${series.size + 1}", cells = cells.map { it + "" })
    fun withoutColumn(col: Int) = copy(series = series.filterIndexed { c, _ -> c != col }, cells = cells.map { r -> r.filterIndexed { c, _ -> c != col } })

    /**
     * The `data` value: categories, and each series with a value per category (null for a blank cell). An error
     * names the cell that isn't a number.
     */
    fun toData(): Result<JsonObject> = runCatching {
        buildJsonObject {
            put("categories", buildJsonArray { categories.forEach { add(JsonPrimitive(it)) } })
            put("series", buildJsonArray {
                series.forEachIndexed { c, name ->
                    add(buildJsonObject {
                        put("name", JsonPrimitive(name))
                        put("values", buildJsonArray {
                            categories.indices.forEach { r ->
                                val t = cell(r, c).trim().replace(',', '.')
                                add(if (t.isEmpty()) JsonNull else t.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("${name.ifBlank { "Series ${c + 1}" }}, ${categories[r].ifBlank { "row ${r + 1}" }}: needs a number")))
                            }
                        })
                    })
                }
            })
        }
    }

    companion object {
        /** A table from a `data` value; a starting table of three categories and one series when there is none. */
        fun from(data: JsonObject?): ChartTable {
            val cats = (data?.get("categories") as? JsonArray)?.map { textOf(it) }
            val series = (data?.get("series") as? JsonArray)?.mapNotNull { it as? JsonObject }
            if (cats == null && series == null) return ChartTable(listOf("Category 1", "Category 2", "Category 3"), listOf("Series 1"), List(3) { listOf("") })
            val names = series.orEmpty().map { textOf(it["name"]) }
            val rows = (cats ?: emptyList()).indices.map { r -> series.orEmpty().map { s -> textOf((s["values"] as? JsonArray)?.getOrNull(r)) } }
            return ChartTable(cats ?: emptyList(), names, rows)
        }
    }
}

/** Is [form] a chart's data (`categories` and `series` with names and values), which reads better as a grid? */
fun isChartData(form: ParamForm): Boolean = form.fields.map { it.name }.toSet() == setOf("categories", "series")

/** Chart data as a grid: categories down the side, a column per series, a value in each cell. */
@Composable
fun ChartDataGrid(value: String, set: (String, String?) -> Unit) {
    var t by remember { mutableStateOf(ChartTable.from(parseJsonOrNull(value) as? JsonObject)) }
    fun update(next: ChartTable) {
        t = next
        val built = next.toData()
        set(built.getOrNull()?.toString().orEmpty(), built.exceptionOrNull()?.message)
    }
    // A fresh grid is data too: publish it at once so OK sends what is shown.
    androidx.compose.runtime.LaunchedEffect(Unit) { update(t) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Saving replaces the chart's data with this table.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("", Modifier.width(140.dp))
                t.series.forEachIndexed { c, name ->
                    Column(Modifier.width(120.dp)) {
                        OutlinedTextField(name, { update(t.withSeriesName(c, it)) }, singleLine = true, label = { Text(kt("Series")) })
                        TextButton({ update(t.withoutColumn(c)) }) { Text(kt("Remove")) }
                    }
                }
                TextButton({ update(t.withColumn()) }) { Text(kt("Add series")) }
            }
            t.categories.forEachIndexed { r, cat ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(cat, { update(t.withCategory(r, it)) }, singleLine = true, label = { Text(kt("Category")) }, modifier = Modifier.width(140.dp))
                    t.series.indices.forEach { c ->
                        OutlinedTextField(
                            t.cell(r, c), { update(t.withCell(r, c, it)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(120.dp),
                        )
                    }
                    TextButton({ update(t.withoutRow(r)) }) { Text(kt("Remove")) }
                }
            }
            TextButton({ update(t.withRow()) }) { Text(kt("Add category")) }
        }
    }
}
