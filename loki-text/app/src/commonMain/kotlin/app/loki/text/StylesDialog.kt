package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put

/** One row of `styles.manage`: a style of any kind, with the formatting it sets itself. */
internal class StyleEntry(
    val id: String,
    val name: String,
    /** `paragraph`, `character`, `linked` or `table`. */
    val type: String,
    val builtIn: Boolean,
    val inGallery: Boolean,
    val hidden: Boolean,
    val basedOn: String?,
    val format: JsonObject,
)

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.num(): Float? = (this as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }

/** Every style `styles.manage` lists, in its order (rows without an id or a name are skipped). */
internal fun parseStyleEntries(manage: JsonElement): List<StyleEntry> = (manage as? JsonArray).orEmpty().mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    StyleEntry(
        id = o["id"].str() ?: return@mapNotNull null,
        name = o["name"].str() ?: return@mapNotNull null,
        type = o["type"].str() ?: "paragraph",
        builtIn = o["builtIn"].flag() ?: true,
        inGallery = o["inGallery"].flag() == true,
        hidden = o["hidden"].flag() == true,
        basedOn = o["basedOn"].str(),
        format = o["format"] as? JsonObject ?: JsonObject(emptyMap()),
    )
}

private fun pt(x: Float) = sizeText((x * 10).let { kotlin.math.round(it) / 10f })

/** The style's type, what it sets and what it is based on, as one line. */
internal fun describeStyle(s: StyleEntry): String = buildList {
    add(when (s.type) { "character" -> "Character style"; "linked" -> "Linked style (paragraph and character)"; "table" -> "Table style"; else -> "Paragraph style" })
    if (s.builtIn) add("Built-in style")
    s.format["font"].str()?.let { add("Font: $it") }
    s.format["size"].num()?.let { add("${pt(it)} pt") }
    if (s.format["bold"].flag() == true) add("Bold")
    if (s.format["italic"].flag() == true) add("Italic")
    s.format["color"].str()?.let { add("Color: #$it") }
    s.format["spaceBefore"].num()?.let { add("Space before: ${pt(it)} pt") }
    s.format["spaceAfter"].num()?.let { add("Space after: ${pt(it)} pt") }
    s.basedOn?.let { add("Based on: $it") }
}.joinToString(", ")

/** Modify Style's fields. Blank text and a null flag mean the style doesn't set that itself (it is inherited). */
internal data class StyleForm(
    val name: String = "",
    val font: String = "",
    val size: String = "",
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val color: String = "",
    val before: String = "",
    val after: String = "",
)

internal fun styleFormFrom(s: StyleEntry): StyleForm = StyleForm(
    name = s.name,
    font = s.format["font"].str().orEmpty(),
    size = sizeText(s.format["size"].num()),
    bold = s.format["bold"].flag(),
    italic = s.format["italic"].flag(),
    color = s.format["color"].str().orEmpty(),
    before = sizeText(s.format["spaceBefore"].num()),
    after = sizeText(s.format["spaceAfter"].num()),
)

/**
 * `styles.modify` params for what changed between [start] and [now], or null for no change. A
 * field cleared back to blank is left alone (the engine overlays; it can't un-set a value).
 */
internal fun styleChanges(id: String, start: StyleForm, now: StyleForm): JsonObject? {
    val chr = buildJsonObject {
        if (now.font.isNotBlank() && now.font.trim() != start.font.trim()) put("font", now.font.trim())
        if (now.size.trim() != start.size.trim()) parseFontSize(now.size)?.let { put("size", it) }
        if (now.bold != null && now.bold != start.bold) put("bold", now.bold)
        if (now.italic != null && now.italic != start.italic) put("italic", now.italic)
        if (now.color.isNotBlank() && !now.color.trim().equals(start.color.trim(), ignoreCase = true)) {
            val c = now.color.trim()
            if (c.equals("auto", ignoreCase = true)) put("color", "Auto")
            else parseHexColor(c)?.let { (r, g, b) -> put("color", buildJsonObject { put("Rgb", JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b)))) }) }
        }
    }
    val para = buildJsonObject {
        fun space(key: String, a: String, b: String) {
            if (b.trim() != a.trim()) b.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.let { put(key, it.coerceIn(0f, 1584f)) }
        }
        space("spaceBefore", start.before, now.before)
        space("spaceAfter", start.after, now.after)
    }
    val rename = now.name.trim().takeIf { it.isNotEmpty() && it != start.name.trim() }
    if (chr.isEmpty() && para.isEmpty() && rename == null) return null
    return buildJsonObject {
        put("style", id)
        if (chr.isNotEmpty()) put("chr", chr)
        if (para.isNotEmpty()) put("para", para)
        if (rename != null) put("name", rename)
    }
}

private fun glyph(type: String) = when (type) { "character" -> "a"; "linked" -> "¶a"; "table" -> "⊞"; else -> "¶" }

/**
 * Manage Styles: every style in the document, with its description and actions. [run] calls an
 * engine command; the list reloads afterwards from [styles].
 */
@Composable
internal fun ManageStylesDialog(
    styles: List<StyleEntry>,
    alphabetical: Boolean,
    onSort: (Boolean) -> Unit,
    run: (String, JsonObject) -> Unit,
    onModify: (StyleEntry) -> Unit,
    onNew: (StyleEntry?) -> Unit,
    onClose: () -> Unit,
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val cur = styles.firstOrNull { it.id == selectedId } ?: styles.firstOrNull()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Manage Styles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort:", style = MaterialTheme.typography.labelLarge)
                    FilterChip(!alphabetical, { onSort(false) }, label = { Text("As recommended") })
                    FilterChip(alphabetical, { onSort(true) }, label = { Text("Alphabetical") })
                }
                LazyColumn(Modifier.fillMaxWidth().height(200.dp)) {
                    items(styles, key = { it.id }) { st ->
                        val on = st.id == cur?.id
                        Text(
                            "${glyph(st.type)}  ${st.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (st.hidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth()
                                .background(if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { selectedId = st.id }.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                if (cur != null) {
                    Text(describeStyle(cur), style = MaterialTheme.typography.bodySmall)
                    if (cur.type != "table") {
                        Row(Modifier.clickable { run("styles.setVisibility", buildJsonObject { put("style", cur.id); put("gallery", !cur.inGallery) }) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(cur.inGallery, { run("styles.setVisibility", buildJsonObject { put("style", cur.id); put("gallery", it) }) })
                            Text("Show in the Styles gallery", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(Modifier.clickable { run("styles.setVisibility", buildJsonObject { put("style", cur.id); put("hidden", !cur.hidden) }) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(cur.hidden, { run("styles.setVisibility", buildJsonObject { put("style", cur.id); put("hidden", it) }) })
                        Text("Hide from style lists", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (cur.type != "table") TextButton(onClick = { run("styles.apply", buildJsonObject { put("style", cur.id) }) }) { Text("Apply") }
                        TextButton(enabled = cur.type != "table", onClick = { onModify(cur) }) { Text("Modify…") }
                        TextButton(onClick = { onNew(cur) }) { Text("New…") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (cur.type == "paragraph" || cur.type == "linked" || cur.type == "character") {
                            TextButton(onClick = { run("styles.updateToMatch", buildJsonObject { put("style", cur.id) }) }) { Text("Update to selection") }
                        }
                        TextButton(enabled = !cur.builtIn, onClick = { run("styles.delete", buildJsonObject { put("style", cur.id) }); selectedId = null }) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Modify Style: the formatting the style sets itself. Empty fields stay inherited. */
@Composable
internal fun ModifyStyleDialog(style: StyleEntry, onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    val start = remember(style.id) { styleFormFrom(style) }
    var f by remember(style.id) { mutableStateOf(start) }
    val sizeOk = f.size.isBlank() || parseFontSize(f.size) != null
    val colorOk = f.color.isBlank() || f.color.equals("auto", true) || parseHexColor(f.color.trim()) != null
    fun spaceOk(t: String) = t.isBlank() || t.trim().toFloatOrNull()?.isFinite() == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modify Style") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.name, { f = f.copy(name = it) }, label = { Text("Name") }, singleLine = true, enabled = !style.builtIn, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.font, { f = f.copy(font = it) }, label = { Text("Font") }, placeholder = { Text("Inherited") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(f.size, { f = f.copy(size = it) }, label = { Text("Size (pt)") }, placeholder = { Text("—") }, singleLine = true, isError = !sizeOk, modifier = Modifier.weight(1f))
                    OutlinedTextField(f.color, { f = f.copy(color = it.removePrefix("#")) }, label = { Text("Color") }, placeholder = { Text("RRGGBB / auto") }, singleLine = true, isError = !colorOk, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for ((label, value, set) in listOf<Triple<String, Boolean?, (Boolean) -> StyleForm>>(
                        Triple("Bold", f.bold) { f.copy(bold = it) }, Triple("Italic", f.italic) { f.copy(italic = it) },
                    )) Row(verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(
                            when (value) { null -> ToggleableState.Indeterminate; true -> ToggleableState.On; false -> ToggleableState.Off },
                            { f = set(value != true) },
                        )
                        Text(label)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(f.before, { f = f.copy(before = it) }, label = { Text("Space before (pt)") }, placeholder = { Text("—") }, singleLine = true, isError = !spaceOk(f.before), modifier = Modifier.weight(1f))
                    OutlinedTextField(f.after, { f = f.copy(after = it) }, label = { Text("Space after (pt)") }, placeholder = { Text("—") }, singleLine = true, isError = !spaceOk(f.after), modifier = Modifier.weight(1f))
                }
                Text("An empty field or a mixed box leaves that setting inherited from the style this one is based on.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = sizeOk && colorOk && spaceOk(f.before) && spaceOk(f.after), onClick = { onApply(styleChanges(style.id, start, f) ?: JsonObject(emptyMap())) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** `styles.create` params; null when the name is blank. */
internal fun createStyleParams(name: String, basedOn: String?, fromSelection: Boolean): JsonObject? {
    val n = name.trim().takeIf { it.isNotEmpty() } ?: return null
    return buildJsonObject {
        put("name", n)
        if (!basedOn.isNullOrBlank()) put("basedOn", basedOn)
        put("fromSelection", fromSelection)
    }
}

/** New Style: a name, what it is based on, and whether it starts from the selection's formatting. */
@Composable
internal fun NewStyleDialog(basedOn: String?, onCreate: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("Style1") }
    var fromSelection by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Style") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Based on: ${basedOn ?: "Normal"}", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.clickable { fromSelection = !fromSelection }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(fromSelection, { fromSelection = it })
                    Text("Start from the selection's formatting")
                }
            }
        },
        confirmButton = { TextButton(enabled = createStyleParams(name, basedOn, fromSelection) != null, onClick = { createStyleParams(name, basedOn, fromSelection)?.let(onCreate) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
