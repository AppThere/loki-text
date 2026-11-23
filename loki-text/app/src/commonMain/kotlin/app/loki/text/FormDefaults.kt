package app.loki.text

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/**
 * Where a setting command's form finds the values to start with: [reader] is a command that only reports (never
 * the setter itself, which would change the document), [fields] maps each form field to a dotted path in what the
 * reader returns, and [valueMap] turns a reported value into the one the field takes (`landscape: true` into `landscape`).
 */
internal class DefaultsSource(
    val reader: String,
    val fields: Map<String, String>,
    val valueMap: Map<String, Map<String, String>> = emptyMap(),
)

private fun same(vararg names: String) = names.associateWith { it }

/**
 * The commands whose forms start with the document's current values. Only listed commands do: guessing from
 * matching names gets them wrong (`style` is a text style in one command and a line style in another), and
 * calling a setter with no values to read it back changes the document.
 */
internal val FORM_DEFAULTS: Map<String, DefaultsSource> = mapOf(
    "file.options" to DefaultsSource(
        "file.options",
        same(
            "author", "initials", "units", "typingReplacesSelection", "insertKeyOvertype", "overtype", "marks", "checkSpelling", "markGrammar",
            "ignoreUppercase", "ignoreNumbers", "ignoreInternet", "dictionariesFolder",
        ),
    ),
    "review.trackingOptions" to DefaultsSource(
        "review.trackingOptions",
        same(
            "comments", "ink", "insertionsDeletions", "formatting", "balloons", "commentDisplay", "insertMark", "insertColor", "deleteMark", "deleteColor",
            "changedLines", "changedLinesColor", "trackFormatting", "trackMoves", "moveFromMark", "moveFromColor", "moveToMark", "moveToColor",
        ),
    ),
    "file.privacyOptions" to DefaultsSource("file.privacy", same("removePersonalInfo", "removeDateTime")),
    "layout.margins" to DefaultsSource(
        "layout.section",
        mapOf("top" to "marginTop", "bottom" to "marginBottom", "left" to "marginLeft", "right" to "marginRight", "gutter" to "gutter"),
    ),
    "layout.pageLayout" to DefaultsSource(
        "layout.section",
        mapOf(
            "width" to "pageW", "height" to "pageH", "landscape" to "landscape", "top" to "marginTop", "bottom" to "marginBottom",
            "left" to "marginLeft", "right" to "marginRight", "gutter" to "gutter", "valign" to "valign",
        ),
    ),
    "layout.size" to DefaultsSource("layout.section", mapOf("width" to "pageW", "height" to "pageH")),
    "layout.orientation" to DefaultsSource("layout.section", mapOf("value" to "landscape"), mapOf("value" to mapOf("true" to "landscape", "false" to "portrait"))),
    "layout.columns" to DefaultsSource("layout.section", mapOf("count" to "columns.count", "space" to "columns.space", "separator" to "columns.separator")),
    "para.indents" to DefaultsSource("para.state", mapOf("left" to "left", "right" to "right", "firstLine" to "indentFirst")),
    "para.spacing" to DefaultsSource("para.state", mapOf("before" to "spaceBefore", "after" to "spaceAfter")),
    "format.size" to DefaultsSource("format.state", mapOf("size" to "size")),
    "format.font" to DefaultsSource("format.state", mapOf("name" to "font", "eastAsia" to "fontEastAsia")),
)

/** [path] ("columns.count") followed through [root]'s objects; null when any step is missing. */
internal fun pointer(root: JsonElement?, path: String): JsonElement? =
    path.split('.').fold(root) { node, key -> (node as? JsonObject)?.get(key) }

/** A reported value as the text a form field holds: whole numbers without a point, others to two places; null for nothing to show. */
internal fun fieldText(v: JsonElement?): String? = when (v) {
    null, JsonNull -> null
    is JsonPrimitive -> when {
        v.isString -> v.content
        v.booleanOrNull != null -> v.content
        v.doubleOrNull != null -> {
            val d = v.doubleOrNull!!
            val r = (d * 100).roundToLong() / 100.0
            if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
        }
        else -> v.content
    }
    is JsonObject, is JsonArray -> v.toString()
}

/** The form fields' starting values from what [source]'s reader returned ([reported]). Fields the reader has no value for are left out. */
internal fun formDefaults(source: DefaultsSource, reported: JsonElement?): Map<String, String> = buildMap {
    for ((field, path) in source.fields) {
        val text = fieldText(pointer(reported, path)) ?: continue
        put(field, source.valueMap[field]?.get(text) ?: text)
    }
}
