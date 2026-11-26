package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The section's page in points: what the horizontal ruler measures (the width and side margins) and what moving an
 * object snaps to (with the height and the top and bottom margins; 0 when the engine doesn't say).
 */
class RulerInfo(
    val pageW: Float, val marginLeft: Float, val marginRight: Float,
    val pageH: Float = 0f, val marginTop: Float = 0f, val marginBottom: Float = 0f,
)

/** The ruler's numbers from `layout.section`; null when the section's sizes are not usable. */
fun parseRulerInfo(e: JsonElement?): RulerInfo? {
    val o = e as? JsonObject ?: return null
    fun n(k: String) = (o[k] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it >= 0f }
    val w = n("pageW")?.takeIf { it > 0f && it <= 20_000f } ?: return null
    val left = n("marginLeft") ?: return null
    val right = n("marginRight") ?: return null
    if (left + right >= w) return null
    val h = n("pageH")?.takeIf { it <= 20_000f } ?: 0f
    return RulerInfo(w, left, right, h, n("marginTop") ?: 0f, n("marginBottom") ?: 0f)
}

/** One mark of the ruler: where it is from the page's left edge in points, how long (0 short, 1 half inch, 2 inch) and the inch number under an inch mark. */
class RulerTick(val x: Float, val size: Int, val label: String)

/**
 * The marks along [info]'s page: one every eighth of an inch, counted from the left margin as on
 * paper rulers in word processors (so the margin itself reads backwards from zero).
 */
fun rulerTicks(info: RulerInfo): List<RulerTick> {
    val step = 9f
    val first = -(info.marginLeft / step).toInt()
    val last = ((info.pageW - info.marginLeft) / step).toInt()
    // A page is at most a few hundred eighths wide; the cap is for a section that claims more.
    return (first..last).take(4000).map { i ->
        val size = if (i % 8 == 0) 2 else if (i % 4 == 0) 1 else 0
        RulerTick(info.marginLeft + i * step, size, if (size == 2 && i != 0) "${kotlin.math.abs(i / 8)}" else "")
    }
}

internal val RULER_HEIGHT = 20.dp

/** Indent markers snap to sixteenths of an inch while dragged. */
private const val SNAP_PT = 4.5f

/** A tab stop as the ruler shows it: where (points from the text column's left edge), its alignment and leader. */
data class TabMark(val pos: Float, val align: String = "left", val leader: String = "none")

/** The tab stops the engine reports for the caret's paragraph; ones without a usable position are left out. */
internal fun parseTabMarks(e: JsonElement?): List<TabMark> = (e as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { t ->
    val o = t as? JsonObject ?: return@mapNotNull null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val pos = (o["pos"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
    TabMark(pos, str("align")?.lowercase() ?: "left", str("leader")?.lowercase() ?: "none")
}.sortedBy { it.pos }

/** One of the ruler's indent markers. */
enum class IndentMarker { FirstLine, Left, Right }

/**
 * Where the ruler's indent markers sit, in points from the page's left edge: the text area between
 * the margins, and the first-line, left and right indents of the caret's paragraph.
 */
data class RulerGeometry(val pageWidth: Float, val textLeft: Float, val textRight: Float, val firstLine: Float, val left: Float, val right: Float)

/** The markers for the caret paragraph's indents in [layout] on [info]'s page. */
internal fun rulerGeometry(info: RulerInfo, layout: LayoutState): RulerGeometry {
    val left = info.marginLeft + (layout.indentLeft ?: 0f)
    val textRight = info.pageW - info.marginRight
    return RulerGeometry(
        pageWidth = info.pageW,
        textLeft = info.marginLeft,
        textRight = textRight,
        firstLine = left + (layout.firstLine ?: 0f),
        left = left,
        right = textRight - (layout.indentRight ?: 0f),
    )
}

/**
 * The marker a press at [x] (points from the page's left) picks up, within [reach] points; [upper]
 * says the press is in the ruler's top half, where the first-line marker hangs (the left one
 * stands on the bottom edge; the right one can be taken from either half).
 */
internal fun markerAt(g: RulerGeometry, x: Float, upper: Boolean, reach: Float): IndentMarker? {
    val candidates = listOfNotNull(
        (IndentMarker.FirstLine to g.firstLine).takeIf { upper },
        (IndentMarker.Left to g.left).takeIf { !upper },
        IndentMarker.Right to g.right,
    )
    return candidates.filter { abs(it.second - x) <= reach }.minByOrNull { abs(it.second - x) }?.first
}

/** What a press on the ruler takes hold of. */
internal sealed interface RulerTarget {
    data class Marker(val marker: IndentMarker) : RulerTarget
    /** A tab stop, by its place in the paragraph's list. */
    data class Tab(val index: Int) : RulerTarget
    /** The edge between a side margin and the text: [left] for the left margin. */
    data class Margin(val left: Boolean) : RulerTarget
    /** Nothing: a click inside the text area sets a tab there. */
    data object Empty : RulerTarget
}

/** Which band of the ruler a press is in: the first-line marker hangs in the top one, the other markers and the tabs stand in the bottom one. */
enum class RulerBand { Top, Middle, Bottom }

/** The band at [y] of a ruler [height] high. */
internal fun rulerBand(y: Float, height: Float): RulerBand = when {
    y < height * 0.3f -> RulerBand.Top
    y > height * 0.7f -> RulerBand.Bottom
    else -> RulerBand.Middle
}

/**
 * What a press at [x] (points from the page's left) in [band] takes hold of, within [reach] points. The markers sit on
 * the margins when there is no indent, so the middle band is the margins' edges' and the others the markers' (then the
 * tab stops, at the bottom); a press near nothing in its band falls back to the rest.
 */
internal fun rulerTargetAt(g: RulerGeometry, tabs: List<TabMark>, x: Float, band: RulerBand, reach: Float): RulerTarget {
    fun margin(): RulerTarget? = when {
        abs(x - g.textLeft) <= reach -> RulerTarget.Margin(left = true)
        abs(x - g.textRight) <= reach -> RulerTarget.Margin(left = false)
        else -> null
    }
    if (band == RulerBand.Middle) margin()?.let { return it }
    val upper = band == RulerBand.Top
    markerAt(g, x, upper, reach)?.let { return RulerTarget.Marker(it) }
    if (!upper) tabs.withIndex().filter { abs(g.textLeft + it.value.pos - x) <= reach }.minByOrNull { abs(g.textLeft + it.value.pos - x) }
        ?.let { return RulerTarget.Tab(it.index) }
    return margin() ?: RulerTarget.Empty
}

/** `para.tabs` for a new left tab where the ruler was clicked; null outside the text area. */
internal fun tabAddParams(g: RulerGeometry, x: Float): JsonObject? {
    val at = snap(x)
    if (at <= g.textLeft || at >= g.textRight) return null
    return buildJsonObject { put("set", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("pos", at - g.textLeft); put("align", "left") }) }) }
}

/**
 * `para.tabs` for tab [t] dropped at [x]: moved there in one step (its alignment and leader kept), or, dropped off the
 * ruler ([off]), cleared.
 */
internal fun tabDropParams(g: RulerGeometry, t: TabMark, x: Float, off: Boolean): JsonObject = buildJsonObject {
    put("clear", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive(t.pos)) })
    if (!off) put("set", kotlinx.serialization.json.buildJsonArray {
        add(buildJsonObject {
            put("pos", (snap(x) - g.textLeft).coerceIn(0f, g.textRight - g.textLeft))
            put("align", t.align)
            if (t.leader != "none") put("leader", t.leader)
        })
    })
}

/** The narrowest text column a margin drag leaves, in points (half an inch). */
private const val MIN_TEXT_PT = 36f

/** `layout.margins` for a side margin's edge dropped at [x], keeping at least half an inch of text between the margins. */
internal fun marginParams(g: RulerGeometry, left: Boolean, x: Float): JsonObject {
    val at = snap(x)
    return if (left) buildJsonObject { put("left", at.coerceIn(0f, g.textRight - MIN_TEXT_PT)) }
    else buildJsonObject { put("right", (g.pageWidth - at).coerceIn(0f, g.pageWidth - g.textLeft - MIN_TEXT_PT)) }
}

/** [x] snapped to the ruler's step. */
internal fun snap(x: Float): Float = (x / SNAP_PT).roundToInt() * SNAP_PT

/**
 * The `para.indents` parameters for [marker] dropped at [x] (points from the page's left). The
 * left marker keeps the first line where it is (only the hanging part moves), and no indent goes
 * past the page's edges.
 */
internal fun indentParams(g: RulerGeometry, marker: IndentMarker, x: Float): JsonObject {
    val at = snap(x).coerceIn(0f, g.pageWidth)
    return when (marker) {
        IndentMarker.FirstLine -> buildJsonObject { put("firstLine", at - g.left) }
        IndentMarker.Left -> buildJsonObject {
            put("left", at - g.textLeft)
            put("firstLine", g.firstLine - at)
        }
        IndentMarker.Right -> buildJsonObject { put("right", g.textRight - at) }
    }
}

/**
 * The horizontal ruler (View › Ruler) over the page area: the text area light between the darker
 * margins, inch marks from the left margin, and the caret paragraph's first-line, left and right
 * indent markers, which drag to set its indents ([onIndents] gets the `para.indents` parameters).
 * [leftPx] is where the page's left edge is in this ruler, so it follows a page scrolled sideways.
 */
@Composable
internal fun HorizontalRuler(
    info: RulerInfo,
    layout: LayoutState,
    leftPx: Float,
    pxPerPt: Float,
    onRun: (String, JsonObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val margin = MaterialTheme.colorScheme.surfaceVariant
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val style = TextStyle(fontSize = 9.sp, color = ink)
    val g = rulerGeometry(info, layout)
    val tabs = layout.tabs
    // What is being dragged and where it is, so it follows the pointer before the engine answers; [off] is a tab pulled off the ruler.
    var dragging by remember { mutableStateOf<Triple<RulerTarget, Float, Boolean>?>(null) }
    Canvas(
        modifier.fillMaxWidth().height(RULER_HEIGHT).pointerInput(g, tabs, leftPx, pxPerPt) {
            if (pxPerPt <= 0f) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val x = (down.position.x - leftPx) / pxPerPt
                val target = rulerTargetAt(g, tabs, x, rulerBand(down.position.y, size.height.toFloat()), reach = 8.dp.toPx() / pxPerPt)
                down.consume()
                var moved = false
                dragging = Triple(target, x, false)
                drag(down.id) { change ->
                    change.consume()
                    val nx = (change.position.x - leftPx) / pxPerPt
                    if (abs(nx - x) * pxPerPt > 3f || abs(change.position.y - down.position.y) > 3f) moved = true
                    // A tab pulled well above or below the ruler comes off it.
                    val off = change.position.y < -size.height || change.position.y > size.height * 2f
                    dragging = Triple(target, nx, off)
                }
                val end = dragging
                dragging = null
                if (end == null) return@awaitEachGesture
                val (_, at, off) = end
                when (target) {
                    is RulerTarget.Marker -> if (moved) onRun("para.indents", indentParams(g, target.marker, at))
                    is RulerTarget.Tab -> if (moved) tabs.getOrNull(target.index)?.let { onRun("para.tabs", tabDropParams(g, it, at, off)) }
                    is RulerTarget.Margin -> if (moved) onRun("layout.margins", marginParams(g, target.left, at))
                    RulerTarget.Empty -> if (!moved) tabAddParams(g, x)?.let { onRun("para.tabs", it) }
                }
            }
        }.semantics { contentDescription = "Ruler: drag the indent markers, tab stops or margin edges; click to set a tab stop" },
    ) {
        fun x(pt: Float) = leftPx + pt * pxPerPt
        val shown = dragging
        // A margin follows while its edge is dragged.
        val textLeftPt = (shown?.first as? RulerTarget.Margin)?.takeIf { it.left }?.let { snap(shown.second) } ?: info.marginLeft
        val textRightPt = (shown?.first as? RulerTarget.Margin)?.takeIf { !it.left }?.let { snap(shown.second) } ?: (info.pageW - info.marginRight)
        drawRect(margin)
        val textLeft = x(textLeftPt)
        val textRight = x(textRightPt)
        drawRect(paper, Offset(textLeft, 2f), Size((textRight - textLeft).coerceAtLeast(0f), size.height - 4f))
        // Marks closer than a few pixels would be a grey smear: at small zooms only the longer ones are drawn.
        val every = if (9f * pxPerPt >= 5f) 0 else if (36f * pxPerPt >= 5f) 1 else 2
        for (t in rulerTicks(info)) {
            if (t.size < every) continue
            val px = x(t.x)
            if (px < 0f || px > size.width) continue
            if (t.label.isNotEmpty()) {
                val text = measurer.measure(t.label, style)
                drawText(text, topLeft = Offset(px - text.size.width / 2f, (size.height - text.size.height) / 2f))
            } else {
                val h = if (t.size == 2) size.height * 0.5f else if (t.size == 1) size.height * 0.35f else size.height * 0.18f
                drawLine(ink.copy(alpha = 0.7f), Offset(px, (size.height - h) / 2f), Offset(px, (size.height + h) / 2f), strokeWidth = 1f)
            }
        }
        val marker = (shown?.first as? RulerTarget.Marker)?.marker
        fun at(m: IndentMarker, pt: Float) = if (marker == m) snap(shown!!.second) else pt
        fun color(m: IndentMarker) = if (marker == m) accent else ink
        drawMarker(x(at(IndentMarker.FirstLine, g.firstLine)), top = true, color(IndentMarker.FirstLine), paper)
        drawMarker(x(at(IndentMarker.Left, g.left)), top = false, color(IndentMarker.Left), paper)
        drawMarker(x(at(IndentMarker.Right, g.right)), top = false, color(IndentMarker.Right), paper)
        // The tab stops, each drawn by its alignment; the one being dragged follows the pointer, and fades when it would come off.
        val draggedTab = (shown?.first as? RulerTarget.Tab)?.index
        tabs.forEachIndexed { i, t ->
            val pt = if (i == draggedTab) snap(shown!!.second) else g.textLeft + t.pos
            val c = if (i == draggedTab) (if (shown!!.third) accent.copy(alpha = 0.35f) else accent) else ink
            drawTabMark(x(pt), t.align, c)
        }
        // While dragging, a line down the ruler shows where it lands.
        shown?.takeIf { it.first != RulerTarget.Empty && !it.third }?.let { (_, pt, _) -> drawLine(accent, Offset(x(snap(pt)), 0f), Offset(x(snap(pt)), size.height), 1.dp.toPx()) }
    }
}

/** A tab stop's mark on the ruler's lower half: an L for left, a reversed L for right, an inverted T for centre, with a dot for decimal and a bar for bar. */
private fun DrawScope.drawTabMark(x: Float, align: String, color: Color) {
    val w = 1.5.dp.toPx()
    val arm = 4.dp.toPx()
    val bottom = size.height - 2.dp.toPx()
    val top = bottom - 6.dp.toPx()
    drawLine(color, Offset(x, top), Offset(x, bottom), w)
    when (align) {
        "right" -> drawLine(color, Offset(x - arm, bottom), Offset(x, bottom), w)
        "center", "decimal" -> drawLine(color, Offset(x - arm, bottom), Offset(x + arm, bottom), w)
        "bar" -> {}
        else -> drawLine(color, Offset(x, bottom), Offset(x + arm, bottom), w)
    }
    if (align == "decimal") drawCircle(color, w, Offset(x + arm * 0.8f, top + 2.dp.toPx()))
}

/** An indent marker: a small triangle at [x], hanging from the top edge ([top]) or standing on the bottom one. */
private fun DrawScope.drawMarker(x: Float, top: Boolean, color: Color, fill: Color) {
    val half = 4.5.dp.toPx()
    val h = 5.5.dp.toPx()
    val base = if (top) 0f else size.height
    val tip = if (top) h else size.height - h
    val p = Path().apply {
        moveTo(x - half, base)
        lineTo(x + half, base)
        lineTo(x, tip)
        close()
    }
    drawPath(p, fill)
    drawPath(p, color, style = Stroke(1.dp.toPx()))
}
