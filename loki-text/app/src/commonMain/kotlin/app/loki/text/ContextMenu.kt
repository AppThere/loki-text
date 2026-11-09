package app.loki.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.loki.text.engine.PageRect
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A proofing problem at the caret, from `review.suggestions`: its [kind] (`spelling`, `grammar`, …), a [message] and the fixes. */
class ProofIssue(val kind: String, val message: String, val suggestions: List<String>)

/** The most suggestions and synonyms a menu lists; the engine's reply is not trusted to be short. */
private const val MAX_MENU_WORDS = 8

/** Parses `review.suggestions`; null when nothing is wrong at the caret (the engine replies null) or the reply is malformed. */
fun parseIssue(reply: JsonElement?): ProofIssue? {
    val o = reply as? JsonObject ?: return null
    val suggestions = (o["suggestions"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }.take(MAX_MENU_WORDS)
    return ProofIssue(o["kind"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["message"]?.jsonPrimitive?.contentOrNull.orEmpty(), suggestions)
}

/** Parses `review.thesaurus`: its `synonyms` list, empty when there are none. */
fun parseSynonyms(reply: JsonElement?): List<String> =
    ((reply as? JsonObject)?.get("synonyms") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }.take(MAX_MENU_WORDS)

/** A menu opened over page [page] at ([x], [y]) pixels in that page's image, with what the engine said about the word there. */
class ContextMenuData(val page: Int, val x: Float, val y: Float, val issue: ProofIssue?, val synonyms: List<String>)

/** One line of the menu. */
sealed interface MenuEntry {
    /** A small heading, not clickable. */
    class Header(val text: String) : MenuEntry
    /** A greyed note, not clickable. */
    class Note(val text: String) : MenuEntry
    object Divider : MenuEntry
    /** A command (or [steps] of them, in order) to run, or [dialog] to open first. */
    class Item(val label: String, val steps: List<Pair<String, JsonObject>> = emptyList(), val enabled: Boolean = true, val strong: Boolean = false, val dialog: InsertDialog? = null) : MenuEntry
}

private val NO_PARAMS = JsonObject(emptyMap())

/** True when the point ([xPt], [yPt]) on [page] is inside one of the selection's boxes (page points). */
fun pointInSelection(rects: List<PageRect>, page: Int, xPt: Float, yPt: Float): Boolean =
    rects.any { r -> r.page == page && xPt >= r.x && xPt <= r.x + r.w && yPt >= r.y && yPt <= r.y + r.h }

/**
 * The menu for [data]: proofing fixes first, then the clipboard, synonyms, Font, Paragraph, link and
 * comment, and the table commands when the caret is in a table. A command the engine says cannot run
 * now is listed greyed ([state]).
 */
fun contextMenuEntries(data: ContextMenuData, state: CommandState): List<MenuEntry> = buildList {
    fun item(label: String, id: String, params: JsonObject = NO_PARAMS) = MenuEntry.Item(label, listOf(id to params), enabled = state.disabled[id] == null)
    data.issue?.let { issue ->
        add(MenuEntry.Header(if (issue.kind == "spelling") "Possible spelling mistake" else issue.message.ifBlank { "Possible mistake" }))
        if (issue.suggestions.isEmpty()) add(MenuEntry.Note("(no suggestions)"))
        for (s in issue.suggestions) add(MenuEntry.Item(s, listOf("review.applySuggestion" to buildJsonObject { put("text", s) }), strong = true))
        if (issue.kind == "spelling") {
            add(item("Ignore All", "review.ignoreAll"))
            add(item("Add to Dictionary", "review.addToDictionary"))
        }
        add(MenuEntry.Divider)
    }
    add(item("Cut", "edit.cut"))
    add(item("Copy", "edit.copy"))
    add(item("Paste", "edit.paste"))
    add(item("Keep Text Only", "edit.pasteText"))
    add(item("Merge Formatting", "edit.pasteMerge"))
    add(MenuEntry.Divider)
    if (data.synonyms.isNotEmpty()) {
        add(MenuEntry.Header("Synonyms"))
        // Replace the word under the caret: select it, then type over it.
        for (w in data.synonyms) add(MenuEntry.Item(w, listOf("select.word" to NO_PARAMS, "text.insert" to buildJsonObject { put("text", w); put("raw", true) })))
        add(MenuEntry.Divider)
    }
    add(item("Font…", "format.fontDialog"))
    add(item("Paragraph…", "para.dialog"))
    add(MenuEntry.Item("Link…", dialog = InsertDialog.Link, enabled = state.disabled["insert.link"] == null))
    add(MenuEntry.Item("New Comment", dialog = InsertDialog.NewComment, enabled = state.disabled["review.newComment"] == null))
    if ("Table Layout" in state.contextual) {
        add(MenuEntry.Divider)
        add(item("Insert Row Above", "table.insertRowAbove"))
        add(item("Insert Row Below", "table.insertRowBelow"))
        add(item("Insert Column Left", "table.insertColumnLeft"))
        add(item("Insert Column Right", "table.insertColumnRight"))
        add(item("Merge Cells", "table.merge"))
        add(item("Delete Row", "table.deleteRow"))
        add(item("Delete Column", "table.deleteColumn"))
        add(item("Delete Table", "table.deleteTable"))
    }
}

/** The context menu, as a popup anchored where the pointer or finger was. Items run through [onRun]; New Comment and Link ask first. */
@Composable
fun ContextMenu(data: ContextMenuData, state: CommandState, onRun: (String, JsonObject) -> Unit, onDismiss: () -> Unit) {
    var dialog by remember { mutableStateOf<InsertDialog?>(null) }
    dialog?.let { d -> InsertDialogView(d, { p -> dialog = null; onDismiss(); onRun(d.command, p) }, { dialog = null; onDismiss() }) }
    // While a dialog is up the menu itself is closed.
    DropdownMenu(expanded = dialog == null, onDismissRequest = onDismiss) {
        for (e in contextMenuEntries(data, state)) when (e) {
            is MenuEntry.Header -> Text(e.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            is MenuEntry.Note -> Text(e.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            MenuEntry.Divider -> HorizontalDivider()
            is MenuEntry.Item -> DropdownMenuItem(
                text = { Text(e.label, fontWeight = if (e.strong) FontWeight.Bold else FontWeight.Normal) },
                enabled = e.enabled,
                onClick = {
                    val d = e.dialog
                    if (d != null) dialog = d else { onDismiss(); e.steps.forEach { (id, p) -> onRun(id, p) } }
                },
            )
        }
    }
}
