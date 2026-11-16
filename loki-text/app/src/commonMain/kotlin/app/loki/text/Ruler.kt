package app.loki.text

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt

/** The ruler's height. */
val RULER_HEIGHT = 24.dp

/** Indent markers snap to sixteenths of an inch while dragged. */
private const val SNAP_PT = 4.5f

/** One of the ruler's indent markers. */
enum class IndentMarker { FirstLine, Left, Right }

/**
 * Where the ruler's parts sit, in points from the page's left edge: the text area between the
 * margins, and the first-line, left and right indent markers of the caret's paragraph.
 */
data class RulerGeometry(val pageWidth: Float, val textLeft: Float, val textRight: Float, val firstLine: Float, val left: Float, val right: Float)

/** The ruler for [lay], or null when the page size isn't known. */
internal fun rulerGeometry(lay: LayoutState): RulerGeometry? {
    val w = lay.pageWidth ?: return null
    val ml = (lay.marginLeft ?: 72f).coerceIn(0f, w)
    val mr = (lay.marginRight ?: 72f).coerceIn(0f, w - ml)
    val left = ml + (lay.indentLeft ?: 0f)
    return RulerGeometry(
        pageWidth = w,
        textLeft = ml,
        textRight = w - mr,
        firstLine = left + (lay.firstLine ?: 0f),
        left = left,
        right = w - mr - (lay.indentRight ?: 0f),
    )
}

/**
 * The marker a press at [x] (points from the page's left) picks up, within [reach] points; [upper]
 * says the press is in the ruler's top half, where the first-line marker sits (the left and right
 * ones hang from the bottom).
 */
internal fun markerAt(g: RulerGeometry, x: Float, upper: Boolean, reach: Float): IndentMarker? {
    val candidates = listOfNotNull(
        (IndentMarker.FirstLine to g.firstLine).takeIf { upper },
        (IndentMarker.Left to g.left).takeIf { !upper },
        IndentMarker.Right to g.right,
    )
    return candidates.filter { abs(it.second - x) <= reach }.minByOrNull { abs(it.second - x) }?.first
}

/** [x] snapped to the ruler's step. */
internal fun snap(x: Float): Float = (x / SNAP_PT).roundToInt() * SNAP_PT

/**
 * The `para.indents` parameters for [marker] dropped at [x] (points from the page's left). As in
 * Word, the left marker keeps the first line where it is (only the hanging part moves), and no
 * indent goes past the page's edges.
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
 * The horizontal ruler over the page: the margins shaded, ticks every eighth of an inch from the
 * left margin with the inches numbered, and the caret paragraph's indent markers, which can be
 * dragged. [pageLeftPx] is where the page's left edge is in the ruler, [pxPerPt] the page's scale.
 */
@Composable
fun HorizontalRuler(lay: LayoutState, pageLeftPx: Float, pxPerPt: Float, onIndents: (JsonObject) -> Unit, modifier: Modifier = Modifier) {
    val g = rulerGeometry(lay) ?: return
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val shade = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val numberStyle = TextStyle(fontSize = 9.sp, color = ink.copy(alpha = 0.7f))
    // The marker being dragged and where it is, so it follows the pointer before the engine answers.
    var dragging by remember { mutableStateOf<Pair<IndentMarker, Float>?>(null) }
    Canvas(
        modifier.fillMaxWidth().height(RULER_HEIGHT).pointerInput(g, pageLeftPx, pxPerPt) {
            if (pxPerPt <= 0f) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val x = (down.position.x - pageLeftPx) / pxPerPt
                val marker = markerAt(g, x, down.position.y < size.height / 2f, reach = 8.dp.toPx() / pxPerPt) ?: return@awaitEachGesture
                down.consume()
                dragging = marker to x
                drag(down.id) { change ->
                    change.consume()
                    dragging = marker to (change.position.x - pageLeftPx) / pxPerPt
                }
                val end = dragging?.second
                dragging = null
                if (end != null) onIndents(indentParams(g, marker, end))
            }
        },
    ) {
        fun px(pt: Float) = pageLeftPx + pt * pxPerPt
        drawRect(shade)
        // The text area is white; the margins stay shaded.
        drawRect(Color.White, Offset(px(g.textLeft), 3.dp.toPx()), Size((g.textRight - g.textLeft) * pxPerPt, size.height - 6.dp.toPx()))
        drawTicks(g, ::px, ink, measurer, numberStyle)
        val shown = dragging
        fun at(m: IndentMarker, x: Float) = if (shown?.first == m) snap(shown.second) else x
        drawMarker(px(at(IndentMarker.FirstLine, g.firstLine)), top = true, color = if (shown?.first == IndentMarker.FirstLine) accent else ink)
        drawMarker(px(at(IndentMarker.Left, g.left)), top = false, color = if (shown?.first == IndentMarker.Left) accent else ink)
        drawMarker(px(at(IndentMarker.Right, g.right)), top = false, color = if (shown?.first == IndentMarker.Right) accent else ink)
        // While dragging, a line down the ruler shows where the indent lands.
        shown?.let { (_, x) -> drawLine(accent, Offset(px(snap(x)), 0f), Offset(px(snap(x)), size.height), 1.dp.toPx()) }
    }
}

/** Ticks every eighth of an inch from the left margin both ways, longer at halves, numbers at inches. */
private fun DrawScope.drawTicks(
    g: RulerGeometry,
    px: (Float) -> Float,
    ink: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle,
) {
    val step = 9f
    val first = -(g.textLeft / step).toInt()
    val last = ((g.pageWidth - g.textLeft) / step).toInt()
    val mid = size.height / 2f
    for (i in first..last) {
        val x = px(g.textLeft + i * step)
        when {
            i != 0 && i % 8 == 0 -> {
                val label = measurer.measure((abs(i) / 8).toString(), style)
                drawText(label, topLeft = Offset(x - label.size.width / 2f, mid - label.size.height / 2f))
            }
            i % 4 == 0 -> drawLine(ink.copy(alpha = 0.6f), Offset(x, mid - 3.dp.toPx()), Offset(x, mid + 3.dp.toPx()), 1f)
            else -> drawLine(ink.copy(alpha = 0.4f), Offset(x, mid - 1.dp.toPx()), Offset(x, mid + 1.dp.toPx()), 1f)
        }
    }
}

/** An indent marker: a small triangle at [x], hanging from the top edge ([top]) or standing on the bottom one. */
private fun DrawScope.drawMarker(x: Float, top: Boolean, color: Color) {
    val half = 5.dp.toPx()
    val h = 6.dp.toPx()
    val base = if (top) 0f else size.height
    val tip = if (top) h else size.height - h
    val p = Path().apply {
        moveTo(x - half, base)
        lineTo(x + half, base)
        lineTo(x, tip)
        close()
    }
    drawPath(p, Color.White)
    drawPath(p, color, style = Stroke(1.dp.toPx()))
}
