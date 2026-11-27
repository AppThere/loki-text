package com.appthere.loki.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The editable properties of a document (File › Info › Properties), as `file.info` reports them. */
internal data class DocProperties(
    val title: String = "", val subject: String = "", val author: String = "", val keywords: String = "",
    val comments: String = "", val category: String = "",
) {
    /** `file.properties` arguments for the fields that differ from [before]; empty when nothing changed. */
    fun changesFrom(before: DocProperties): JsonObject = buildJsonObject {
        if (title != before.title) put("title", title)
        if (subject != before.subject) put("subject", subject)
        if (author != before.author) put("author", author)
        if (keywords != before.keywords) put("keywords", keywords)
        if (comments != before.comments) put("comments", comments)
        if (category != before.category) put("category", category)
    }
}

/** What File › Info shows: the facts about the document and its properties. */
internal data class DocInfo(
    val path: String?, val dirty: Boolean, val pages: Int, val words: Int, val paragraphs: Int, val sections: Int,
    val comments: Int, val trackChanges: Boolean, val encrypted: Boolean,
    val created: String, val modified: String, val lastModifiedBy: String, val properties: DocProperties,
) {
    /** The facts as label and value rows, in the order they are shown. */
    fun rows(): List<Pair<String, String>> = buildList {
        add("File" to (path ?: "Not saved yet"))
        add("Status" to if (dirty) "Has unsaved changes" else "Saved")
        add("Pages" to "$pages")
        add("Words" to "$words")
        add("Paragraphs" to "$paragraphs")
        add("Sections" to "$sections")
        add("Comments" to "$comments")
        add("Tracked changes" to if (trackChanges) "On" else "Off")
        if (encrypted) add("Password" to "Protected")
        if (created.isNotBlank()) add("Created" to savedAtLabel(created))
        if (modified.isNotBlank()) add("Last modified" to savedAtLabel(modified))
        if (lastModifiedBy.isNotBlank()) add("Last modified by" to lastModifiedBy)
    }
}

internal fun parseDocInfo(r: JsonElement?): DocInfo? {
    val o = r as? JsonObject ?: return null
    fun str(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    fun int(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
    fun flag(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true
    val p = o["properties"] as? JsonObject ?: JsonObject(emptyMap())
    return DocInfo(
        path = (o["path"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
        dirty = flag("dirty"), pages = int("pages"), words = int("words"), paragraphs = int("paragraphs"), sections = int("sections"),
        comments = int("comments"), trackChanges = flag("trackChanges"), encrypted = flag("encrypted"),
        created = str(p, "created"), modified = str(p, "modified"), lastModifiedBy = str(p, "lastModifiedBy"),
        properties = DocProperties(
            str(p, "title"), str(p, "subject"), str(p, "creator"), str(p, "keywords"), str(p, "description"), str(p, "category"),
        ),
    )
}

/** File › Info: the document's facts, and its properties to edit. [onSave] gets the changed fields (not called when nothing changed). */
@Composable
internal fun InfoDialog(info: DocInfo?, error: String?, onSave: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var edit by remember(info) { mutableStateOf(info?.properties ?: DocProperties()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info == null) Text(error ?: "Reading the document…")
                else {
                    for ((k, v) in info.rows()) Row(Modifier.fillMaxWidth()) {
                        Text(k, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Properties", style = MaterialTheme.typography.titleSmall)
                    @Composable fun field(label: String, value: String, set: (String) -> Unit, single: Boolean = true) =
                        OutlinedTextField(value, set, label = { Text(label) }, singleLine = single, modifier = Modifier.fillMaxWidth())
                    field("Title", edit.title, { edit = edit.copy(title = it) })
                    field("Subject", edit.subject, { edit = edit.copy(subject = it) })
                    field("Author", edit.author, { edit = edit.copy(author = it) })
                    field("Keywords", edit.keywords, { edit = edit.copy(keywords = it) })
                    field("Category", edit.category, { edit = edit.copy(category = it) })
                    field("Comments", edit.comments, { edit = edit.copy(comments = it) }, single = false)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            val changes = info?.let { edit.changesFrom(it.properties) }
            TextButton(onClick = { if (changes != null && changes.isNotEmpty()) onSave(changes) else onDismiss() }) {
                Text(if (changes != null && changes.isNotEmpty()) "Save" else "Close")
            }
        },
        dismissButton = { if (info != null && edit != info.properties) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
