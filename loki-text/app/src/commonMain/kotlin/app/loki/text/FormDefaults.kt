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
 * reader returns, [valueMap] turns a reported value into the one the field takes (`landscape: true` into `landscape`),
 * and [scale] multiplies a reported number (a zoom of 1.5 into 150 %). [opensForm]: the command's button opens its
 * form even though every parameter is optional; otherwise the defaults are only there for when the form is opened
 * (the all-commands list's options, a result's Change…), and the button runs the command as before.
 */
internal class DefaultsSource(
    val reader: String,
    val fields: Map<String, String>,
    val valueMap: Map<String, Map<String, String>> = emptyMap(),
    val scale: Map<String, Double> = emptyMap(),
    val opensForm: Boolean = true,
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
    // The selection's character formatting.
    "format.color" to DefaultsSource("format.state", mapOf("color" to "color")),
    "format.underlineColor" to DefaultsSource("format.state", mapOf("color" to "underlineColor")),
    "format.spacing" to DefaultsSource("format.state", mapOf("points" to "spacing")),
    "format.underline" to DefaultsSource("format.state", mapOf("value" to "underline"), opensForm = false),
    "format.emphasisMark" to DefaultsSource("format.state", mapOf("mark" to "emphasisMark"), opensForm = false),
    "para.style" to DefaultsSource("format.state", mapOf("style" to "style")),
    "review.language" to DefaultsSource("format.state", mapOf("lang" to "lang", "noProof" to "noProof"), opensForm = false),
    // The paragraphs'.
    "para.align" to DefaultsSource("para.state", mapOf("value" to "align")),
    "para.keepNext" to DefaultsSource("para.state", mapOf("value" to "keepNext"), opensForm = false),
    "para.keepLines" to DefaultsSource("para.state", mapOf("value" to "keepLines"), opensForm = false),
    "para.pageBreakBefore" to DefaultsSource("para.state", mapOf("value" to "pageBreakBefore"), opensForm = false),
    "para.widowControl" to DefaultsSource("para.state", mapOf("value" to "widowControl"), opensForm = false),
    "para.rtl" to DefaultsSource("para.state", mapOf("value" to "bidi"), opensForm = false),
    // The caret's section.
    "layout.verticalAlign" to DefaultsSource("layout.section", mapOf("value" to "valign")),
    "layout.sectionRtl" to DefaultsSource("layout.section", mapOf("value" to "rtl"), opensForm = false),
    "layout.textDirection" to DefaultsSource("layout.section", mapOf("value" to "textDirection"), opensForm = false),
    "layout.documentGrid" to DefaultsSource("layout.section", mapOf("type" to "docGrid.type"), opensForm = false),
    "layout.pageNumberFormat" to DefaultsSource("layout.section", mapOf("format" to "pageNumFormat", "start" to "pageNumStart"), opensForm = false),
    "layout.lineNumbers" to DefaultsSource(
        "layout.section",
        mapOf("value" to "lineNumbers.restart", "start" to "lineNumbers.start", "countBy" to "lineNumbers.countBy", "distance" to "lineNumbers.distance"),
        mapOf("value" to mapOf("page" to "restartPage", "section" to "restartSection", "continuous" to "continuous")),
        opensForm = false,
    ),
    // The selected chart's categories and series, so Edit Data changes them rather than starting over.
    "chart.editData" to DefaultsSource("chart.data", mapOf("data" to "data")),
    // The view, Read Aloud and the person's settings.
    "view.zoom" to DefaultsSource("view.state", mapOf("value" to "zoom"), scale = mapOf("value" to 100.0)),
    "view.numeral" to DefaultsSource("view.state", mapOf("value" to "numeral"), opensForm = false),
    "view.stylesPaneDock" to DefaultsSource("view.state", mapOf("dock" to "stylesPaneDock"), opensForm = false),
    "readAloud.speed" to DefaultsSource("readAloud.status", mapOf("value" to "rate")),
    "readAloud.skipCitations" to DefaultsSource("readAloud.status", mapOf("value" to "skipCitations"), opensForm = false),
    "file.setAuthor" to DefaultsSource("file.options", mapOf("name" to "author")),
    "tools.autocorrect" to DefaultsSource(
        "tools.autocorrect",
        same(
            "enabled", "replaceText", "twoInitialCaps", "capSentences", "capCells", "capDays", "capsLock", "smartQuotes", "fractions", "ordinals",
            "dashes", "links", "bullets", "numbering", "borderLines", "boldItalic", "tabIndent", "headingStyles",
        ),
        opensForm = false,
    ),
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
        val found = pointer(reported, path)
        val factor = source.scale[field]
        val text = (if (factor != null) (found as? JsonPrimitive)?.doubleOrNull?.let { fieldText(JsonPrimitive(it * factor)) } else fieldText(found)) ?: continue
        put(field, source.valueMap[field]?.get(text) ?: text)
    }
}
