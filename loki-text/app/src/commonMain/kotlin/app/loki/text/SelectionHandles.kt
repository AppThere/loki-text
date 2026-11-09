package app.loki.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.loki.text.engine.PageRect
import kotlin.math.roundToInt

/** Which end of the selection a handle sits on. */
enum class HandleEnd { Start, End }

/**
 * A selection handle's place: the [page], the point in page points on the text line's baseline
 * side where the handle hangs ([x], [bottom]), and the middle of that line ([mid]), which is where
 * a drag aims so the pointer position resolves to this line.
 */
class HandlePoint(val page: Int, val x: Float, val bottom: Float, val mid: Float)

/**
 * The two handles for a selection's boxes (reading order, as the engine reports them): the start
 * handle at the left of the first box, the end handle at the right of the last. Null when nothing
 * is selected or a box is not finite.
 */
fun selectionHandles(rects: List<PageRect>): Pair<HandlePoint, HandlePoint>? {
    val first = rects.firstOrNull() ?: return null
    val last = rects.lastOrNull() ?: return null
    fun ok(r: PageRect) = r.x.isFinite() && r.y.isFinite() && r.w.isFinite() && r.h.isFinite()
    if (!ok(first) || !ok(last)) return null
    return HandlePoint(first.page, first.x, first.y + first.h, first.y + first.h / 2) to
        HandlePoint(last.page, last.x + last.w, last.y + last.h, last.y + last.h / 2)
}

/** The size of the touch target around a handle; the drawn knob is smaller. */
private val TOUCH_TARGET = 44.dp
private val KNOB_RADIUS = 8.dp
private val HANDLE_COLOR = Color(0xFF1E6FE0)

/**
 * A draggable selection handle on a page image. It hangs below its line at [at] ([pxPerPt] turns
 * points into the pixels of the page image). Dragging reports where the pointer is, as a page
 * pixel position aimed at the line's middle, to [onDrag]; [onStart] runs first, so the other end
 * of the selection can be fixed as the anchor.
 */
@Composable
fun SelectionHandle(
    end: HandleEnd,
    at: HandlePoint,
    pxPerPt: Float,
    onStart: (HandleEnd) -> Unit,
    onDrag: (HandleEnd, Offset) -> Unit,
    /** Runs when the finger lifts or the drag is cancelled. */
    onEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    val target = with(density) { TOUCH_TARGET.roundToPx() }
    val knob = with(density) { KNOB_RADIUS.toPx() }
    val latest by rememberUpdatedState(at)
    val latestScale by rememberUpdatedState(pxPerPt)
    var finger by remember { mutableStateOf(Offset.Zero) }
    // The touch target is centred just under the line, so the knob never hides the text it selects.
    val left = (at.x * pxPerPt).roundToInt() - target / 2
    val top = (at.bottom * pxPerPt).roundToInt()
    Box(
        Modifier
            .offset { IntOffset(left, top) }
            .size(TOUCH_TARGET)
            .pointerInput(end) {
                detectDragGestures(
                    onDragStart = {
                        finger = Offset(latest.x * latestScale, latest.mid * latestScale)
                        onStart(end)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        finger += amount
                        onDrag(end, finger)
                    },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() },
                )
            },
    ) {
        Canvas(Modifier.size(TOUCH_TARGET)) {
            val c = Offset(size.width / 2, knob + 2f)
            drawCircle(HANDLE_COLOR, knob, c)
            // The stem joins the knob to the text.
            drawRect(HANDLE_COLOR, Offset(c.x - 1.5f, 0f), androidx.compose.ui.geometry.Size(3f, c.y))
        }
    }
}
