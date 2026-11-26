package app.loki.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shape of a parameter whose value is an object or a list, read from its doc type, so the form can draw
 * fields for it instead of a JSON box. Anything the docs write that isn't one of these stays raw JSON.
 */
sealed interface ParamShape {
    /** An object with named keys, drawn as a nested form. */
    class Obj(val form: ParamForm) : ParamShape

    /** A list of [item]s, drawn as rows to add, remove and reorder. */
    class ListOf(val item: ParamShape) : ParamShape

    /** One plain value (a number, some text, a colour, a choice), drawn as a field: what a list of scalars holds. */
    class Scalar(val field: ParamField) : ParamShape

    /** A fixed run of plain values (`[width pt, space after pt]`), drawn as fields side by side and sent as an array. */
    class Tuple(val fields: List<ParamField>) : ParamShape

    /** An object whose keys the person names (`{style name: 1-9}`), drawn as rows of a name and a value. */
    class NamedValues(val nameLabel: String, val value: ParamField, val range: IntRange?) : ParamShape

    /** Rows of plain cells, any number of them (`[[cell, …], …]`), drawn as a grid; the limits are the engine's. */
    class Grid(val maxRows: Int = 1000, val maxColumns: Int = 63) : ParamShape
}

/** How deep shapes nest before the rest is left to JSON; the docs go three deep at most. */
private const val MAX_DEPTH = 4

/** The structured shape of [field], or null when its value is plain or beyond the form (positions, types a doc doesn't spell out). */
fun shapeOf(field: ParamField, defs: Map<String, String>): ParamShape? =
    if (field.kind != FieldKind.Json) null else shapeOfType(field.type, defs, 0)

internal fun shapeOfType(type: String, defs: Map<String, String>, depth: Int): ParamShape? {
    if (depth > MAX_DEPTH) return null
    // `X | null`: null is how a key is cleared, which a blank field already does.
    val alts = splitTopLevel(type.trim(), '|').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
    if (alts.isEmpty()) return null
    // A list of strings or objects (`[string | {"text": …}]`): the object says more, so it is the one drawn.
    val t = alts.firstOrNull { isStructured(it, defs) } ?: return null
    if (alts.size > 1 && alts.count { isStructured(it, defs) } > 1) return null
    defs[t]?.let { return shapeOfType(it, defs, depth + 1) }
    val joined = splitTopLevel(t, '&').map { it.trim() }
    if (joined.size > 1) return shapeOfJoined(joined, defs, depth)
    val close = matchingClose(t, 0)
    if (close != t.length - 1) return null
    if (t.startsWith("{")) {
        shapeOfNamedValues(t)?.let { return it }
        val form = parseParamForm(t)
        if (form.fields.isEmpty() || form.alternatives) return null
        return ParamShape.Obj(ParamForm(form.fields, false, "", defs))
    }
    // A list: `[TabStop]`, `[{…}]`, `[pt]`, `[string]`; not tuples (`[[width pt, space pt], …]`) or positions.
    val inner = t.substring(1, t.length - 1).trim().removeSuffix("…").removeSuffix("...").trim().removeSuffix(",").trim()
    if (inner.isEmpty()) return null
    val parts = splitTopLevel(inner, ',').map { it.trim() }
    // The values a list may hold, each named (`["comments", "revisions", …]`): a list of that choice.
    if (parts.size > 1 && parts.all { it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") }) {
        val choice = parseParamForm("{\"value\": \"${parts.joinToString("|") { it.trim('"') }}\"}").fields.single()
        return ParamShape.ListOf(ParamShape.Scalar(choice))
    }
    // Rows of any length (`[[cell, …], …]`).
    if (parts.size == 1 && isOpenRow(inner)) return ParamShape.Grid()
    // A list of fixed pairs (`[[width pt, space after pt], …]`).
    if (parts.size == 1 && inner.startsWith("[")) {
        val tuple = shapeOfTuple(inner) ?: return null
        return ParamShape.ListOf(tuple)
    }
    if (parts.size > 1 || inner.startsWith("[")) return null
    val innerAlts = splitTopLevel(inner, '|').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
    val structured = innerAlts.firstOrNull { it.startsWith("{") || it in defs || splitTopLevel(it, '&').size > 1 }
    if (structured != null) return shapeOfType(structured, defs, depth + 1)?.let { ParamShape.ListOf(it) }
    val item = parseParamForm("{\"value\": $inner}").fields.singleOrNull() ?: return null
    if (item.kind == FieldKind.Json || item.kind == FieldKind.Path) return null
    return ParamShape.ListOf(ParamShape.Scalar(item))
}

private fun isStructured(t: String, defs: Map<String, String>) = t.startsWith("{") || t.startsWith("[") || t in defs || splitTopLevel(t, '&').size > 1

/** `Part & {"part": name}`: one object with the keys of each. The keys added come first, as they say what the rest are about. */
private fun shapeOfJoined(parts: List<String>, defs: Map<String, String>, depth: Int): ParamShape.Obj? {
    val forms = parts.map { (shapeOfType(it, defs, depth + 1) as? ParamShape.Obj)?.form ?: return null }
    return ParamShape.Obj(ParamForm(forms.asReversed().flatMap { it.fields }.distinctBy { it.name }, false, "", defs))
}

/** `{style name: 1-9}`: keys the person names, each with a plain value. */
private val NAMED_VALUES = Regex("^\\{\\s*([A-Za-z]+(?: [A-Za-z]+)* name)\\s*:\\s*([^{}\\[\\],:]+?)\\s*\\}$")
private val WHOLE_RANGE = Regex("^(\\d+)\\s*(?:-|–|\\.\\.)\\s*(\\d+)$")

private fun shapeOfNamedValues(t: String): ParamShape.NamedValues? {
    val (name, type) = NAMED_VALUES.find(t)?.destructured ?: return null
    val value = parseParamForm("{\"value\": $type}").fields.singleOrNull() ?: return null
    if (value.kind == FieldKind.Json) return null
    val range = WHOLE_RANGE.find(type)?.let { m -> m.groupValues[1].toInt()..m.groupValues[2].toInt() }
    return ParamShape.NamedValues(name.replaceFirstChar { it.uppercase() }, value, range)
}

/** `[cell, …]`: one kind of cell, any number of them. */
private fun isOpenRow(t: String): Boolean {
    if (!t.startsWith("[") || matchingClose(t, 0) != t.length - 1) return false
    val parts = splitTopLevel(t.substring(1, t.length - 1), ',').map { it.trim() }
    return parts.size == 2 && (parts[1] == "…" || parts[1] == "...")
}

/** `[width pt, space after pt]`: each part a plain value named by its words; null for one with an open end (`[cell, …]`). */
private fun shapeOfTuple(t: String): ParamShape.Tuple? {
    if (matchingClose(t, 0) != t.length - 1) return null
    val parts = splitTopLevel(t.substring(1, t.length - 1), ',').map { it.trim() }
    if (parts.size < 2 || parts.any { it.isEmpty() || it == "…" || it == "..." }) return null
    val fields = parts.mapIndexed { i, p ->
        // "width pt": the name, then the type word; "x": a name alone is a number (the tuples are coordinates and sizes).
        val words = p.split(' ').filter { it.isNotEmpty() }
        val typeWord = words.last()
        val name = (if (words.size > 1) words.dropLast(1) else words).joinToString(" ")
        val f = parseParamForm("{\"v$i\": ${if (words.size > 1) typeWord else "number"}}").fields.singleOrNull() ?: return null
        if (f.kind != FieldKind.Number && f.kind != FieldKind.Text) return null
        ParamField(name, false, f.kind, f.options, "", f.type)
    }
    return ParamShape.Tuple(fields)
}

private val lenient = Json { isLenient = true }

/** [text] as JSON, or null when it is blank or not JSON. */
fun parseJsonOrNull(text: String): JsonElement? =
    text.trim().takeIf { it.isNotEmpty() }?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() }

/** A value as a field holds it: a string's text, other values as JSON; nothing for null. */
fun textOf(v: JsonElement?): String = when (v) {
    null, JsonNull -> ""
    is JsonPrimitive -> v.content
    else -> v.toString()
}

/** The field texts of an object value, by field name (an object field's own value as JSON text). */
fun objectTexts(form: ParamForm, value: JsonElement?): Map<String, String> {
    val o = value as? JsonObject ?: return emptyMap()
    return form.fields.mapNotNull { f -> o[f.name]?.let { f.name to textOf(it) } }.toMap()
}

/** The items of a list value as texts (an object item as JSON text). A value that isn't a list gives none. */
fun listTexts(value: JsonElement?): List<String> = (value as? JsonArray)?.map { textOf(it) } ?: emptyList()

/**
 * The value an object's fields make: null when every field is blank (an untouched optional object is left out),
 * else the object, or an error for the person (a required field blank, a bad number).
 */
fun buildObject(form: ParamForm, texts: Map<String, String>): Result<JsonObject?> {
    if (form.fields.all { texts[it.name].isNullOrBlank() }) return Result.success(null)
    return buildParams(form, texts)
}

/** The value a list's item texts make: blank items are dropped; an empty list is null (left out). */
fun buildList(item: ParamShape, texts: List<String>): Result<JsonArray?> = runCatching {
    val out = texts.mapIndexedNotNull { i, t ->
        try {
            when (item) {
                is ParamShape.Scalar -> fieldValue(item.field, t)
                is ParamShape.Obj -> buildObject(item.form, objectTexts(item.form, parseJsonOrNull(t))).getOrThrow()
                is ParamShape.ListOf -> buildList(item.item, listTexts(parseJsonOrNull(t))).getOrThrow()
                is ParamShape.Tuple -> buildTuple(item, listTexts(parseJsonOrNull(t))).getOrThrow()
                is ParamShape.NamedValues -> buildNamedValues(item, namedTexts(parseJsonOrNull(t))).getOrThrow()
                is ParamShape.Grid -> buildGrid(item, gridTexts(parseJsonOrNull(t))).getOrThrow()
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Item ${i + 1}: ${e.message}")
        }
    }
    if (out.isEmpty()) null else JsonArray(out)
}

/** The array a tuple's texts make: null when all are blank; every part is needed once any is given. */
fun buildTuple(tuple: ParamShape.Tuple, texts: List<String>): Result<JsonArray?> = runCatching {
    if (texts.all { it.isBlank() }) return@runCatching null
    JsonArray(tuple.fields.mapIndexed { i, f ->
        fieldValue(f, texts.getOrElse(i) { "" }) ?: throw IllegalArgumentException("${f.name.replaceFirstChar { it.uppercase() }} is needed")
    })
}

/** A map's rows as (name, value) texts. A value that isn't an object gives none. */
fun namedTexts(value: JsonElement?): List<Pair<String, String>> = (value as? JsonObject)?.map { (k, v) -> k to textOf(v) } ?: emptyList()

/**
 * The object a map's rows make: wholly blank rows are dropped and no rows is null (left out). A row half filled,
 * a name twice or a value outside the doc's range is an error: the engine would drop it without saying so.
 */
fun buildNamedValues(shape: ParamShape.NamedValues, rows: List<Pair<String, String>>): Result<JsonObject?> = runCatching {
    val out = LinkedHashMap<String, JsonElement>()
    rows.forEachIndexed { i, (rawName, rawValue) ->
        val name = rawName.trim()
        val text = rawValue.trim()
        if (name.isEmpty() && text.isEmpty()) return@forEachIndexed
        val row = "Row ${i + 1}"
        require(name.isNotEmpty()) { "$row: ${shape.nameLabel.lowercase()} is needed" }
        require(name !in out) { "$row: $name is there twice" }
        val range = shape.range
        if (range != null) require(text.toIntOrNull()?.let { it in range } == true) { "$row: needs a whole number from ${range.first} to ${range.last}" }
        out[name] = requireNotNull(fieldValue(shape.value, text)) { "$row: needs a value" }
    }
    if (out.isEmpty()) null else JsonObject(out)
}

/** A grid's cells as texts, row by row. A value that isn't a list of lists gives none. */
fun gridTexts(value: JsonElement?): List<List<String>> = (value as? JsonArray)?.map { listTexts(it) } ?: emptyList()

/**
 * The rows a grid's cells make, each as long as the longest (the engine takes only a rectangle), the cells as typed.
 * A grid of blank cells is null (left out).
 */
fun buildGrid(shape: ParamShape.Grid, rows: List<List<String>>): Result<JsonArray?> = runCatching {
    if (rows.all { row -> row.all { it.isBlank() } }) return@runCatching null
    val columns = rows.maxOf { it.size }
    require(rows.size <= shape.maxRows) { "At most ${shape.maxRows} rows" }
    require(columns <= shape.maxColumns) { "At most ${shape.maxColumns} columns" }
    JsonArray(rows.map { row -> JsonArray(List(columns) { JsonPrimitive(row.getOrElse(it) { "" }) }) })
}
