package com.appthere.loki.text

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.appthere.loki.kit.SheetDialog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** The mail-merge recipient list: the field names and one row of text per record, each row as long as the field list. */
internal data class RecipientTable(val fields: List<String>, val rows: List<List<String>>) {
    /** `mailings.editRecipients` arguments that replace the list with this table. */
    fun toParams(): JsonObject = buildJsonObject {
        put("fields", buildJsonArray { fields.forEach { add(JsonPrimitive(it)) } })
        put("rows", buildJsonArray { rows.forEach { r -> add(buildJsonArray { fields.indices.forEach { i -> add(JsonPrimitive(r.getOrElse(i) { "" })) } }) } })
    }

    fun withField(name: String) = copy(fields = fields + name, rows = rows.map { it + "" })
    fun withoutField(i: Int) = copy(fields = fields.filterIndexed { n, _ -> n != i }, rows = rows.map { r -> r.filterIndexed { n, _ -> n != i } })
    fun withRow() = copy(rows = rows + listOf(fields.map { "" }))
    fun withoutRow(i: Int) = copy(rows = rows.filterIndexed { n, _ -> n != i })
    fun withCell(row: Int, col: Int, text: String) =
        copy(rows = rows.mapIndexed { r, cells -> if (r != row) cells else cells.mapIndexed { c, old -> if (c == col) text else old } })

    /** A new field name that is not blank and not one already there. */
    fun canAddField(name: String) = name.isNotBlank() && fields.none { it.equals(name.trim(), ignoreCase = true) }
}

/** The list as `mailings.editRecipients` reports it (`headers`, and `rows` as objects by field or as arrays); null when it isn't that. */
internal fun parseRecipients(r: JsonElement?): RecipientTable? {
    val o = r as? JsonObject ?: return null
    fun text(e: JsonElement?) = when (e) { null, JsonNull -> ""; is JsonPrimitive -> e.contentOrNull.orEmpty(); else -> e.toString() }
    val headers = (o["headers"] as? JsonArray ?: return null).map { text(it) }
    val rows = (o["rows"] as? JsonArray).orEmpty().map { row ->
        when (row) {
            is JsonObject -> headers.map { h -> text(row[h]) }
            is JsonArray -> headers.indices.map { i -> text(row.getOrNull(i)) }
            else -> headers.map { "" }
        }
    }
    return RecipientTable(headers, rows)
}

/** Mailings › Edit Recipient List: the table to change, a row or a field at a time. [onSave] gets the new table. */
@Composable
internal fun RecipientsDialog(start: RecipientTable, error: String?, onSave: (RecipientTable) -> Unit, onDismiss: () -> Unit) {
    var t by remember(start) { mutableStateOf(start) }
    var newField by remember { mutableStateOf("") }
    SheetDialog(
        "Edit recipient list", "${t.rows.size} ${if (t.rows.size == 1) "record" else "records"}", onDismiss, maxWidth = 900,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onSave(t) }) { Text("Save") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newField, { newField = it }, singleLine = true, label = { Text("New field") }, modifier = Modifier.width(200.dp))
                TextButton(onClick = { t = t.withField(newField.trim()); newField = "" }, enabled = t.canAddField(newField)) { Text("Add field") }
                TextButton(onClick = { t = t.withRow() }, enabled = t.fields.isNotEmpty()) { Text("Add record") }
            }
            if (t.fields.isEmpty()) Text("This list has no fields yet. Add one to begin.", style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    t.fields.forEachIndexed { i, name ->
                        Row(Modifier.width(160.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = { t = t.withoutField(i) }) { Text("×") }
                        }
                    }
                    Text("", Modifier.width(48.dp))
                }
                t.rows.forEachIndexed { r, cells ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        cells.forEachIndexed { c, v -> OutlinedTextField(v, { t = t.withCell(r, c, it) }, singleLine = true, modifier = Modifier.width(160.dp)) }
                        TextButton(onClick = { t = t.withoutRow(r) }) { Text("Delete") }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
