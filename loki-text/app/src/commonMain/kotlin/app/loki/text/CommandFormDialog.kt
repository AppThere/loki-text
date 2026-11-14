package app.loki.text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Colours the form offers for a colour field: our own small palette, as `RRGGBB`. */
internal val FORM_COLORS = listOf(
    "000000", "595959", "A6A6A6", "FFFFFF", "C00000", "E97132", "FFC000", "4EA72E", "0F9ED5", "004F93", "7030A0", "D63384",
)

/**
 * The frame every app dialog of this kind shares: as wide as a phone allows and no wider than a
 * form reads well, with the body scrolling between a fixed title and fixed buttons, so the buttons
 * are in reach on a short screen (a phone in landscape, or with the keyboard up).
 */
@Composable
internal fun SheetDialog(
    title: String,
    subtitle: String = "",
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    maxWidth: Int = 560,
    body: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(12.dp).widthIn(max = maxWidth.dp).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(top = 16.dp)) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                }
                Box(Modifier.weight(1f, fill = false).padding(top = 12.dp)) { body() }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) { buttons() }
            }
        }
    }
}

/** `RRGGBB` as a colour, or null. */
internal fun formColor(hex: String): Color? =
    hex.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { Color(0xFF000000.toInt() or it) }

/**
 * The form for a command that needs values: one control per parameter of its params doc, a box
 * for raw JSON when the doc's shape is beyond the form, and the doc's own notes. [run] executes
 * the command and returns why it failed, or null; the form stays open on a failure so the values
 * can be corrected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommandFormDialog(command: CommandInfo, run: suspend (JsonObject) -> String?, onDismiss: () -> Unit) {
    val form = remember(command.id) { parseParamForm(command.params) }
    val values = remember(command.id) { mutableStateMapOf<String, String>() }
    var raw by remember(command.id) { mutableStateOf("") }
    var showRaw by remember(command.id) { mutableStateOf(form.fields.isEmpty()) }
    var error by remember(command.id) { mutableStateOf<String?>(null) }
    var busy by remember(command.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        val params = buildParams(form, values, if (showRaw) raw else "").getOrElse { error = it.message; return }
        busy = true
        scope.launch {
            val failed = run(params)
            busy = false
            if (failed == null) onDismiss() else error = failed
        }
    }
    SheetDialog(
        command.label, command.location, onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = ::submit, enabled = !busy) { Text("OK") }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (f in form.fields) {
                val value = values[f.name].orEmpty()
                fun set(v: String) { values[f.name] = v; error = null }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val name = fieldLabel(f.name) + if (f.optional || form.alternatives) "" else " *"
                    when (f.kind) {
                        FieldKind.Bool, FieldKind.Choice -> {
                            Text(name, style = MaterialTheme.typography.labelLarge)
                            val choices = if (f.kind == FieldKind.Bool) listOf("true" to "Yes", "false" to "No") else f.options.map { it.text to optionLabel(it) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Tapping the chosen one again clears it, which leaves an optional key out.
                                for ((v, label) in choices) FilterChip(value == v, { set(if (value == v) "" else v) }, label = { Text(label) })
                            }
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (f.kind == FieldKind.Color) ColorDot(formColor(value))
                                OutlinedTextField(
                                    value, ::set,
                                    label = { Text(name) },
                                    singleLine = f.kind != FieldKind.Json && f.name != "text",
                                    keyboardOptions = if (f.kind == FieldKind.Number && f.options.isEmpty()) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                                    placeholder = { Text(placeholder(f)) },
                                    modifier = Modifier.weight(1f),
                                )
                                if (f.kind == FieldKind.Path) TextButton({
                                    scope.launch {
                                        val picked = withContext(Dispatchers.Default) { pickFile(save = writesFile(command.id)) }
                                        if (picked != null) set(picked)
                                    }
                                }) { Text("Browse…") }
                            }
                            if (f.kind == FieldKind.Color) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (hex in FORM_COLORS) ColorDot(formColor(hex), selected = value.equals(hex, ignoreCase = true)) { set(hex) }
                            }
                            if (f.options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (o in f.options) FilterChip(value == o.text, { set(if (value == o.text) "" else o.text) }, label = { Text(optionLabel(o)) })
                            }
                        }
                    }
                    if (f.hint.isNotEmpty()) Text(f.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (showRaw) {
                OutlinedTextField(
                    raw, { raw = it; error = null },
                    label = { Text("Parameters as JSON") }, placeholder = { Text("{ }") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            } else TextButton({ showRaw = true }) { Text("Enter parameters as JSON…") }
            if (form.notes.isNotEmpty() || showRaw) {
                SelectionContainer { Text(if (showRaw) command.params.take(1200) else form.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** A value as a chip names it: the engine's word made readable, `null` as None. */
private fun optionLabel(o: ParamOption): String = if (!o.quoted && o.text == "null") "None" else if (o.quoted) fieldLabel(o.text) else o.text

private fun placeholder(f: ParamField): String = when (f.kind) {
    FieldKind.Number -> "0"
    FieldKind.Color -> "RRGGBB"
    FieldKind.Json -> "JSON"
    FieldKind.Path -> "File"
    else -> ""
}

/** Does a command write the file its `path` names (so the picker asks where to save), rather than read it? */
internal fun writesFile(id: String): Boolean = id.startsWith("file.export") || id.startsWith("file.save") || id == "file.printPdf"

@Composable
private fun ColorDot(color: Color?, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Surface(
        shape = CircleShape,
        color = color ?: Color.Transparent,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(28.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {}
}

/**
 * What a command answered, for commands that report rather than change (Word Count, Check
 * Compatibility, Document Properties…). [onChange], when set, opens the command's form: the same
 * command with parameters is how those settings are changed.
 */
@Composable
internal fun ResultDialog(command: CommandInfo, result: JsonElement?, onChange: (() -> Unit)?, onDismiss: () -> Unit) {
    val lines = remember(result) { describeResult(result) }
    SheetDialog(
        command.label, command.location, onDismiss,
        buttons = {
            if (onChange != null) TextButton(onClick = onChange) { Text("Change…") }
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    ) {
        SelectionContainer {
            LazyColumn(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).heightIn(max = 520.dp)) {
                items(lines) { l ->
                    Row(Modifier.fillMaxWidth().padding(start = (l.depth.coerceIn(0, 8) * 14).dp, top = 2.dp, bottom = 2.dp)) {
                        if (l.name.isNotEmpty()) {
                            Text(l.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (l.value.isEmpty()) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.widthIn(max = 200.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(l.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
