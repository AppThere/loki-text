package com.appthere.loki.text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The languages `insert.dateTime` writes in: tag and name. */
internal val DATE_LANGUAGES = listOf("en-US" to "English (United States)", "pt-BR" to "Portuguese (Brazil)")

/** One Date and Time format: the Word picture and now written in it. */
internal data class DateFormat(val format: String, val sample: String)

/** The formats `loki.dateFormats` answered, in its order; those without a picture or sample are skipped. */
internal fun parseDateFormats(r: JsonElement?): List<DateFormat> =
    ((r as? JsonObject)?.get("formats") as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        DateFormat(s("format") ?: return@mapNotNull null, s("sample") ?: return@mapNotNull null)
    }

/** What the Date and Time dialog chose last in this window, so it opens there again. */
internal data class DateChoice(val language: String, val format: String? = null, val update: Boolean = false)

/** The language the dialog starts in for an interface language code (`pt-br`, `de`): Portuguese ones write Portuguese dates. */
internal fun dateLanguageFor(uiLanguage: String): String = if (uiLanguage.lowercase().startsWith("pt")) "pt-BR" else "en-US"

internal fun dateTimeParams(format: String, language: String, update: Boolean): JsonObject = buildJsonObject {
    put("format", format); put("language", language); put("update", update)
}

/**
 * Insert › Date & Time: today's date in each format of the chosen language, and whether it is a
 * field that keeps itself up to date. [formatsFor] reads the formats for a language from the engine.
 */
@Composable
internal fun DateTimeDialog(start: DateChoice, formatsFor: suspend (String) -> List<DateFormat>, onInsert: (DateChoice, JsonObject) -> Unit, onDismiss: () -> Unit) {
    var language by remember { mutableStateOf(start.language) }
    var picked by remember { mutableStateOf(start.format) }
    var update by remember { mutableStateOf(start.update) }
    var formats by remember { mutableStateOf<List<DateFormat>?>(null) }
    LaunchedEffect(language) {
        val list = formatsFor(language)
        formats = list
        // A language without the picture chosen starts over at its first one.
        if (list.none { it.format == picked }) picked = list.firstOrNull()?.format
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Date and Time")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Language"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((tag, name) in DATE_LANGUAGES) FilterChip(language == tag, { language = tag }, label = { Text(tr(name)) })
                }
                Text(tr("Available formats"), style = MaterialTheme.typography.labelLarge)
                val list = formats
                when {
                    list == null -> Text(tr("Reading the formats…"))
                    list.isEmpty() -> Text(tr("The engine gave no date formats."), color = MaterialTheme.colorScheme.error)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(list) { d ->
                            Text(
                                d.sample,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (d.format == picked) FontWeight.Bold else FontWeight.Normal,
                                color = if (d.format == picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                                    .semantics { selected = d.format == picked }
                                    .clickable(role = Role.RadioButton) { picked = d.format }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Row(
                    Modifier.toggleable(update, role = Role.Checkbox) { update = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(update, null)
                    Text(tr("Update automatically"))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked != null, onClick = {
                val f = picked ?: return@TextButton
                onInsert(DateChoice(language, f, update), dateTimeParams(f, language, update))
            }) { Text(tr("Insert")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
