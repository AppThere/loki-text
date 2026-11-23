package app.loki.kit

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
val FORM_COLORS = listOf(
    "000000", "595959", "A6A6A6", "FFFFFF", "C00000", "E97132", "FFC000", "4EA72E", "0F9ED5", "004F93", "7030A0", "D63384",
)

/**
 * The frame every app dialog of this kind shares: as wide as a phone allows and no wider than a
 * form reads well, with the body scrolling between a fixed title and fixed buttons, so the buttons
 * are in reach on a short screen (a phone in landscape, or with the keyboard up).
 */
@Composable
fun SheetDialog(
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
fun formColor(hex: String): Color? =
    hex.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { Color(0xFF000000.toInt() or it) }

/**
 * The form for a command that needs values: one control per parameter of its params doc, a box
 * for raw JSON when the doc's shape is beyond the form, and the doc's own notes. [run] executes
 * the command and returns why it failed, or null; the form stays open on a failure so the values
 * can be corrected. The platform's file picker comes in as [pickFile], so the kit needs none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandFormDialog(
    command: CommandInfo,
    /** Asks the person for a file: one to read, or (with `save`) a place to write. Null when cancelled. May block; it is called off the UI thread. */
    pickFile: (save: Boolean) -> String?,
    /** Does this command write the file its path names, rather than read it? */
    writesFile: (id: String) -> Boolean,
    run: suspend (JsonObject) -> String?,
    onDismiss: () -> Unit,
    /** The document's current values by field name, to start the form with (fields the command doesn't have are ignored). */
    initial: Map<String, String> = emptyMap(),
) {
    val form = remember(command.id) { parseParamForm(command.params) }
    val values = remember(command.id) {
        mutableStateMapOf<String, String>().also { m -> for (f in form.fields) initial[f.name]?.let { m[f.name] = it } }
    }
    var raw by remember(command.id) { mutableStateOf("") }
    var showRaw by remember(command.id) { mutableStateOf(form.fields.isEmpty()) }
    var error by remember(command.id) { mutableStateOf<String?>(null) }
    var busy by remember(command.id) { mutableStateOf(false) }
    // What a structured field's rows say is wrong (a required key blank, a bad number), by field: OK waits until they are put right.
    val fieldErrors = remember(command.id) { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (!showRaw) fieldErrors.entries.firstOrNull()?.let { (k, v) -> error = "${fieldLabel(k)}: $v"; return }
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
                val shape = remember(command.id, f.name) { shapeOf(f, form.defs) }
                FieldControl(
                    f, values[f.name].orEmpty(), required = !(f.optional || form.alternatives), shape = shape,
                    set = { v, problem -> values[f.name] = v; if (problem == null) fieldErrors.remove(f.name) else fieldErrors[f.name] = problem; error = null },
                    browse = { picked -> scope.launch { withContext(Dispatchers.Default) { pickFile(writesFile(command.id)) }?.let(picked) } },
                )
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
internal fun optionLabel(o: ParamOption): String = if (!o.quoted && o.text == "null") "None" else if (o.quoted) fieldLabel(o.text) else o.text

private fun placeholder(f: ParamField): String = when (f.kind) {
    FieldKind.Number -> "0"
    FieldKind.Color -> "RRGGBB"
    FieldKind.Json -> "JSON"
    FieldKind.Path -> "File"
    else -> ""
}

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
fun ResultDialog(command: CommandInfo, result: JsonElement?, onChange: (() -> Unit)?, onDismiss: () -> Unit) {
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


/**
 * One parameter's control: chips for a choice or yes/no, a box (with swatches for a colour, Browse for a path)
 * for a value, and for an object or a list of [shape] its own fields or rows. [set] gets the new text and, for a
 * structured value, what is wrong with it (null when it is fine). [browse] asks for a file and hands back its path.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldControl(
    f: ParamField,
    value: String,
    required: Boolean,
    shape: ParamShape?,
    set: (String, String?) -> Unit,
    browse: ((String) -> Unit) -> Unit,
    label: String = fieldLabel(f.name) + if (required) " *" else "",
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            shape != null -> StructuredField(label, shape, value, set, browse)
            f.kind == FieldKind.Bool || f.kind == FieldKind.Choice -> {
                Text(label, style = MaterialTheme.typography.labelLarge)
                val choices = if (f.kind == FieldKind.Bool) listOf("true" to "Yes", "false" to "No") else f.options.map { it.text to optionLabel(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Tapping the chosen one again clears it, which leaves an optional key out.
                    for ((v, text) in choices) FilterChip(value == v, { set(if (value == v) "" else v, null) }, label = { Text(text) })
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (f.kind == FieldKind.Color) ColorDot(formColor(value))
                    OutlinedTextField(
                        value, { set(it, null) },
                        label = { Text(label) },
                        singleLine = f.kind != FieldKind.Json && f.name != "text",
                        keyboardOptions = if (f.kind == FieldKind.Number && f.options.isEmpty()) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                        placeholder = { Text(placeholder(f)) },
                        modifier = Modifier.weight(1f),
                    )
                    if (f.kind == FieldKind.Path) TextButton({ browse { set(it, null) } }) { Text("Browse…") }
                }
                if (f.kind == FieldKind.Color) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (hex in FORM_COLORS) ColorDot(formColor(hex), selected = value.equals(hex, ignoreCase = true)) { set(hex, null) }
                }
                if (f.options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in f.options) FilterChip(value == o.text, { set(if (value == o.text) "" else o.text, null) }, label = { Text(optionLabel(o)) })
                }
            }
        }
        if (f.hint.isNotEmpty()) Text(f.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * An object or list parameter as fields and rows, with a switch to its JSON. [value] is the value as JSON text;
 * [set] gets the new JSON text (blank to leave the key out) and what is wrong, if anything.
 */
@Composable
private fun StructuredField(label: String, shape: ParamShape, value: String, set: (String, String?) -> Unit, browse: ((String) -> Unit) -> Unit) {
    var asJson by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        TextButton({ asJson = !asJson }) { Text(if (asJson) "Edit as fields" else "Edit as JSON") }
    }
    if (asJson) {
        OutlinedTextField(
            value, { t -> set(t, if (t.isBlank() || parseJsonOrNull(t) != null) null else "needs JSON") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(10.dp)) { ShapeEditor(shape, value, set, browse) }
    }
}

/** The editor for one [shape], working on its value as JSON text (a scalar item on its own text). */
@Composable
private fun ShapeEditor(shape: ParamShape, value: String, set: (String, String?) -> Unit, browse: ((String) -> Unit) -> Unit) {
    when (shape) {
        is ParamShape.Scalar -> FieldControl(shape.field, value, required = false, shape = null, set = set, browse = browse, label = "Value")
        is ParamShape.Obj -> if (isChartData(shape.form)) ChartDataGrid(value, set) else ObjectEditor(shape.form, value, set, browse)
        is ParamShape.ListOf -> ListEditor(shape.item, value, set, browse)
        is ParamShape.Tuple -> TupleEditor(shape, value, set)
    }
}

@Composable
private fun ObjectEditor(form: ParamForm, value: String, set: (String, String?) -> Unit, browse: ((String) -> Unit) -> Unit) {
    // The drafts are kept here, so a half-typed field stays as typed while the value it makes is not yet valid.
    val texts = remember { mutableStateMapOf<String, String>().apply { putAll(objectTexts(form, parseJsonOrNull(value))) } }
    val problems = remember { mutableStateMapOf<String, String>() }
    fun publish() {
        val inner = problems.entries.firstOrNull()?.let { (k, v) -> "${fieldLabel(k)}: $v" }
        val built = buildObject(form, texts)
        set(built.getOrNull()?.toString().orEmpty(), inner ?: built.exceptionOrNull()?.message)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in form.fields) {
            val shape = remember(f.name) { shapeOf(f, form.defs) }
            FieldControl(
                f, texts[f.name].orEmpty(), required = !f.optional, shape = shape,
                set = { v, problem -> texts[f.name] = v; if (problem == null) problems.remove(f.name) else problems[f.name] = problem; publish() },
                browse = browse,
            )
        }
    }
}

@Composable
private fun ListEditor(item: ParamShape, value: String, set: (String, String?) -> Unit, browse: ((String) -> Unit) -> Unit) {
    // Each row has a key of its own, so removing one doesn't hand its drafts to the next.
    var next by remember { mutableStateOf(0) }
    val rows = remember { androidx.compose.runtime.mutableStateListOf<Pair<Int, String>>().apply { listTexts(parseJsonOrNull(value)).forEach { add(next++ to it) } } }
    val problems = remember { mutableStateMapOf<Int, String>() }
    fun publish() {
        val inner = rows.indexOfFirst { it.first in problems }.takeIf { it >= 0 }?.let { i -> "item ${i + 1}: ${problems[rows[i].first]}" }
        val built = buildList(item, rows.map { it.second })
        set(built.getOrNull()?.toString().orEmpty(), inner ?: built.exceptionOrNull()?.message)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rows.isEmpty()) Text("None", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        rows.forEachIndexed { i, (key, text) ->
            androidx.compose.runtime.key(key) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp).width(22.dp))
                    Box(Modifier.weight(1f)) {
                        ShapeEditor(item, text, { v, problem ->
                            val at = rows.indexOfFirst { it.first == key }
                            if (at >= 0) rows[at] = key to v
                            if (problem == null) problems.remove(key) else problems[key] = problem
                            publish()
                        }, browse)
                    }
                    Column {
                        TextButton({ rows.removeAll { it.first == key }; problems.remove(key); publish() }) { Text("Remove") }
                        if (i > 0) TextButton({ val r = rows.removeAt(i); rows.add(i - 1, r); publish() }) { Text("Up") }
                    }
                }
                if (item !is ParamShape.Scalar) HorizontalDivider()
            }
        }
        TextButton({ rows.add(next++ to ""); publish() }) { Text("Add") }
    }
}

@Composable
private fun TupleEditor(tuple: ParamShape.Tuple, value: String, set: (String, String?) -> Unit) {
    val texts = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(listTexts(parseJsonOrNull(value))); while (size < tuple.fields.size) add("") } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tuple.fields.forEachIndexed { i, f ->
            OutlinedTextField(
                texts[i], { t ->
                    texts[i] = t
                    val built = buildTuple(tuple, texts)
                    set(built.getOrNull()?.toString().orEmpty(), built.exceptionOrNull()?.message)
                },
                label = { Text(f.name.replaceFirstChar { it.uppercase() }) }, singleLine = true,
                keyboardOptions = if (f.kind == FieldKind.Number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
