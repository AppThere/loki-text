package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

/** What the horizontal ruler measures: the page's width and its side margins, in points. */
class RulerInfo(val pageW: Float, val marginLeft: Float, val marginRight: Float)

/** The ruler's numbers from `layout.section`; null when the section's sizes are not usable. */
fun parseRulerInfo(e: JsonElement?): RulerInfo? {
    val o = e as? JsonObject ?: return null
    fun n(k: String) = (o[k] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() && it >= 0f }
    val w = n("pageW")?.takeIf { it > 0f && it <= 20_000f } ?: return null
    val left = n("marginLeft") ?: return null
    val right = n("marginRight") ?: return null
    if (left + right >= w) return null
    return RulerInfo(w, left, right)
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

/**
 * The horizontal ruler (View › Ruler) over the page area: the text area light between the darker
 * margins, inch marks from the left margin, and the paragraph's indents at the caret as markers.
 * [leftPx] is where the page's left edge is in this ruler, so it follows a page scrolled sideways.
 * It shows the measures; dragging its markers is not built (the Paragraph dialog sets indents).
 */
@Composable
internal fun HorizontalRuler(info: RulerInfo, layout: LayoutState, leftPx: Float, pxPerPt: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val margin = MaterialTheme.colorScheme.surfaceVariant
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val style = TextStyle(fontSize = 9.sp, color = ink)
    Canvas(modifier.fillMaxWidth().height(RULER_HEIGHT)) {
        fun x(pt: Float) = leftPx + pt * pxPerPt
        drawRect(margin)
        val textLeft = x(info.marginLeft)
        val textRight = x(info.pageW - info.marginRight)
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
        // The caret paragraph's indents: a marker hanging from the top at each side.
        fun marker(pt: Float) {
            val px = x(pt)
            val half = 4.dp.toPx()
            drawPath(Path().apply { moveTo(px - half, 0f); lineTo(px + half, 0f); lineTo(px, half * 1.4f); close() }, accent)
        }
        marker(info.marginLeft + (layout.indentLeft ?: 0f))
        marker(info.pageW - info.marginRight - (layout.indentRight ?: 0f))
    }
}
