package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a parameter takes, which picks the control the form draws for it. */
enum class FieldKind { Bool, Number, Text, Choice, Color, Path, Json }

/** A value the engine's params doc names for a field. [quoted] is a string (`"auto"`); otherwise a literal (`null`, `1.5`). */
class ParamOption(val text: String, val quoted: Boolean)

/** One parameter of a command, read from its params doc. */
class ParamField(
    val name: String,
    val optional: Boolean,
    val kind: FieldKind,
    /** The values the doc names: every value of a [FieldKind.Choice], suggestions for the other kinds. */
    val options: List<ParamOption>,
    /** What the doc says about the field, in its own words. */
    val hint: String,
)

/**
 * A command's parameters as a form. [alternatives] is set when the doc gives more than one shape
 * (`{…} | {…}`): the form shows the first, nothing is required, and the raw JSON box takes the rest.
 * [notes] is what the doc says after the parameters (what the command returns, its rules).
 */
class ParamForm(val fields: List<ParamField>, val alternatives: Boolean, val notes: String)

/** The most text a hint or a note keeps; a params doc can run to pages. */
private const val MAX_HINT = 240
private const val MAX_NOTES = 600

/** Unquoted type words that mean a number. */
private val NUMBER_WORDS = setOf("pt", "n", "number", "percent", "%", "deg", "int", "px", "twips", "emu", "index")

/** Field names that hold a file or folder path. */
private fun isPathName(name: String) = name == "path" || name == "dir" || name.endsWith("Path") || name.endsWith("Folder")

/** The index after the bracket that closes the one at [open], or -1; strings are skipped. */
private fun matchingClose(s: String, open: Int): Int {
    var depth = 0
    var inString = false
    var i = open
    while (i < s.length) {
        val c = s[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else when (c) {
            '"' -> inString = true
            '{', '[', '(' -> depth++
            '}', ']', ')' -> { depth--; if (depth == 0) return i }
        }
        i++
    }
    return -1
}

/** [s] cut at each [sep] that is outside brackets and strings. */
private fun splitTopLevel(s: String, sep: Char): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var inString = false
    var start = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else when (c) {
            '"' -> inString = true
            '{', '[', '(' -> depth++
            '}', ']', ')' -> if (depth > 0) depth--
            sep -> if (depth == 0) { out += s.substring(start, i); start = i + 1 }
        }
        i++
    }
    out += s.substring(start.coerceAtMost(s.length))
    return out
}

/** [s] without its top-level parentheticals, and what they said. */
private fun withoutParentheticals(s: String): Pair<String, String> {
    val core = StringBuilder()
    val said = ArrayList<String>()
    var i = 0
    var inString = false
    while (i < s.length) {
        val c = s[i]
        if (inString) {
            core.append(c)
            if (c == '"') inString = false
        } else if (c == '"') {
            inString = true
            core.append(c)
        } else if (c == '(') {
            val close = matchingClose(s, i)
            if (close < 0) { said += s.substring(i + 1); break }
            said += s.substring(i + 1, close)
            i = close
        } else core.append(c)
        i++
    }
    return core.toString() to said.joinToString("; ")
}

private fun isNumberLiteral(s: String) = s.toDoubleOrNull() != null

/** Does an unquoted type word mean a number (`pt`, `0-8`, `percent`, `non-negative JSON integer`)? */
private fun isNumberWord(word: String): Boolean {
    val w = word.trim()
    if (w.isEmpty()) return false
    val first = w.substringBefore(' ').substringBefore('|').lowercase()
    if (first in NUMBER_WORDS) return true
    if (Regex("^-?\\d+(\\.\\d+)?\\s*(-|–|\\.\\.)\\s*-?\\d+.*").matches(w)) return true
    return "integer" in w.lowercase() || w.lowercase().startsWith("px ")
}

/** The field for [name] with the doc's [typeText] (everything after the colon). */
private fun classify(name: String, optional: Boolean, typeText: String): ParamField {
    val (core, said) = withoutParentheticals(typeText)
    val hint = said.trim().take(MAX_HINT)
    val alts = splitTopLevel(core, '|').map { it.trim() }.filter { it.isNotEmpty() }
    val options = ArrayList<ParamOption>()
    val words = ArrayList<String>()
    for (a in alts) {
        if (a.length >= 2 && a.startsWith("\"") && a.endsWith("\"")) {
            // One string can list its values: "left|center|right".
            a.substring(1, a.length - 1).split('|').map { it.trim() }.filter { it.isNotEmpty() }.forEach { options += ParamOption(it, true) }
        } else if (a == "null" || a == "true" || a == "false" || isNumberLiteral(a)) {
            options += ParamOption(a, false)
        } else words += a
    }
    val open = options.any { it.text.endsWith("…") || it.text.endsWith("...") }
    val named = options.filter { !(it.quoted && (it.text == "RRGGBB" || it.text.endsWith("…") || it.text.endsWith("..."))) }
    val first = words.firstOrNull().orEmpty()
    val kind = when {
        options.any { it.quoted && it.text == "RRGGBB" } -> FieldKind.Color
        first == "bool" || (words.isEmpty() && options.isNotEmpty() && options.all { !it.quoted && (it.text == "true" || it.text == "false") }) -> FieldKind.Bool
        isPathName(name) && (first.isEmpty() || first.startsWith("string")) && options.isEmpty() -> FieldKind.Path
        words.isEmpty() && named.isNotEmpty() && !open -> FieldKind.Choice
        isNumberWord(first) -> FieldKind.Number
        first.startsWith("[") || first.startsWith("{") || first.firstOrNull()?.isUpperCase() == true || first == "pos" -> FieldKind.Json
        else -> FieldKind.Text
    }
    return ParamField(name, optional, kind, if (kind == FieldKind.Bool) emptyList() else named, hint)
}

private val KEY = Regex("^\\s*\"([^\"]+)\"\\s*(\\?)?\\s*(?::\\s*(.*))?$", RegexOption.DOT_MATCHES_ALL)
private val KEY_GROUP = Regex("^\\s*((?:\"\\w+\"\\s*\\|\\s*)+\"\\w+\")\\s*(\\?)?\\s*:\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
private val NEXT_KEY = Regex("^\\s*\"(\\w+)\"\\s*(\\?)?\\s*:(.*)$", RegexOption.DOT_MATCHES_ALL)

/**
 * Reads a command's params doc (`{"size": pt, "name"?: string} (notes)`) into a form. The docs are
 * prose written for people, so anything this can't read becomes a text field or is left to the
 * form's raw JSON box; it never fails.
 */
fun parseParamForm(doc: String): ParamForm {
    val p = doc.trim()
    if (!p.startsWith("{")) return ParamForm(emptyList(), false, p.take(MAX_NOTES))
    val close = matchingClose(p, 0)
    if (close < 0) return ParamForm(emptyList(), false, p.take(MAX_NOTES))
    val body = p.substring(1, close)
    val rest = p.substring(close + 1).trim()
    var alternatives = Regex("^\\|\\s*\\{").containsMatchIn(rest)
    val fields = ArrayList<ParamField>()
    for (segment in splitTopLevel(body, ',')) {
        if (segment.isBlank()) continue
        val group = KEY_GROUP.find(segment)
        if (group != null) {
            // "a"|"b"|"c"?: bool — several keys of one type.
            val names = Regex("\"(\\w+)\"").findAll(group.groupValues[1]).map { it.groupValues[1] }.toList()
            for (n in names) fields += classify(n, true, group.groupValues[3])
            continue
        }
        val m = KEY.find(segment) ?: continue
        val name = m.groupValues[1]
        var type = m.groupValues[3]
        var optional = m.groupValues[2] == "?"
        // `"delta": pt | "percent": number` — one of several keys; none is required on its own.
        val more = ArrayList<Pair<String, String>>()
        // Only at the top level: `{"top"|"right": {…}}` inside a value is that value's own shape.
        val parts = splitTopLevel(type, '|')
        val firstKey = parts.indexOfFirst { NEXT_KEY.matches(it) }
        if (firstKey > 0) {
            optional = true
            type = parts.take(firstKey).joinToString("|")
            var at = firstKey
            while (at < parts.size) {
                val m2 = NEXT_KEY.find(parts[at]) ?: break
                var end = at + 1
                while (end < parts.size && !NEXT_KEY.matches(parts[end])) end++
                more += m2.groupValues[1] to (listOf(m2.groupValues[3]) + parts.subList(at + 1, end)).joinToString("|")
                at = end
            }
        }
        if (fields.none { it.name == name }) fields += classify(name, optional, type)
        for ((n, t) in more) if (fields.none { it.name == n }) fields += classify(n, true, t)
    }
    if (rest.contains("exactly one") || rest.contains("one of")) alternatives = alternatives || fields.all { it.optional }
    return ParamForm(fields, alternatives, rest.take(MAX_NOTES))
}

/** `spaceBefore` as a person reads it: "Space before". */
fun fieldLabel(name: String): String {
    val spaced = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').lowercase()
    return spaced.replaceFirstChar { it.uppercase() }
}

private val lenientJson = Json { isLenient = true }

/**
 * What the form sends for [field] holding [raw]: null to leave the key out (a blank field). Throws
 * [IllegalArgumentException] with a message for the person when [raw] is not a usable value.
 */
fun fieldValue(field: ParamField, raw: String): JsonElement? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    field.options.firstOrNull { it.text == text }?.let { o ->
        if (o.quoted) return JsonPrimitive(o.text)
        if (o.text == "null") return JsonNull
        if (o.text == "true" || o.text == "false") return JsonPrimitive(o.text == "true")
        return numberValue(o.text) ?: JsonPrimitive(o.text)
    }
    val label = fieldLabel(field.name)
    return when (field.kind) {
        FieldKind.Bool -> when (text.lowercase()) {
            "true", "yes", "on" -> JsonPrimitive(true)
            "false", "no", "off" -> JsonPrimitive(false)
            else -> throw IllegalArgumentException("$label: choose yes or no")
        }
        FieldKind.Number -> numberValue(text) ?: throw IllegalArgumentException("$label: needs a number")
        FieldKind.Color -> {
            val hex = text.removePrefix("#").uppercase()
            if (hex.length != 6 || hex.any { it !in "0123456789ABCDEF" }) throw IllegalArgumentException("$label: needs a colour like 1F4E79")
            JsonPrimitive(hex)
        }
        FieldKind.Json -> try {
            lenientJson.parseToJsonElement(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("$label: needs JSON, such as [1, 2] or {\"key\": 1}")
        }
        FieldKind.Text, FieldKind.Choice, FieldKind.Path -> JsonPrimitive(text)
    }
}

/** [text] as a JSON number (whole when it is), or null when it isn't a finite number. */
private fun numberValue(text: String): JsonPrimitive? {
    val t = text.trim().replace(',', '.')
    t.toLongOrNull()?.let { return JsonPrimitive(it) }
    val d = t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return JsonPrimitive(d)
}

/**
 * The params the form sends: each filled field under its name. A required field left blank is an
 * error unless the doc offers [ParamForm.alternatives]. [rawJson], when not blank, is sent instead
 * of the fields (the escape hatch for a shape the form can't draw).
 */
fun buildParams(form: ParamForm, values: Map<String, String>, rawJson: String = ""): Result<JsonObject> = runCatching {
    if (rawJson.isNotBlank()) {
        val parsed = try { lenientJson.parseToJsonElement(rawJson.trim()) } catch (_: Exception) { null }
        return@runCatching parsed as? JsonObject ?: throw IllegalArgumentException("The JSON box needs an object, such as {\"key\": 1}")
    }
    val out = LinkedHashMap<String, JsonElement>()
    for (f in form.fields) {
        val v = fieldValue(f, values[f.name].orEmpty())
        if (v == null) {
            if (!f.optional && !form.alternatives) throw IllegalArgumentException("${fieldLabel(f.name)} is needed")
            continue
        }
        out[f.name] = v
    }
    JsonObject(out)
}
