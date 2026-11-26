package app.loki.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

// Lanes of clips against a time ruler: a video editor's timeline, an animation's layers, a
// sound editor's tracks. The app turns its engine's state into lanes (seconds), this draws them
// and says what was tapped; the engine stays the one that edits.

/** One block on a lane: [start]..[end] in seconds. */
class TimelineClip(val id: Long, val start: Double, val end: Double, val label: String, val selected: Boolean = false, val dim: Boolean = false, val color: Color? = null)

/** A row of the timeline. */
class TimelineLane(val id: Long, val label: String, val clips: List<TimelineClip>, val selected: Boolean = false, val dim: Boolean = false, val color: Color? = null)

/** A named point on the ruler. */
class TimelineMark(val at: Double, val label: String)

/** Seconds as m:ss, or with tenths below a second's spacing. */
fun rulerLabel(seconds: Double, fine: Boolean): String {
    val s = seconds.coerceAtLeast(0.0)
    val m = (s / 60).toInt(); val rest = s - m * 60
    return if (fine) "$m:" + (if (rest < 10) "0" else "") + fixed(rest, 1) else "$m:" + rest.toInt().toString().padStart(2, '0')
}

/** What a drag on a clip does: move the whole clip, or move its end (making it longer or shorter). */
enum class ClipDrag { Move, ResizeEnd }

/** The clip at time [at] in [lane] and what a press there would drag: its end if [at] is within [edge] of it, else the clip's body. */
fun hitClip(lane: TimelineLane, at: Double, edge: Double): Pair<TimelineClip, ClipDrag>? {
    // The nearest end first, so a clip that ends where the next begins can still be grabbed by its end.
    lane.clips.firstOrNull { kotlin.math.abs(at - it.end) <= edge && at >= it.start }?.let { return it to ClipDrag.ResizeEnd }
    return lane.clips.firstOrNull { at >= it.start && at < it.end }?.let { it to ClipDrag.Move }
}

/** [delta] moved to a multiple of [snap] (0: not at all). */
fun snapDelta(delta: Double, snap: Double): Double = if (snap > 0.0 && delta.isFinite()) kotlin.math.round(delta / snap) * snap else delta

/**
 * The timeline. A drag or the wheel scrolls it in time, two fingers or Ctrl and the wheel zoom it,
 * a tap on the ruler calls [onSeek] with that time, a tap in a lane calls [onTap] with the time, the lane
 * and the clip under the finger (null for none). [total] is the length that fits at the start.
 *
 * With [onDragClip], a drag that starts on a clip moves it, or its end if it starts at the end, instead of
 * scrolling: the clip follows the finger (to a multiple of [snap]) and [onDragClip] hears the whole
 * change once, when it is let go, with `done` true.
 */
@Composable
fun LaneTimeline(
    lanes: List<TimelineLane>,
    total: Double,
    playhead: Double,
    modifier: Modifier = Modifier,
    marks: List<TimelineMark> = emptyList(),
    laneHeight: Dp = 44.dp,
    onSeek: (Double) -> Unit = {},
    onTap: (TimelineLane, TimelineClip?, Double) -> Unit = { _, _, _ -> },
    onDragClip: ((TimelineLane, TimelineClip, ClipDrag, Double, Boolean) -> Unit)? = null,
    /** What a dragged clip's change is rounded to, in the timeline's unit (an animation's whole frames: 1). */
    snap: Double = 0.0,
    /** The ruler's spacings to choose from, smallest first, in the timeline's unit (seconds by default; an animation's frames). */
    steps: DoubleArray = SECOND_STEPS,
    /** What the ruler writes at a time; `fine` is true where the spacing is under one unit. */
    ruler: (Double, Boolean) -> String = ::rulerLabel,
) {
    val seek by rememberUpdatedState(onSeek)
    val tap by rememberUpdatedState(onTap)
    val dragClip by rememberUpdatedState(onDragClip)
    // A clip being dragged: which, what the drag does, and by how much so far.
    var preview by remember { mutableStateOf<Triple<Long, Long, Pair<ClipDrag, Double>>?>(null) }
    val laneList by rememberUpdatedState(lanes)
    var perPx by remember { mutableFloatStateOf(0f) }
    var scroll by remember { mutableFloatStateOf(0f) }
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val base = Color(0xFF3E7BD4)
    val text = TextStyle(color = cs.onSurface, fontSize = TextUnit(11f, TextUnitType.Sp))
    val maxPerPx = (total.coerceAtLeast(1.0) / 40.0).toFloat().coerceAtLeast(0.01f)
    Box(modifier.background(cs.background)) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (perPx <= 0f) return@detectTransformGestures
                        val at = scroll + centroid.x * perPx
                        perPx = (perPx / zoom).coerceIn(0.0005f, maxPerPx)
                        scroll = (at - centroid.x * perPx - pan.x * perPx).coerceAtLeast(0f)
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: continue
                            if (e.type == PointerEventType.Scroll && perPx > 0f) {
                                val d = c.scrollDelta
                                if (e.keyboardModifiers.isCtrlPressed) {
                                    val at = scroll + c.position.x * perPx
                                    perPx = (perPx * (if (d.y > 0) 1.15f else 1 / 1.15f)).coerceIn(0.0005f, maxPerPx)
                                    scroll = (at - c.position.x * perPx).coerceAtLeast(0f)
                                } else scroll = (scroll + (d.x + d.y) * 30 * perPx).coerceAtLeast(0f)
                                c.consume()
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val rulerH = RULER.toPx(); val laneH = laneHeight.toPx()
                        val at = (scroll + p.x * perPx).toDouble().coerceAtLeast(0.0)
                        if (p.y < rulerH) { seek(at); return@detectTapGestures }
                        val lane = laneList.getOrNull(((p.y - rulerH) / laneH).toInt()) ?: return@detectTapGestures
                        tap(lane, lane.clips.firstOrNull { at >= it.start && at < it.end }, at)
                    }
                }
                // Innermost, so it sees a press first: one that lands on a clip is a drag of it, and is not the scroll below.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val dragger = dragClip ?: return@awaitEachGesture
                        val rulerH = RULER.toPx(); val laneH = laneHeight.toPx()
                        if (down.position.y < rulerH || perPx <= 0f) return@awaitEachGesture
                        val lane = laneList.getOrNull(((down.position.y - rulerH) / laneH).toInt()) ?: return@awaitEachGesture
                        val at = (scroll + down.position.x * perPx).toDouble()
                        val (clip, kind) = hitClip(lane, at, edge = (8.dp.toPx() * perPx).toDouble()) ?: return@awaitEachGesture
                        var moved = false
                        var delta = 0.0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            delta = snapDelta(((ch.position.x - down.position.x) * perPx).toDouble(), snap)
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) { preview = Triple(lane.id, clip.id, kind to delta); ch.consume() }
                            if (!ch.pressed) break
                        }
                        preview = null
                        if (moved && delta != 0.0) dragger(lane, clip, kind, delta, true)
                    }
                },
        ) {
            if (perPx <= 0f) perPx = (total.coerceAtLeast(1.0) / size.width.coerceAtLeast(1f)).toFloat()
            val rulerH = RULER.toPx(); val laneH = laneHeight.toPx()
            fun x(t: Double) = ((t - scroll) / perPx).toFloat()
            fun label(s: String, at: Offset, style: TextStyle, width: Float = size.width - at.x) {
                if (at.x < 0f || at.y < 0f || width < 4f || size.height - at.y < 4f) return
                drawText(measurer, s, at, style, maxLines = 1, overflow = TextOverflow.Clip, size = Size(width, minOf(20.dp.toPx(), size.height - at.y)))
            }
            lanes.forEachIndexed { i, lane ->
                val top = rulerH + i * laneH
                drawRect(if (i % 2 == 0) cs.surface else cs.surfaceVariant.copy(alpha = 0.5f), Offset(0f, top), Size(size.width, laneH))
                if (lane.selected) drawRect(base.copy(alpha = 0.12f), Offset(0f, top), Size(size.width, laneH))
                drawLine(cs.outlineVariant, Offset(0f, top + laneH), Offset(size.width, top + laneH))
                for (c in lane.clips) {
                    val pv = preview?.takeIf { it.first == lane.id && it.second == c.id }?.third
                    val x0 = x(if (pv?.first == ClipDrag.Move) c.start + pv.second else c.start)
                    val x1 = x(when (pv?.first) { ClipDrag.Move -> c.end + pv.second; ClipDrag.ResizeEnd -> maxOf(c.end + pv.second, c.start + maxOf(snap, 0.0001)); null -> c.end })
                    if (x1 < 0 || x0 > size.width) continue
                    val w = (x1 - x0).coerceAtLeast(2f)
                    val colour = c.color ?: lane.color ?: base
                    drawRoundRect(if (c.dim || lane.dim) colour.copy(alpha = 0.35f) else colour, Offset(x0, top + 3.dp.toPx()), Size(w, laneH - 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                    drawRoundRect(if (c.selected) Color.White else Color.Black.copy(alpha = 0.4f), Offset(x0, top + 3.dp.toPx()), Size(w, laneH - 6.dp.toPx()), CornerRadius(3.dp.toPx()), style = Stroke(if (c.selected) 2.5f else 1f))
                    label(c.label, Offset(maxOf(x0, 0f) + 5.dp.toPx(), top + 6.dp.toPx()), text.copy(color = Color.White), minOf(x1 - maxOf(x0, 0f) - 8.dp.toPx(), size.width - maxOf(x0, 0f) - 5.dp.toPx()))
                }
                label(lane.label, Offset(4.dp.toPx(), top + laneH - 16.dp.toPx()), text.copy(color = cs.onSurfaceVariant), 120.dp.toPx())
            }
            drawRect(cs.surfaceVariant, Offset.Zero, Size(size.width, rulerH))
            val step = steps.firstOrNull { it / perPx >= 70 } ?: steps.last()
            var s = floor(scroll / step) * step
            while ((s - scroll) / perPx < size.width) {
                val px = ((s - scroll) / perPx).toFloat()
                drawLine(cs.outline, Offset(px, rulerH - 8.dp.toPx()), Offset(px, rulerH))
                label(ruler(s, step < 1.0), Offset(px + 3.dp.toPx(), 2.dp.toPx()), text.copy(color = cs.onSurfaceVariant))
                s += step
            }
            for (m in marks) {
                val px = x(m.at)
                drawLine(Color(0xFFE5A100), Offset(px, rulerH - 10.dp.toPx()), Offset(px, size.height), strokeWidth = 1f)
                label(m.label, Offset(px + 3.dp.toPx(), rulerH - 16.dp.toPx()), text.copy(color = Color(0xFFE5A100)))
            }
            val ph = x(playhead)
            drawLine(Color(0xFFE5484D), Offset(ph, 0f), Offset(ph, size.height), strokeWidth = 2f)
        }
    }
}

/** The ruler's spacings for a timeline in seconds. */
val SECOND_STEPS: DoubleArray = doubleArrayOf(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0)

private val RULER = 28.dp
