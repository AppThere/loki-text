package app.loki.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

// A box with handles to scale and turn what is selected, sized for a finger. The engine (or the
// app) does the transform; this is where the handles are, which one a press is on, and the shape
// a drag turns the box into.

/** A place to grab a box: its four corners, the middles of its four sides, and the knob that turns it. */
enum class BoxGrab { TopLeft, TopRight, BottomRight, BottomLeft, Top, Right, Bottom, Left, Rotate }

/** Keys held with a finger or pen, which have no Shift or Alt: the options bar's chips stand in (sticky until turned off). */
object TouchModifiers {
    var shift by mutableStateOf(false)
    var alt by mutableStateOf(false)
}

/** Shift and Alt chips for the options bar, on a touch screen only. */
@Composable
fun ModifierChips() {
    if (!isTouchPlatform) return
    Row {
        ToggleChip("Shift", TouchModifiers.shift) { TouchModifiers.shift = it }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.padding(horizontal = 4.dp))
        ToggleChip("Alt", TouchModifiers.alt) { TouchModifiers.alt = it }
    }
}


/**
 * The handles of a box whose corners are [corners] (document space: top-left, top-right,
 * bottom-right, bottom-left of the box itself, so a turned box works). [hit] and [knob] are in
 * screen pixels.
 */
class TransformBox(val corners: List<Offset>, val hit: Float = if (isTouchPlatform) 26f else 9f, val knob: Float = if (isTouchPlatform) 56f else 26f) {
    private val center get() = Offset(corners.map { it.x }.average().toFloat(), corners.map { it.y }.average().toFloat())

    /**
     * The corner the turning knob stands outside: the top right, or the next of the others whose
     * knob would still be on the canvas (a box that fills the view has no room outside its top right).
     */
    private fun knobCorner(view: CanvasView): Int {
        val v = view.viewport
        for (i in listOf(1, 0, 2, 3)) {
            val d = corners[i] - center
            val len = hypot(d.x, d.y).takeIf { it > 1e-6f } ?: continue
            val k = view.toScreen((corners[i].x + d.x * knob / view.zoom.coerceAtLeast(1e-6f) / len).toDouble(), (corners[i].y + d.y * knob / view.zoom.coerceAtLeast(1e-6f) / len).toDouble())
            val m = hit * 0.75f
            if (v.width <= 0f || (k.x in m..(v.width - m) && k.y in m..(v.height - m))) return i
        }
        return -1
    }

    /** The knob straight out from the top side's middle: where a box that fills the view has room for it. */
    private fun aboveTop(distance: Float): Offset {
        val m = mid(0, 1)
        val d = m - center
        val len = hypot(d.x, d.y).takeIf { it > 1e-6f } ?: return m
        return m + d * (distance / len)
    }

    /** Where [g]'s handle is, in document space. */
    fun point(g: BoxGrab, view: CanvasView): Offset = when (g) {
        BoxGrab.TopLeft -> corners[0]; BoxGrab.TopRight -> corners[1]; BoxGrab.BottomRight -> corners[2]; BoxGrab.BottomLeft -> corners[3]
        BoxGrab.Top -> mid(0, 1); BoxGrab.Right -> mid(1, 2); BoxGrab.Bottom -> mid(2, 3); BoxGrab.Left -> mid(3, 0)
        BoxGrab.Rotate -> knobCorner(view).let { if (it < 0) aboveTop(knob / view.zoom.coerceAtLeast(1e-6f)) else outward(it, knob / view.zoom.coerceAtLeast(1e-6f)) }
    }

    /**
     * Where a press must be for an engine that has its own handles (their hit sizes are for a
     * mouse): the handle itself, or for the knob a spot just outside the top-right corner, in the
     * engine's rotate zone. A press by finger is sent here, and the drag carries the finger's offset from it.
     */
    fun enginePoint(g: BoxGrab, view: CanvasView): Offset =
        if (g == BoxGrab.Rotate) knobCorner(view).let { outward(it.takeIf { c -> c >= 0 } ?: 1, ENGINE_ROTATE_ZONE / view.zoom.coerceAtLeast(1e-6f)) } else point(g, view)

    private fun mid(a: Int, b: Int) = (corners[a] + corners[b]) / 2f

    /** The point [distance] (document units) beyond corner [i], straight away from the box's centre. */
    private fun outward(i: Int, distance: Float): Offset {
        val d = corners[i] - center
        val len = hypot(d.x, d.y).takeIf { it > 1e-6f } ?: return corners[i]
        return corners[i] + d * (distance / len)
    }

    /**
     * What a press at [p] (document space) is on, or null. A side's middle is skipped when the
     * side is too short on screen to tell it from its corners.
     */
    fun grabAt(p: Offset, view: CanvasView): BoxGrab? {
        val screen = { g: BoxGrab -> point(g, view).let { view.toScreen(it.x.toDouble(), it.y.toDouble()) } }
        val at = view.toScreen(p.x.toDouble(), p.y.toDouble())
        val order = listOf(BoxGrab.Rotate, BoxGrab.TopLeft, BoxGrab.TopRight, BoxGrab.BottomRight, BoxGrab.BottomLeft, BoxGrab.Top, BoxGrab.Right, BoxGrab.Bottom, BoxGrab.Left)
        for (g in order) {
            val s = screen(g)
            if (hypot(s.x - at.x, s.y - at.y) > hit) continue
            if (g in EDGES && sideScreenLength(g, view) < hit * 3.2f) continue
            return g
        }
        return null
    }

    private fun sideScreenLength(g: BoxGrab, view: CanvasView): Float {
        val (a, b) = when (g) { BoxGrab.Top -> 0 to 1; BoxGrab.Right -> 1 to 2; BoxGrab.Bottom -> 2 to 3; else -> 3 to 0 }
        val pa = view.toScreen(corners[a].x.toDouble(), corners[a].y.toDouble()); val pb = view.toScreen(corners[b].x.toDouble(), corners[b].y.toDouble())
        return hypot(pa.x - pb.x, pa.y - pb.y)
    }

    /** The box and its handles, in screen space. */
    fun DrawScope.draw(view: CanvasView, accent: Color, active: BoxGrab? = null) {
        val s = corners.map { view.toScreen(it.x.toDouble(), it.y.toDouble()) }
        for (i in 0 until 4) drawLine(accent, s[i], s[(i + 1) % 4], strokeWidth = 1.5f)
        val half = if (isTouchPlatform) 8f else 4.5f
        fun square(c: Offset, on: Boolean) {
            drawRect(if (on) accent else Color.White, c - Offset(half, half), Size(half * 2, half * 2))
            drawRect(accent, c - Offset(half, half), Size(half * 2, half * 2), style = Stroke(1.5f))
        }
        for (g in BoxGrab.entries) {
            if (g == BoxGrab.Rotate) continue
            if (g in EDGES && sideScreenLength(g, view) < hit * 3.2f) continue
            square(point(g, view).let { view.toScreen(it.x.toDouble(), it.y.toDouble()) }, g == active)
        }
        val k = point(BoxGrab.Rotate, view).let { view.toScreen(it.x.toDouble(), it.y.toDouble()) }
        drawLine(accent.copy(alpha = 0.6f), knobCorner(view).let { if (it < 0) (s[0] + s[1]) / 2f else s[it] }, k, strokeWidth = 1f)
        drawCircle(if (active == BoxGrab.Rotate) accent else Color.White, half + 1.5f, k)
        drawCircle(accent, half + 1.5f, k, style = Stroke(1.5f))
    }

    companion object {
        /** Screen pixels outside a corner where VectorCraft turns the selection (its zone runs from 5 to 18). */
        const val ENGINE_ROTATE_ZONE = 11f
        val EDGES = setOf(BoxGrab.Top, BoxGrab.Right, BoxGrab.Bottom, BoxGrab.Left)

        /**
         * The corners of an axis-aligned box (top-left, top-right, bottom-right, bottom-left) after
         * dragging handle [g] from [start] to [now]: the opposite handle stays put, or the centre with
         * [fromCenter] (Alt); [keepAspect] (Shift) scales both ways together. Never degenerate.
         */
        fun scaled(c: List<Offset>, g: BoxGrab, start: Offset, now: Offset, keepAspect: Boolean, fromCenter: Boolean): List<Offset> {
            val x0 = c[0].x; val y0 = c[0].y; val x1 = c[2].x; val y1 = c[2].y
            val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
            val ax = when (g) { BoxGrab.TopLeft, BoxGrab.BottomLeft, BoxGrab.Left -> -1; BoxGrab.TopRight, BoxGrab.BottomRight, BoxGrab.Right -> 1; else -> 0 }
            val ay = when (g) { BoxGrab.TopLeft, BoxGrab.TopRight, BoxGrab.Top -> -1; BoxGrab.BottomLeft, BoxGrab.BottomRight, BoxGrab.Bottom -> 1; else -> 0 }
            // The fixed point: the side or corner opposite the handle, or the centre.
            val ox = if (fromCenter || ax == 0) cx else if (ax < 0) x1 else x0
            val oy = if (fromCenter || ay == 0) cy else if (ay < 0) y1 else y0
            val sx0 = (if (ax < 0) x0 else if (ax > 0) x1 else cx) - ox
            val sy0 = (if (ay < 0) y0 else if (ay > 0) y1 else cy) - oy
            fun factor(startD: Float, nowD: Float) = if (abs(startD) < 1e-6f) 1f else (nowD / startD).let { if (abs(it) < 0.01f) 0.01f * (if (it < 0) -1f else 1f) else it }
            var kx = if (ax == 0) 1f else factor(sx0, now.x - ox)
            var ky = if (ay == 0) 1f else factor(sy0, now.y - oy)
            if (keepAspect) {
                val k = when { ax == 0 -> ky; ay == 0 -> kx; abs(kx) >= abs(ky) -> kx; else -> ky }
                kx = k; ky = k
            }
            fun map(p: Offset) = Offset(ox + (p.x - ox) * kx, oy + (p.y - oy) * ky)
            return c.map(::map)
        }

        /** [c] turned about its centre by the angle from [start] to [now] as seen from there; [snap] (Shift) rounds it to 15°. */
        fun rotated(c: List<Offset>, start: Offset, now: Offset, snap: Boolean): List<Offset> {
            val cx = c.map { it.x }.average().toFloat(); val cy = c.map { it.y }.average().toFloat()
            var a = atan2(now.y - cy, now.x - cx) - atan2(start.y - cy, start.x - cx)
            if (snap) { val step = (15.0 * PI / 180.0).toFloat(); a = round(a / step) * step }
            val co = cos(a); val si = sin(a)
            return c.map { Offset(cx + (it.x - cx) * co - (it.y - cy) * si, cy + (it.x - cx) * si + (it.y - cy) * co) }
        }
    }
}
