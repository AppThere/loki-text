package app.loki.text

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * The task panes this front end draws. [flag] is the key of `view.state` that says the pane is
 * open (null for a pane the engine keeps no flag for: this app remembers those itself), and
 * [toggle] the command that opens and closes it.
 */
enum class PaneKind(val title: String, val flag: String?, val toggle: String) {
    Comments("Comments", "commentsPane", "view.commentsPane"),
    Styles("Styles", "stylesPane", "view.stylesPane"),
    Clipboard("Clipboard", "clipboardPane", "edit.clipboardPane"),
    Reviewing("Reviewing", null, "review.changes"),
    Accessibility("Accessibility", "accessibilityPane", "view.accessibilityPane"),
    AltText("Alt Text", "altTextPane", "view.altTextPane"),
    StyleInspector("Style Inspector", "styleInspector", "styles.inspector"),
    Selection("Selection", null, "arrange.selectionPane"),
}

/** Panes the engine keeps no open flag for, by the command that brings each up. */
internal val LOCAL_PANES: Map<String, PaneKind> = PaneKind.entries.filter { it.flag == null }.associateBy { it.toggle }

/**
 * What `view.state` says about how the document is shown. The engine's own renderer draws plain
 * pages, so the front end applies the rest: the zoom, dark pages, gridlines, the ruler and which
 * panes are open.
 */
class ViewState(
    /** `print`, `web`, `draft` or `outline`. */
    val mode: String = "print",
    val readMode: Boolean = false,
    /** 1 is 100%. */
    val zoom: Float = 1f,
    /** `pageWidth`, `onePage`, `multiplePages`, or empty when the zoom is a plain percentage. */
    val fit: String = "",
    /** Dark pages: the page drawn light on dark. */
    val darkPages: Boolean = false,
    val ruler: Boolean = false,
    val gridlines: Boolean = false,
    val navPane: Boolean = false,
    /** The engine-flagged panes that are open, in [PaneKind] order. */
    val panes: List<PaneKind> = emptyList(),
    /** The Draw tab's tool while one is picked up; null when the pointer selects as usual. */
    val ink: InkTool? = null,
) {
    /** One continuous column (Web Layout, Draft, Outline) rather than sheets of paper. */
    val flat: Boolean get() = mode != "print" && !readMode

    companion object {
        val DEFAULT = ViewState()
    }
}

/**
 * The Draw tab's tool in hand: `pen`, `pencil` or `highlighter` (with the colour and width its
 * strokes get), `eraser`, or `lasso`. Presses on the page draw with it instead of moving the caret.
 */
class InkTool(val name: String, val red: Int = 0, val green: Int = 0, val blue: Int = 0, val width: Float = 1.5f) {
    val draws: Boolean get() = name != "eraser" && name != "lasso"
}

/** The tool `view.state`'s `draw` object says is in hand, or null for Select. */
internal fun parseInkTool(draw: JsonElement?): InkTool? {
    val o = draw as? JsonObject ?: return null
    val mode = o["mode"]
    val name = when (mode) {
        is JsonPrimitive -> mode.contentOrNull?.takeIf { it == "eraser" || it == "lasso" }
        is JsonObject -> (mode["pen"] as? JsonPrimitive)?.contentOrNull?.takeIf { it == "pen" || it == "pencil" || it == "highlighter" }
        else -> null
    } ?: return null
    val settings = o[name] as? JsonObject ?: return InkTool(name)
    val rgb = (settings["color"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.floatOrNull?.toInt()?.coerceIn(0, 255) }
    val width = (settings["width"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(72f) ?: 1.5f
    return if (rgb.size == 3) InkTool(name, rgb[0], rgb[1], rgb[2], width) else InkTool(name, width = width)
}

/** The command and parameters for an ink gesture of [tool] over [points] (page points) on [page]; null when there is nothing to send. */
internal fun inkCommand(tool: InkTool, page: Int, points: List<Pair<Float, Float>>): Pair<String, JsonObject>? {
    val usable = points.filter { it.first.isFinite() && it.second.isFinite() }.take(MAX_INK_POINTS)
    val first = usable.firstOrNull() ?: return null
    fun path() = kotlinx.serialization.json.JsonArray(usable.map { (x, y) -> kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))) })
    return when (tool.name) {
        "eraser" -> "draw.erase" to kotlinx.serialization.json.buildJsonObject { put("page", JsonPrimitive(page)); put("x", JsonPrimitive(first.first)); put("y", JsonPrimitive(first.second)) }
        "lasso" -> if (usable.size < 3) null else "draw.lasso" to kotlinx.serialization.json.buildJsonObject { put("page", JsonPrimitive(page)); put("points", path()) }
        else -> if (usable.size < 2) null else "draw.stroke" to kotlinx.serialization.json.buildJsonObject { put("page", JsonPrimitive(page)); put("points", path()) }
    }
}

/** The engine takes at most this many points in a stroke. */
internal const val MAX_INK_POINTS = 5000

/** Parses `view.state`; anything missing or odd keeps its default. */
fun parseViewState(e: JsonElement?): ViewState {
    val o = e as? JsonObject ?: return ViewState.DEFAULT
    fun flag(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true
    fun text(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return ViewState(
        mode = text("mode") ?: "print",
        readMode = flag("readMode"),
        zoom = (o["zoom"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it > 0f } ?: 1f,
        fit = text("fit").orEmpty(),
        darkPages = flag("darkMode"),
        ruler = flag("ruler"),
        gridlines = flag("gridlines"),
        navPane = flag("navPane"),
        panes = PaneKind.entries.filter { k -> k.flag != null && flag(k.flag) },
        ink = parseInkTool(o["draw"]),
    )
}

/**
 * The zoom that fits a page of [pageW] × [pageH] points into a viewport of [viewW] × [viewH]
 * pixels for the engine's [fit] (`pageWidth`, `onePage`, `multiplePages`), given [pxPerPtAt100]
 * pixels per point at 100%. Null when [fit] names no fit or the sizes are not usable.
 */
fun fitZoom(fit: String, pageW: Float, pageH: Float, viewW: Float, viewH: Float, pxPerPtAt100: Float): Float? {
    if (!(pageW > 0f && pageH > 0f && viewW > 0f && viewH > 0f && pxPerPtAt100 > 0f)) return null
    val byWidth = viewW / (pageW * pxPerPtAt100)
    val byHeight = viewH / (pageH * pxPerPtAt100)
    val z = when (fit) {
        "pageWidth" -> byWidth
        "onePage" -> minOf(byWidth, byHeight)
        // Two pages abreast would need a second column of pages; one row of them at half width reads the same.
        "multiplePages" -> minOf(byWidth, byHeight) / 2f
        else -> return null
    }
    return z.takeIf { it.isFinite() && it > 0f }
}

/** The tint a paper colour (`view.paperColor`, `view.readPageColor`) lays over the page, as `RRGGBB`; null for none. */
fun paperTint(name: String?): String? = when (name) {
    "green" -> "CCE8CF"
    "cream" -> "FBF0D9"
    "gray" -> "E0E0E0"
    "sepia" -> "F4ECD8"
    else -> null
}
