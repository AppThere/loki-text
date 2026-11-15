package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The most levels a list has. */
internal const val LIST_LEVELS = 9

/** Number styles a level can have: the OOXML name and a sample. */
internal val NUMBER_STYLES = listOf(
    "decimal" to "1, 2, 3, …", "lowerLetter" to "a, b, c, …", "upperLetter" to "A, B, C, …",
    "lowerRoman" to "i, ii, iii, …", "upperRoman" to "I, II, III, …", "decimalZero" to "01, 02, 03, …",
    "ordinal" to "1st, 2nd, 3rd, …", "cardinalText" to "One, Two, Three, …", "bullet" to "•", "none" to "(none)",
)

/** One level of the list being defined. Positions are in points. */
internal data class LevelForm(
    val text: String = "%1.",
    val format: String = "decimal",
    val start: Int = 1,
    val restart: Boolean = true,
    /** The 1-based level it restarts after. */
    val restartAfter: Int = 1,
    /** The paragraph style linked to this level, or empty. */
    val style: String = "",
    val align: String = "left",
    val alignedAt: Float = 0f,
    val indentAt: Float = 36f,
    val follow: String = "tab",
    val tabOn: Boolean = false,
    val tabAt: Float = 36f,
    val font: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val legal: Boolean = false,
)

private fun JsonElement?.s(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
private fun JsonElement?.f(): Float? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.floatOrNull?.takeIf { it.isFinite() }

/** A level from `list.get`; [i] is its 0-based index. */
internal fun levelFrom(i: Int, v: JsonElement?): LevelForm {
    val o = v as? JsonObject ?: return LevelForm(text = "%${i + 1}.")
    val restartAfter = (o["restartAfter"] as? JsonPrimitive)?.intOrNull
    val indent = o["indentAt"].f() ?: 36f
    return LevelForm(
        text = o["text"].s(),
        format = o["format"].s().ifEmpty { "decimal" },
        start = ((o["start"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(0, 32_767),
        restart = restartAfter != 0,
        restartAfter = (restartAfter?.takeIf { it > 0 } ?: i).coerceIn(1, 8),
        style = o["style"].s(),
        align = o["align"].s().ifEmpty { "left" },
        alignedAt = o["alignedAt"].f() ?: 0f,
        indentAt = indent,
        follow = o["follow"].s().ifEmpty { "tab" },
        tabOn = o["tabAt"].f() != null,
        tabAt = o["tabAt"].f() ?: indent,
        font = o["font"].s(),
        bold = (o["bold"] as? JsonPrimitive)?.booleanOrNull == true,
        italic = (o["italic"] as? JsonPrimitive)?.booleanOrNull == true,
        legal = (o["legal"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

/** All nine levels from a `list.get` result; a missing list (the caret isn't in one) gives 1. a. i. levels. */
internal fun levelsFrom(get: JsonElement?): List<LevelForm> {
    val arr = (get as? JsonObject)?.get("levels") as? JsonArray
    if (arr == null) {
        val fmts = listOf("decimal", "lowerLetter", "lowerRoman")
        return List(LIST_LEVELS) { i -> LevelForm(text = "%${i + 1}.", format = fmts[i % 3], alignedAt = 18f + 36f * i, indentAt = 36f + 36f * i, tabAt = 36f + 36f * i) }
    }
    return List(LIST_LEVELS) { i -> levelFrom(i, arr.getOrNull(i)) }
}

private fun pt(x: Float) = if (x.isFinite()) x.coerceIn(-1584f, 1584f) else 0f

/** One level as `list.define` takes it. */
internal fun levelParams(l: LevelForm, i: Int): JsonObject = buildJsonObject {
    put("format", l.format)
    put("text", l.text)
    put("start", l.start.coerceIn(0, 32_767))
    when {
        !l.restart -> put("restartAfter", 0)
        i > 0 && l.restartAfter < i -> put("restartAfter", l.restartAfter)
        else -> put("restartAfter", JsonNull)
    }
    if (l.style.isEmpty()) put("style", JsonNull) else put("style", l.style)
    put("align", l.align)
    put("alignedAt", pt(l.alignedAt))
    put("indentAt", pt(l.indentAt))
    put("follow", l.follow)
    if (l.tabOn && l.follow == "tab") put("tabAt", pt(l.tabAt)) else put("tabAt", JsonNull)
    if (l.font.isBlank()) put("font", JsonNull) else put("font", l.font.trim())
    put("bold", l.bold)
    put("italic", l.italic)
    put("legal", l.legal)
}

/** The `list.define` params for the whole list. */
internal fun listParams(levels: List<LevelForm>): JsonObject = buildJsonObject {
    put("levels", JsonArray(levels.take(LIST_LEVELS).mapIndexed { i, l -> levelParams(l, i) }))
}

private val ONES = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen", "Twenty")

private fun letters(n: Int, upper: Boolean): String {
    if (n < 1) return n.toString()
    // Word repeats the letter: a … z, aa … zz, aaa …
    val c = (if (upper) 'A' else 'a') + (n - 1) % 26
    return c.toString().repeat((n - 1) / 26 + 1)
}

private fun roman(n: Int, upper: Boolean): String {
    if (n !in 1..3999) return n.toString()
    var k = n
    val sb = StringBuilder()
    for ((v, r) in listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc", 50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")) {
        while (k >= v) { sb.append(r); k -= v }
    }
    return if (upper) sb.toString().uppercase() else sb.toString()
}

/** [n] written in the number style [format] (the preview's sample). */
internal fun formatNumber(format: String, n: Int): String = when (format) {
    "decimal" -> n.toString()
    "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
    "lowerLetter" -> letters(n, false)
    "upperLetter" -> letters(n, true)
    "lowerRoman" -> roman(n, false)
    "upperRoman" -> roman(n, true)
    "ordinal" -> n.toString() + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    "cardinalText" -> ONES.getOrNull(n) ?: n.toString()
    else -> n.toString()
}

/** The number level [k] (0-based) shows with every level at its start value, as in the preview. */
internal fun sampleNumber(levels: List<LevelForm>, k: Int): String {
    val l = levels.getOrNull(k) ?: return ""
    if (l.format == "none") return ""
    if (l.format == "bullet") return l.text
    val sb = StringBuilder()
    var i = 0
    while (i < l.text.length) {
        val c = l.text[i]
        val d = l.text.getOrNull(i + 1)?.digitToIntOrNull()
        if (c == '%' && d != null) {
            val j = (d - 1).coerceIn(0, LIST_LEVELS - 1)
            val lv = levels.getOrNull(j)
            val fmt = if (l.legal && j != k) "decimal" else lv?.format ?: "decimal"
            sb.append(formatNumber(fmt, (lv?.start ?: 1).coerceAtLeast(1)))
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/** A small menu of [options] (value to label); [current] is the chosen value. */
@Composable
private fun Choice(label: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${options.firstOrNull { it.first == current }?.second ?: current.ifEmpty { "(none)" }}", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((v, l) in options) DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onPick(v) })
        }
    }
}

/**
 * Define New Multilevel List: nine levels, the one being edited, and a preview. [styles] are the
 * paragraph styles (id to name) a level can link to. OK hands back the `list.define` params.
 */
@Composable
internal fun DefineListDialog(start: List<LevelForm>, styles: List<Pair<String, String>>, onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var levels by remember { mutableStateOf(start) }
    var level by remember { mutableStateOf(0) }
    var unit by remember { mutableStateOf(LengthUnit.Inches) }
    var texts by remember { mutableStateOf(mapOf<String, String>()) }
    val l = levels[level]
    fun update(f: (LevelForm) -> LevelForm) { levels = levels.toMutableList().also { it[level] = f(l) } }
    fun shown(key: String, pt: Float) = texts[key] ?: lengthText(pt, unit)
    fun length(key: String, text: String, set: (LevelForm, Float) -> LevelForm) {
        texts = texts + (key to text)
        text.trim().toFloatOrNull()?.takeIf { it.isFinite() }?.let { v -> update { set(it, (v * unit.pt).coerceIn(-1584f, 1584f)) } }
    }
    fun bad(key: String) = texts[key]?.trim()?.toFloatOrNull()?.isFinite() != true && texts.containsKey(key)
    fun select(i: Int) { level = i; texts = emptyMap() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Define New Multilevel List") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until LIST_LEVELS) FilterChip(level == i, { select(i) }, label = { Text("${i + 1}") })
                }
                // Preview: every level's number at its position, the one being edited emphasised.
                Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        for (k in 0 until LIST_LEVELS) {
                            val lv = levels[k]
                            val indent = (lv.alignedAt / 6f).coerceIn(0f, 120f)
                            Text(
                                (sampleNumber(levels, k) + "  ———————").trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (k == level) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                modifier = Modifier.padding(start = indent.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((u) in LengthUnit.entries.map { it to it.label }) FilterChip(unit == u, { unit = u; texts = emptyMap() }, label = { Text(u.label) })
                }
                Text("Number format", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(l.text, { t -> update { it.copy(text = t) } }, label = { Text("Number text (%1 = level 1's number)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Choice("Style", l.format, NUMBER_STYLES.map { (k, s) -> k to s }, { k ->
                    update {
                        // A new style puts this level's number in the text if it had none (a bullet its character).
                        val text = if (k == "bullet") "•" else if (it.format == "bullet" || !it.text.contains('%')) "%${level + 1}." else it.text
                        it.copy(format = k, text = text)
                    }
                }, Modifier.fillMaxWidth())
                if (level > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Include:", style = MaterialTheme.typography.bodyMedium)
                    for (k in 0 until level) TextButton(onClick = { update { it.copy(text = it.text + "%${k + 1}") } }) { Text("Level ${k + 1}") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        l.start.toString(), { t -> t.trim().toIntOrNull()?.let { v -> update { it.copy(start = v.coerceIn(0, 32_767)) } } },
                        label = { Text("Start at") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    if (level > 0) Row(Modifier.weight(2f).clickable { update { it.copy(restart = !it.restart) } }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(l.restart, { c -> update { it.copy(restart = c) } })
                        Text("Restart after level ${l.restartAfter.coerceIn(1, level)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (level > 1 && l.restart) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in 1..level) FilterChip(l.restartAfter.coerceIn(1, level) == k, { update { it.copy(restartAfter = k) } }, label = { Text("$k") })
                }
                Choice("Link to style", l.style, listOf("" to "(no style)") + styles, { s -> update { it.copy(style = s) } }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(l.font, { t -> update { it.copy(font = t) } }, label = { Text("Font") }, singleLine = true, modifier = Modifier.weight(1f))
                    Row(Modifier.clickable { update { it.copy(bold = !it.bold) } }, verticalAlignment = Alignment.CenterVertically) { Checkbox(l.bold, { c -> update { it.copy(bold = c) } }); Text("Bold") }
                    Row(Modifier.clickable { update { it.copy(italic = !it.italic) } }, verticalAlignment = Alignment.CenterVertically) { Checkbox(l.italic, { c -> update { it.copy(italic = c) } }); Text("Italic") }
                }
                Row(Modifier.clickable { update { it.copy(legal = !it.legal) } }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(l.legal, { c -> update { it.copy(legal = c) } })
                    Text("Legal style numbering")
                }
                Text("Position (${unit.label})", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, name) in listOf("left" to "Left", "center" to "Center", "right" to "Right")) FilterChip(l.align == k, { update { it.copy(align = k) } }, label = { Text(name) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(shown("a$level", l.alignedAt), { length("a$level", it) { f, v -> f.copy(alignedAt = v) } }, label = { Text("Aligned at") }, singleLine = true, isError = bad("a$level"), modifier = Modifier.weight(1f))
                    OutlinedTextField(shown("i$level", l.indentAt), { length("i$level", it) { f, v -> f.copy(indentAt = v) } }, label = { Text("Text indent at") }, singleLine = true, isError = bad("i$level"), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, name) in listOf("tab" to "Tab", "space" to "Space", "nothing" to "Nothing")) FilterChip(l.follow == k, { update { it.copy(follow = k) } }, label = { Text("Follow: $name") })
                }
                if (l.follow == "tab") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.clickable { update { it.copy(tabOn = !it.tabOn) } }, verticalAlignment = Alignment.CenterVertically) { Checkbox(l.tabOn, { c -> update { it.copy(tabOn = c) } }); Text("Tab stop at") }
                    OutlinedTextField(shown("t$level", l.tabAt), { length("t$level", it) { f, v -> f.copy(tabAt = v) } }, enabled = l.tabOn, singleLine = true, isError = bad("t$level"), modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(enabled = texts.keys.none { bad(it) }, onClick = { onApply(listParams(levels)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The first character (a whole surrogate pair counts as one) of [text], or null when it is blank. */
internal fun firstSymbol(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return if (t[0].isHighSurrogate() && t.length > 1 && t[1].isLowSurrogate()) t.substring(0, 2) else t.substring(0, 1)
}

/** `para.defineBullet` params; null for a blank symbol. */
internal fun bulletParams(symbol: String): JsonObject? = firstSymbol(symbol)?.let { buildJsonObject { put("char", it) } }

/** `para.defineNumber` params; null when the text is blank or the start isn't a number from 0 to 100000. */
internal fun numberParams(format: String, text: String, start: String): JsonObject? {
    val n = start.trim().toIntOrNull()?.takeIf { it in 0..100_000 } ?: return null
    if (text.isBlank()) return null
    return buildJsonObject { put("format", format); put("text", text); put("start", n) }
}

/** Define New Bullet: the symbol the list uses. */
@Composable
internal fun DefineBulletDialog(onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var symbol by remember { mutableStateOf("★") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Define New Bullet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (g in listOf("•", "○", "■", "◆", "★", "✓", "➢", "–")) FilterChip(symbol == g, { symbol = g }, label = { Text(g) })
                }
                OutlinedTextField(symbol, { symbol = it.take(2) }, label = { Text("Symbol") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = bulletParams(symbol) != null, onClick = { bulletParams(symbol)?.let(onApply) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Define New Number Format: style, the text around the number, and where it starts. */
@Composable
internal fun DefineNumberDialog(onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var format by remember { mutableStateOf("decimal") }
    var text by remember { mutableStateOf("%1.") }
    var start by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Define New Number Format") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Number style", format, NUMBER_STYLES.filter { it.first != "bullet" && it.first != "none" }, { format = it }, Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("Number text (%1 = the number)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(start, { start = it }, label = { Text("Start at") }, singleLine = true, isError = numberParams(format, text, start) == null)
                Text("Preview: " + sampleNumber(listOf(LevelForm(text = text, format = format, start = start.trim().toIntOrNull() ?: 1)), 0), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(enabled = numberParams(format, text, start) != null, onClick = { numberParams(format, text, start)?.let(onApply) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** `para.setNumberingValue` params; null unless [value] is a whole number from 0 to 100000. */
internal fun numberingValueParams(value: String): JsonObject? =
    value.trim().toIntOrNull()?.takeIf { it in 0..100_000 }?.let { buildJsonObject { put("value", it) } }

/** Set Numbering Value: the number this item and the ones after it continue from. */
@Composable
internal fun NumberingValueDialog(onApply: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Numbering Value") },
        text = { OutlinedTextField(value, { value = it }, label = { Text("Set value to") }, singleLine = true, isError = numberingValueParams(value) == null) },
        confirmButton = { TextButton(enabled = numberingValueParams(value) != null, onClick = { numberingValueParams(value)?.let(onApply) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
