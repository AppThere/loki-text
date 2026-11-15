package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** What the bar is for. */
internal enum class FindMode { Find, Replace, GoTo }

/** The bar's fields; kept in the app so a menu command or shortcut can open it with the same state. */
internal data class FindState(
    val mode: FindMode = FindMode.Find,
    val query: String = "",
    val with: String = "",
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
    val goTarget: String = "page",
    val goValue: String = "",
)

/** The options every find/replace command takes. */
internal fun findParams(s: FindState, withReplacement: Boolean = false): JsonObject = buildJsonObject {
    put("text", s.query)
    if (s.matchCase) put("matchCase", true)
    if (s.wholeWord) put("wholeWord", true)
    if (s.regex) put("regex", true)
    if (withReplacement) put("with", s.with)
}

/** `edit.goto` params: a page or paragraph number, or a bookmark name. Null when the value is unusable. */
internal fun goParams(s: FindState): JsonObject? {
    val v = s.goValue.trim()
    if (v.isEmpty()) return null
    return when (s.goTarget) {
        "bookmark" -> buildJsonObject { put("bookmark", v) }
        else -> {
            val n = v.toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return null
            buildJsonObject { put(s.goTarget, n) }
        }
    }
}

/** "3 of 12", "No matches" or blank, from `edit.findNext`/`edit.advancedFind` results. */
internal fun matchText(result: JsonElement?): String {
    val o = result as? JsonObject ?: return ""
    val count = (o["count"] as? JsonPrimitive)?.intOrNull ?: return ""
    if (count == 0) return "No matches"
    val index = (o["index"] as? JsonPrimitive)?.intOrNull
    return if (index != null) "${index + 1} of $count" else if (count == 1) "1 match" else "$count matches"
}

/**
 * Find, Replace and Go To in a bar above the page, so the document stays visible and editable
 * behind it. [run] calls an engine command and returns its result or the failure.
 */
@Composable
internal fun FindBar(
    state: FindState,
    onState: (FindState) -> Unit,
    run: suspend (String, JsonObject) -> Result<JsonElement?>,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(state.mode) { runCatching { focus.requestFocus() } }

    // Live count and highlighting as the query or its options change.
    LaunchedEffect(state.query, state.matchCase, state.wholeWord, state.regex, state.mode) {
        if (state.mode == FindMode.GoTo) return@LaunchedEffect
        if (state.query.isEmpty()) {
            message = ""
            run("edit.advancedFind", buildJsonObject { put("highlight", false) })
            return@LaunchedEffect
        }
        delay(200)
        val r = run("edit.advancedFind", findParams(state).let { p -> JsonObject(p + ("highlight" to JsonPrimitive(true))) })
        message = r.fold({ matchText(it) }, { it.message ?: "Search failed" })
    }

    fun step(forward: Boolean) = scope.launch {
        if (state.query.isEmpty()) return@launch
        message = run(if (forward) "edit.findNext" else "edit.findPrevious", findParams(state))
            .fold({ matchText(it) }, { if (it.message?.contains("no matches") == true) "No matches" else it.message ?: "Search failed" })
    }

    fun go() = scope.launch {
        val p = goParams(state) ?: run { message = "Enter a number"; return@launch }
        message = run("edit.goto", p).fold({ "" }, { it.message ?: "Not found" })
    }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((m, label) in listOf(FindMode.Find to "Find", FindMode.Replace to "Replace", FindMode.GoTo to "Go To")) {
                    FilterChip(state.mode == m, { onState(state.copy(mode = m)) }, label = { Text(label) })
                }
                Text(message, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Lucide.X, contentDescription = "Close", modifier = Modifier.size(18.dp)) }
            }
            if (state.mode == FindMode.GoTo) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, label) in listOf("page" to "Page", "paragraph" to "Paragraph", "bookmark" to "Bookmark")) {
                        FilterChip(state.goTarget == id, { onState(state.copy(goTarget = id)) }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        state.goValue, { onState(state.copy(goValue = it)) }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(focus),
                        label = { Text(if (state.goTarget == "bookmark") "Bookmark name" else "Number") },
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    )
                    TextButton(onClick = { go() }) { Text("Go") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedTextField(
                        state.query, { onState(state.copy(query = it)) }, singleLine = true, label = { Text("Find") },
                        modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { step(!e.isShiftPressed); true } else false
                        },
                    )
                    IconButton(onClick = { step(false) }, modifier = Modifier.size(36.dp)) { Icon(Lucide.ChevronUp, contentDescription = "Previous match") }
                    IconButton(onClick = { step(true) }, modifier = Modifier.size(36.dp)) { Icon(Lucide.ChevronDown, contentDescription = "Next match") }
                }
                if (state.mode == FindMode.Replace) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(state.with, { onState(state.copy(with = it)) }, singleLine = true, label = { Text("Replace with") }, modifier = Modifier.weight(1f))
                        TextButton(enabled = state.query.isNotEmpty(), onClick = {
                            scope.launch {
                                message = run("edit.replace", findParams(state, true))
                                    .fold({ (it as? JsonObject)?.get("remaining")?.let { r -> "${(r as? JsonPrimitive)?.intOrNull ?: 0} left" } ?: "" }, { it.message ?: "Replace failed" })
                            }
                        }) { Text("Replace") }
                        TextButton(enabled = state.query.isNotEmpty(), onClick = {
                            scope.launch {
                                message = run("edit.replaceAll", findParams(state, true)).fold(
                                    { val n = ((it as? JsonObject)?.get("replaced") as? JsonPrimitive)?.intOrNull ?: 0; if (n == 1) "1 replacement" else "$n replacements" },
                                    { it.message ?: "Replace failed" },
                                )
                            }
                        }) { Text("All") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(state.matchCase, { onState(state.copy(matchCase = !state.matchCase)) }, label = { Text("Match case") })
                    FilterChip(state.wholeWord, { onState(state.copy(wholeWord = !state.wholeWord)) }, label = { Text("Whole word") })
                    FilterChip(state.regex, { onState(state.copy(regex = !state.regex)) }, label = { Text("Regex") })
                }
            }
        }
    }
}
