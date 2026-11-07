package app.loki.text

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * The character formatting at the selection that the Home tab's controls show: the font and size
 * in their boxes, and (for the colour buttons) what the text has now. From `format.state`; a
 * field is null when the selection mixes values or the engine said nothing.
 */
class FormatState(val font: String?, val size: Float?, val color: String?, val highlight: String?) {
    companion object {
        val EMPTY = FormatState(null, null, null, null)
    }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

fun parseFormatState(state: JsonElement): FormatState {
    val o = state as? JsonObject ?: return FormatState.EMPTY
    return FormatState(
        font = o["font"].text(),
        size = (o["size"] as? JsonPrimitive)?.floatOrNull,
        color = o["color"].text(),
        highlight = o["highlight"].text(),
    )
}

/** Family names from `font.list {"all": true}`, sorted, without duplicates or blanks. */
fun parseFontFamilies(list: JsonElement): List<String> =
    ((list as? JsonObject)?.get("families") as? JsonArray).orEmpty()
        .mapNotNull { it.text()?.trim()?.takeIf { f -> f.isNotEmpty() } }
        .distinct()
        .sortedBy { it.lowercase() }

/** A size as it is written in the box: `11`, `10.5`. */
fun sizeText(size: Float?): String = when {
    size == null || !size.isFinite() -> ""
    size == size.toInt().toFloat() -> size.toInt().toString()
    else -> size.toString()
}

/** The point sizes in the Font Size menu; the engine's own list (`format.growFont` steps through it). */
val FONT_SIZES = listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72)

/** What a typed size may be: Word allows 1 to 1638 pt. Anything else is ignored. */
fun parseFontSize(text: String): Float? = text.trim().toFloatOrNull()?.takeIf { it.isFinite() && it in 1f..1638f }

/** A colour choice: the [name] it is announced by, the [param] the command takes, and how it looks. */
class Swatch(val name: String, val param: String, val color: Color)

private fun rgb(name: String, hex: String) = Swatch(name, hex, Color(0xFF000000.toInt() or hex.toInt(16)))

/** Font Color choices; each is a plain `RRGGBB` for `format.color`. Our own palette. */
val FONT_COLORS = listOf(
    rgb("Black", "000000"), rgb("Dark Gray", "595959"), rgb("Gray", "8C8C8C"), rgb("Dark Red", "A00000"), rgb("Red", "E02020"),
    rgb("Orange", "F07800"), rgb("Gold", "D8A800"), rgb("Green", "2E8B2E"), rgb("Teal", "108080"), rgb("Blue", "2060D0"),
    rgb("Navy", "102F7A"), rgb("Purple", "7A30A8"),
)

/** Shading choices for `para.shading`: soft background fills. */
val SHADINGS = listOf(
    rgb("Light Gray", "E6E6E6"), rgb("Light Yellow", "FFF4B8"), rgb("Light Green", "D6F0D0"), rgb("Light Blue", "D4E4FA"),
    rgb("Light Pink", "F9D6E6"), rgb("Light Orange", "FFE0C0"), rgb("Light Purple", "E6D8F4"), rgb("Gray", "BFBFBF"),
)

/** The highlight colours. [Swatch.param] is the name `format.highlight` accepts. */
val HIGHLIGHTS = listOf(
    Swatch("Yellow", "Yellow", Color(0xFFFFFF00)), Swatch("Bright Green", "BrightGreen", Color(0xFF00FF00)),
    Swatch("Turquoise", "Turquoise", Color(0xFF00FFFF)), Swatch("Pink", "Pink", Color(0xFFFF00FF)),
    Swatch("Blue", "Blue", Color(0xFF0000FF)), Swatch("Red", "Red", Color(0xFFFF0000)),
    Swatch("Dark Blue", "DarkBlue", Color(0xFF000080)), Swatch("Teal", "Teal", Color(0xFF008080)),
    Swatch("Green", "Green", Color(0xFF008000)), Swatch("Violet", "Violet", Color(0xFF800080)),
    Swatch("Dark Red", "DarkRed", Color(0xFF800000)), Swatch("Dark Yellow", "DarkYellow", Color(0xFF808000)),
    Swatch("Gray 50%", "Gray50", Color(0xFF808080)), Swatch("Gray 25%", "Gray25", Color(0xFFC0C0C0)),
    Swatch("Black", "Black", Color(0xFF000000)),
)

/** Line spacing multiples for the Line Spacing menu. */
val LINE_SPACINGS = listOf(1.0f, 1.15f, 1.5f, 2.0f, 2.5f, 3.0f)

/** `1.0` as `1`, `1.15` as `1.15`. */
fun spacingText(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

/** `RRGGBB` or `#RRGGBB` as its three channels, or null when it isn't six hex digits. */
fun parseHexColor(text: String): Triple<Int, Int, Int>? {
    val h = text.trim().removePrefix("#")
    if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val v = h.toInt(16)
    return Triple(v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
}

/** Three channels (clamped to 0..255) as `RRGGBB`. */
fun hexOf(r: Int, g: Int, b: Int): String =
    listOf(r, g, b).joinToString("") { it.coerceIn(0, 255).toString(16).padStart(2, '0') }.uppercase()

/** Border line styles `para.borders` accepts, with their menu names. */
val BORDER_STYLES = listOf("Single" to "single", "Double" to "double", "Dotted" to "dotted", "Dashed" to "dashed", "Thick" to "thick", "Triple" to "triple")

/** Which sides a border applies to, with the `kind` each sends. */
val BORDER_KINDS = listOf(
    "Bottom Border" to "bottom", "Top Border" to "top", "Left Border" to "left", "Right Border" to "right",
    "All Borders" to "all", "Outside Borders" to "outside", "Inside Borders" to "inside", "No Border" to "none",
)

/** The Bullets menu: a glyph is the `kind` `para.bullets` takes. */
val BULLET_GLYPHS = listOf("•", "○", "▪", "◆", "✓", "–")

/** The Numbering menu: a label as it looks, and the `kind` for `para.numbering`. */
val NUMBERINGS = listOf(
    "1. 2. 3." to "numbered", "1) 2) 3)" to "numberedParen", "I. II. III." to "outline",
    "A. B. C." to "upperLetter", "a) b) c)" to "lowerLetter", "i. ii. iii." to "lowerRoman",
)

/** The Multilevel List menu: label and `list.multilevelPreset` preset. */
val MULTILEVEL_PRESETS = listOf(
    "1  1.1  1.1.1  (Headings)" to "headings", "Article I.  Section 1.01  (a)" to "article",
    "I.  A.  1.  (Headings)" to "outlineHeadings", "Chapter 1" to "chapter",
)
