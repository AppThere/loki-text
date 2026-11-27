package com.appthere.loki.text

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.appthere.loki.kit.CommandInfo
import com.appthere.loki.kit.SheetDialog
import com.appthere.loki.kit.chordOf
import com.appthere.loki.kit.chordText
import com.appthere.loki.kit.displayShortcut
import com.appthere.loki.kit.isShortcutChord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The person's own key changes: each command whose keys they changed, with the keys it has now (none when they took them all away). */
internal data class KeyCustom(val commands: List<Pair<String, List<String>>>)

/**
 * What `tools.customizeKeyboard` reports. The engine answers `{custom: [{command, keys}]}`; its params doc describes
 * `{custom: {assigned: {key: id}, removed: [key]}}`, which is read too. Anything else is no changes.
 */
internal fun parseKeyCustom(r: JsonElement?): KeyCustom {
    fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull
    return when (val custom = (r as? JsonObject)?.get("custom")) {
        is JsonArray -> KeyCustom(custom.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = str(o["command"]) ?: return@mapNotNull null
            id to (o["keys"] as? JsonArray).orEmpty().mapNotNull { str(it) }
        }.sortedBy { it.first })
        is JsonObject -> KeyCustom((custom["assigned"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> str(v)?.let { it to k } }
            .groupBy({ it.first }, { it.second }).map { (id, keys) -> id to keys.sorted() }.sortedBy { it.first })
        else -> KeyCustom(emptyList())
    }
}

/** A key as the engine writes it (`Cmd+Shift+K`, `Mod+B`) as this platform names it. */
internal fun keyLabel(key: String) = displayShortcut(key.replace("Cmd+", "Mod+"))

/** Commands whose name or id contains [query] (all words of it, any order), the closest first; at most [limit]. */
internal fun findCommands(all: Collection<CommandInfo>, query: String, limit: Int = 8): List<CommandInfo> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    return all.filter { c -> words.all { w -> c.label.lowercase().contains(w) || c.id.lowercase().contains(w) } }
        .sortedWith(compareBy({ !it.label.lowercase().startsWith(words.first()) }, { it.label.length }))
        .take(limit)
}

/** The `tools.customizeKeyboard` arguments for giving [key] to [command]. */
internal fun assignParams(command: String, key: String) = buildJsonObject { put("assign", buildJsonObject { put("command", command); put("key", key) }) }

/** The `tools.customizeKeyboard` arguments for taking [key] away from [command]. */
internal fun removeParams(command: String, key: String) = buildJsonObject { put("remove", buildJsonObject { put("command", command); put("key", key) }) }

/**
 * Customize Keyboard: the keys given to commands, a command to find and a key to press for it, and resetting
 * the built-in keys. [run] sends `tools.customizeKeyboard` arguments and returns the new state, or why it failed.
 */
@Composable
internal fun KeyboardDialog(
    start: KeyCustom,
    catalog: Map<String, CommandInfo>,
    run: suspend (JsonObject) -> Result<KeyCustom>,
    launch: (suspend () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf(start) }
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<CommandInfo?>(null) }
    var key by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val keyBox = remember { FocusRequester() }
    var error by remember { mutableStateOf<String?>(null) }
    fun send(params: JsonObject, after: () -> Unit = {}) = launch {
        run(params).onSuccess { custom = it; error = null; after() }.onFailure { error = it.message }
    }
    fun label(id: String) = catalog[id]?.label ?: id
    SheetDialog("Customize keyboard", "Keys you give to commands", onDismiss, buttons = { TextButton(onClick = onDismiss) { Text("Close") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Give a key to a command", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(query, { query = it; chosen = null }, singleLine = true, label = { Text("Find a command") }, modifier = Modifier.fillMaxWidth())
            if (chosen == null) for (c in findCommands(catalog.values, query)) {
                Text(
                    "${c.label}  ·  ${c.location}", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { chosen = c; query = c.label }.padding(vertical = 6.dp),
                )
            }
            chosen?.let { c ->
                Text("Command: ${c.label}" + if (c.shortcut.isNotEmpty()) "  (now ${c.shortcut.split(" / ").joinToString(", ") { keyLabel(it) }})" else "", style = MaterialTheme.typography.bodyMedium)
            }
            // Press the key here; or type it as the engine writes one (Mod+Shift+K).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).border(if (listening) 2.dp else 1.dp, if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        .focusRequester(keyBox)
                        .clickable { keyBox.requestFocus() }
                        .onFocusChanged { listening = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            val c = chordOf(e) ?: return@onPreviewKeyEvent false
                            if (!isShortcutChord(c)) return@onPreviewKeyEvent false
                            chordText(c)?.let { key = it }
                            true
                        }
                        .focusable().padding(12.dp)
                        .semantics { contentDescription = "Shortcut key: press the keys" },
                ) { Text(key.ifEmpty { if (listening) "Press the keys…" else "Click here, then press the keys" }) }
                OutlinedTextField(key, { key = it.trim() }, singleLine = true, label = { Text("Or type it") }, modifier = Modifier.weight(1f))
            }
            TextButton(
                onClick = { chosen?.let { c -> send(assignParams(c.id, key)) { key = "" } } },
                enabled = chosen != null && key.isNotBlank(),
            ) { Text("Assign") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            Text("Commands whose keys you changed", style = MaterialTheme.typography.titleSmall)
            if (custom.commands.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            for ((id, keys) in custom.commands) {
                Text(label(id), style = MaterialTheme.typography.bodyLarge)
                if (keys.isEmpty()) Text("No keys", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                for (k in keys) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(keyLabel(k), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 16.dp))
                    TextButton({ send(removeParams(id, k)) }) { Text("Remove") }
                }
            }
            TextButton({ send(buildJsonObject { put("reset", true) }) }) { Text("Reset the built-in keys") }
        }
    }
}
