package com.appthere.loki.text

import com.appthere.loki.kit.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.appthere.loki.text.engine.ObjectFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** The eight resize handles; ([sx], [sy]) is the side each one drags: -1 left or top, 1 right or bottom, 0 neither. */
enum class FrameHandle(val sx: Int, val sy: Int) {
    TopLeft(-1, -1), Top(0, -1), TopRight(1, -1), Left(-1, 0), Right(1, 0), BottomLeft(-1, 1), Bottom(0, 1), BottomRight(1, 1);

    /** A corner, which keeps a picture's proportions. */
    val corner: Boolean get() = sx != 0 && sy != 0
}

/** A frame's box, before rotation, in page points, and its turn about the centre in degrees. */
class FrameRect(val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float) {
    val cx: Float get() = x + w / 2f
    val cy: Float get() = y + h / 2f
}

internal fun ObjectFrame.rect() = FrameRect(x, y, w, h, rotation)

private fun rad(deg: Float) = deg * (PI.toFloat() / 180f)

/** [v] turned clockwise by [deg] (y points down on a page, so that is a standard rotation). */
internal fun rotateVec(v: Offset, deg: Float): Offset {
    val a = rad(deg)
    return Offset(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
}

/** Where handle [h] of [f] is on the page, in points: the box's corner or edge middle, turned about the centre. */
internal fun handlePoint(f: FrameRect, h: FrameHandle): Offset =
    Offset(f.cx, f.cy) + rotateVec(Offset(h.sx * f.w / 2f, h.sy * f.h / 2f), f.rotation)

/** Where the rotation handle is: [gap] points above the top edge's middle, turned with the frame. */
internal fun rotateHandlePoint(f: FrameRect, gap: Float): Offset =
    Offset(f.cx, f.cy) + rotateVec(Offset(0f, -f.h / 2f - gap), f.rotation)

/** [f] moved by ([dx], [dy]) page points. */
internal fun moveFrame(f: FrameRect, dx: Float, dy: Float): FrameRect = FrameRect(f.x + dx, f.y + dy, f.w, f.h, f.rotation)

/**
 * [f] with handle [h] dragged by ([dx], [dy]) page points. The far side stays where it is, also when the frame is
 * turned: the drag is measured along the frame's own axes, and the centre moves to keep the far side fixed. A
 * corner with [keepAspect] scales both sides together. Neither side goes below [min] points. Not finite input
 * leaves the frame as it was.
 */
internal fun resizeFrame(f: FrameRect, h: FrameHandle, dx: Float, dy: Float, keepAspect: Boolean, min: Float = 4f): FrameRect {
    if (!dx.isFinite() || !dy.isFinite() || f.w <= 0f || f.h <= 0f) return f
    val d = rotateVec(Offset(dx, dy), -f.rotation)
    var w = if (h.sx == 0) f.w else max(min, f.w + h.sx * d.x)
    var hh = if (h.sy == 0) f.h else max(min, f.h + h.sy * d.y)
    if (keepAspect && h.corner) {
        val k = max(w / f.w, hh / f.h)
        w = max(min, f.w * k); hh = max(min, f.h * k)
    }
    // The dragged side moved by (w - f.w) / (hh - f.h) along the frame's axes; the centre moves half of that.
    val shift = rotateVec(Offset(h.sx * (w - f.w) / 2f, h.sy * (hh - f.h) / 2f), f.rotation)
    val cx = f.cx + shift.x
    val cy = f.cy + shift.y
    return FrameRect(cx - w / 2f, cy - hh / 2f, w, hh, f.rotation)
}

/**
 * The turn (degrees clockwise, 0–360) that points the frame's top at the page point ([px], [py]), about its
 * centre; 0 is straight up. Close to a multiple of 15° it snaps to it, so upright and square are easy to hit.
 */
internal fun rotationFor(f: FrameRect, px: Float, py: Float): Float {
    if (!px.isFinite() || !py.isFinite()) return f.rotation
    var deg = atan2(py - f.cy, px - f.cx) * (180f / PI.toFloat()) + 90f
    deg = ((deg % 360f) + 360f) % 360f
    val snapped = (deg / 15f).roundToInt() * 15f
    return (if (kotlin.math.abs(snapped - deg) <= 3f) snapped else deg).let { ((it % 360f) + 360f) % 360f }
}

/** The id the Crop button runs: crop handles on the selected picture, on or off. */
internal const val CROP_MODE = "app.cropMode"

/** The most of one axis a crop may take: the two sides keep at least 5% of the picture between them. */
private const val MAX_CROP_AXIS = 0.95f

/**
 * `picture.crop` for a crop handle [h] dragged from the picture's frame [from] to [to] (the visible part, as
 * [resizeFrame] moves the dragged side), given its crop now ([crop]: left, top, right, bottom as fractions of the
 * source). Each point the side moves in is the share of the source that a point of the frame shows; dragging out
 * gives back what was cropped, never more. The new display size keeps the picture's scale. Null when nothing changed.
 */
internal fun cropDragParams(from: FrameRect, to: FrameRect, h: FrameHandle, crop: List<Float>): JsonObject? {
    if (crop.size != 4 || from.w <= 0f || from.h <= 0f) return null
    val (l, t, r, b) = crop
    val shownX = (1f - l - r).coerceAtLeast(0.05f)
    val shownY = (1f - t - b).coerceAtLeast(0.05f)
    // Points trimmed (positive) or given back (negative) on each side.
    val trimX = from.w - to.w
    val trimY = from.h - to.h
    fun side(was: Float, trim: Float, perPt: Float, other: Float) = (was + trim * perPt).coerceIn(0f, (MAX_CROP_AXIS - other).coerceAtLeast(0f))
    val nl = if (h.sx == -1) side(l, trimX, shownX / from.w, r) else l
    val nr = if (h.sx == 1) side(r, trimX, shownX / from.w, l) else r
    val nt = if (h.sy == -1) side(t, trimY, shownY / from.h, b) else t
    val nb = if (h.sy == 1) side(b, trimY, shownY / from.h, t) else b
    if (nl == l && nr == r && nt == t && nb == b) return null
    return buildJsonObject {
        put("left", nl); put("top", nt); put("right", nr); put("bottom", nb)
        put("width", from.w * (1f - nl - nr) / shownX)
        put("height", from.h * (1f - nt - nb) / shownY)
    }
}

/** The lines on a page a moving object snaps to, in page points: its edges, margins and middle, across and down. */
class PageGuides(val xs: List<Float>, val ys: List<Float>)

/** The page's guides from the section's page and margins (the top and bottom ones only when the engine gave them). */
internal fun pageGuides(info: RulerInfo): PageGuides = PageGuides(
    listOf(0f, info.marginLeft, info.pageW / 2f, info.pageW - info.marginRight, info.pageW),
    if (info.pageH > 0f) listOf(0f, info.marginTop, info.pageH / 2f, info.pageH - info.marginBottom, info.pageH) else emptyList(),
)

/**
 * [f] moved so the nearest of its left edge, middle or right edge (and top, middle or bottom) that is within [tol] points
 * of a guide lies on it; and the guides it lies on, across ([GuideLines.x]) and down ([GuideLines.y]), to draw.
 */
internal fun snapToGuides(f: FrameRect, g: PageGuides, tol: Float): Pair<FrameRect, GuideLines> {
    fun best(edges: List<Float>, lines: List<Float>): Pair<Float, Float>? =
        edges.flatMap { e -> lines.map { line -> (line - e) to line } }.filter { kotlin.math.abs(it.first) <= tol }.minByOrNull { kotlin.math.abs(it.first) }
    val bx = best(listOf(f.x, f.cx, f.x + f.w), g.xs)
    val by = best(listOf(f.y, f.cy, f.y + f.h), g.ys)
    val moved = FrameRect(f.x + (bx?.first ?: 0f), f.y + (by?.first ?: 0f), f.w, f.h, f.rotation)
    return moved to GuideLines(bx?.second, by?.second)
}

/** The guides a moving object lies on: an up-and-down line at [x], an across one at [y]; null for none. */
data class GuideLines(val x: Float?, val y: Float?)

private val GUIDE_COLOR = Color(0xFFD6336C)
private val CROP_COLOR = Color(0xFF111111)

private val FRAME_COLOR = Color(0xFF1E6FE0)

/**
 * A handle's drag, measured from where the pointer went down (so the touch slop counts: the edge lands where the finger
 * does). [onMove] gets the total so far in pixels; [onEnd] when it lifts after moving; [onCancel] when it never moved.
 */
private fun Modifier.handleDrag(key: Any, onMove: (Offset) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val first = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
        if (first == null) { onCancel(); return@awaitEachGesture }
        onMove(first.position - start)
        val finished = drag(first.id) { change -> change.consume(); onMove(change.position - start) }
        if (finished) onEnd() else onCancel()
    }
}
private val TOUCH_TARGET = 40.dp
private val KNOB = 6.dp
private const val ROTATE_GAP_DP = 28f

/** The command that applies [to] to a frame that was [from]: Move or Resize (`arrange.bounds`) with only what changed. */
internal fun boundsParams(from: ObjectFrame, to: FrameRect): JsonObject = buildJsonObject {
    put("page", from.page)
    put("x", to.x); put("y", to.y)
    if (to.w != from.w) put("width", to.w)
    if (to.h != from.h) put("height", to.h)
}

/**
 * The frame of the selected object over its page: an outline turned with the object, eight resize handles and a
 * rotation handle. Dragging the body moves a floating object; a handle resizes; the rotation handle turns it.
 * While a finger is down only the outline follows, and the change is one command when it lifts ([onCommit]).
 * With Ctrl held a drag of the body makes a copy where it lands instead (`arrange.copyTo`); an object in the text is moved or copied
 * to the text position under the drop ([onDropAt]). [pxPerPt] turns page points into the page image's pixels.
 */
@Composable
fun BoxScope.ObjectFrameOverlay(
    frame: ObjectFrame,
    pxPerPt: Float,
    /** The picture's crop now, while Crop is on: then the frame shows crop handles, which trim it. */
    crop: List<Float>? = null,
    /** The page's lines a floating object snaps to while it moves; null for none. */
    guides: PageGuides? = null,
    onDropAt: (x: Float, y: Float, copy: Boolean) -> Unit = { _, _, _ -> },
    onCommit: (String, JsonObject) -> Unit,
) {
    // A connector is dragged by its two ends, not framed.
    frame.ends?.let { ends -> ConnectorEnds(frame.page, ends, pxPerPt, onCommit); return }
    if (crop != null) { CropHandles(frame, crop, pxPerPt, onCommit); return }
    val density = LocalDensity.current
    val target = with(density) { TOUCH_TARGET.roundToPx() }
    val knob = with(density) { KNOB.toPx() }
    val latestFrame by rememberUpdatedState(frame)
    val latestScale by rememberUpdatedState(pxPerPt)
    // What the outline shows while a drag is in progress; it starts again from the real frame when that changes.
    var preview by remember(frame) { mutableStateOf<FrameRect?>(null) }
    // The guides the moving object lies on, drawn across the page while it moves.
    var snapped by remember(frame) { mutableStateOf<GuideLines?>(null) }
    val latestGuides by rememberUpdatedState(guides)
    val shown = preview ?: frame.rect()
    val real = frame.rect()
    val gap = ROTATE_GAP_DP * density.density / pxPerPt

    // The outline, drawn over the page without taking touches.
    Canvas(Modifier.matchParentSize()) {
        val k = pxPerPt
        rotate(shown.rotation, pivot = Offset(shown.cx * k, shown.cy * k)) {
            drawRect(FRAME_COLOR, Offset(shown.x * k, shown.y * k), Size(shown.w * k, shown.h * k), style = Stroke(2f))
            drawLine(FRAME_COLOR, Offset(shown.cx * k, shown.y * k), Offset(shown.cx * k, (shown.y - gap) * k), 2f)
        }
        for (h in FrameHandle.values()) drawCircle(FRAME_COLOR, knob, handlePoint(shown, h) * k)
        drawCircle(FRAME_COLOR, knob * 1.3f, rotateHandlePoint(shown, gap) * k)
        snapped?.let { s ->
            s.x?.let { x -> drawLine(GUIDE_COLOR, Offset(x * k, 0f), Offset(x * k, size.height), 1.5f) }
            s.y?.let { y -> drawLine(GUIDE_COLOR, Offset(0f, y * k), Offset(size.width, y * k), 1.5f) }
        }
    }

    // Dragging the body moves a floating object, or with Ctrl copies it; an object in the text is dropped at a text position. The
    // area turns with the frame, so its drags are in the frame's axes.
    run {
        val inline = !frame.floating
        Box(
            Modifier
                .offset { IntOffset((real.x * pxPerPt).roundToInt(), (real.y * pxPerPt).roundToInt()) }
                .size(with(density) { (real.w * pxPerPt).toDp() }, with(density) { (real.h * pxPerPt).toDp() })
                .graphicsLayer { rotationZ = real.rotation }
                .pointerInput(inline) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var total = Offset.Zero
                        var copy = false
                        var moved = false
                        val first = awaitTouchSlopOrCancellation(down.id) { change, over -> change.consume(); total += rotateVec(over, latestFrame.rotation); moved = true }
                        if (first == null) return@awaitEachGesture
                        fun show() {
                            if (inline) return
                            val moved = moveFrame(latestFrame.rect(), total.x / latestScale, total.y / latestScale)
                            // Six pixels on screen, whatever the zoom; Alt moves freely, as in other editors.
                            val g = latestGuides?.takeIf { !currentEvent.keyboardModifiers.isAltPressed }
                            if (g == null) { preview = moved; snapped = null } else {
                                val (at, lines) = snapToGuides(moved, g, 6.dp.toPx() / latestScale)
                                preview = at
                                snapped = lines.takeIf { it.x != null || it.y != null }
                            }
                        }
                        show()
                        drag(first.id) { change ->
                            change.consume()
                            copy = currentEvent.keyboardModifiers.let { it.isCtrlPressed || it.isMetaPressed }
                            total += rotateVec(change.positionChange(), latestFrame.rotation)
                            show()
                        }
                        val f = latestFrame
                        if (inline) {
                            // Dropped where the pointer ended, in page points.
                            onDropAt(f.x + f.w / 2f + total.x / latestScale, f.y + f.h / 2f + total.y / latestScale, copy)
                        } else {
                            preview?.let { p -> if (copy) onCommit("arrange.copyTo", buildJsonObject { put("page", f.page); put("x", p.x); put("y", p.y) }) else onCommit("arrange.bounds", boundsParams(f, p)) }
                        }
                        preview = null
                        snapped = null
                    }
                },
        )
    }

    // A handle is a touch target centred on its place; its own place stays put during the drag (the outline moves).
    for (h in FrameHandle.values()) {
        val at = handlePoint(real, h) * pxPerPt
        Box(
            Modifier
                .offset { IntOffset(at.x.roundToInt() - target / 2, at.y.roundToInt() - target / 2) }
                .size(TOUCH_TARGET)
                .handleDrag(
                    h,
                    onMove = { total -> preview = resizeFrame(latestFrame.rect(), h, total.x / latestScale, total.y / latestScale, keepAspect = latestFrame.kind == "image" && h.corner) },
                    onEnd = { preview?.let { onCommit("arrange.bounds", boundsParams(latestFrame, it)) }; preview = null },
                    onCancel = { preview = null },
                ),
        )
    }

    // The rotation handle: the pointer's place about the centre sets the turn.
    run {
        val at = rotateHandlePoint(real, gap) * pxPerPt
        Box(
            Modifier
                .offset { IntOffset(at.x.roundToInt() - target / 2, at.y.roundToInt() - target / 2) }
                .size(TOUCH_TARGET)
                .pointerInput(Unit) {
                    var finger = Offset.Zero
                    detectDragGestures(
                        onDragStart = { finger = rotateHandlePoint(latestFrame.rect(), gap) },
                        onDrag = { change, amount ->
                            change.consume()
                            finger += amount / latestScale
                            val f = latestFrame.rect()
                            preview = FrameRect(f.x, f.y, f.w, f.h, rotationFor(f, finger.x, finger.y))
                        },
                        onDragEnd = { preview?.let { onCommit("arrange.rotation", buildJsonObject { put("degrees", it.rotation) }) }; preview = null },
                        onDragCancel = { preview = null },
                    )
                },
        )
    }
}

/** How near (dp) a press must be to a connector's end to grab it. */
private val END_TOUCH = 40.dp

/**
 * A selected connector: a handle at each end. Dragging one shows a line from the other end to the pointer, and on release
 * `shape.connect` glues the end to a shape's connection site near there, or leaves it free there.
 */
@Composable
private fun BoxScope.ConnectorEnds(page: Int, ends: List<Pair<Float, Float>>, pxPerPt: Float, onCommit: (String, JsonObject) -> Unit) {
    val density = LocalDensity.current
    val target = with(density) { END_TOUCH.roundToPx() }
    val knob = with(density) { KNOB.toPx() }
    val latestScale by rememberUpdatedState(pxPerPt)
    val latestEnds by rememberUpdatedState(ends)
    // The end being dragged (0 start, 1 end) and where it is now, in page points.
    var dragging by remember(ends) { mutableStateOf<Pair<Int, Offset>?>(null) }
    fun place(i: Int): Offset = dragging?.takeIf { it.first == i }?.second ?: ends[i].let { Offset(it.first, it.second) }
    Canvas(Modifier.matchParentSize()) {
        drawLine(FRAME_COLOR, place(0) * pxPerPt, place(1) * pxPerPt, strokeWidth = 2f)
        for (i in 0..1) drawCircle(FRAME_COLOR, knob * 1.3f, place(i) * pxPerPt)
    }
    for (i in 0..1) {
        val at = Offset(ends[i].first, ends[i].second) * pxPerPt
        Box(
            Modifier
                .offset { IntOffset(at.x.roundToInt() - target / 2, at.y.roundToInt() - target / 2) }
                .size(END_TOUCH)
                .pointerInput(i) {
                    var finger = Offset.Zero
                    detectDragGestures(
                        onDragStart = { finger = latestEnds[i].let { Offset(it.first, it.second) }; dragging = i to finger },
                        onDrag = { change, amount -> change.consume(); finger += amount / latestScale; dragging = i to finger },
                        onDragEnd = {
                            val to = dragging?.second
                            dragging = null
                            if (to != null) onCommit("shape.connect", buildJsonObject {
                                put(if (i == 0) "start" else "end", buildJsonObject { put("x", to.x); put("y", to.y); put("page", page) })
                            })
                        },
                        onDragCancel = { dragging = null },
                    )
                },
        )
    }
}

/**
 * A picture in Crop: its frame with heavy crop handles at the corners and the middle of each side. Dragging one trims
 * the picture from that side (or gives back what was cropped); the outline follows, and on release it is one
 * `picture.crop`. Turned pictures are trimmed along their own sides.
 */
@Composable
private fun BoxScope.CropHandles(frame: ObjectFrame, crop: List<Float>, pxPerPt: Float, onCommit: (String, JsonObject) -> Unit) {
    val density = LocalDensity.current
    val target = with(density) { TOUCH_TARGET.roundToPx() }
    val latestFrame by rememberUpdatedState(frame)
    val latestCrop by rememberUpdatedState(crop)
    val latestScale by rememberUpdatedState(pxPerPt)
    var preview by remember(frame) { mutableStateOf<FrameRect?>(null) }
    val real = frame.rect()
    val shown = preview ?: real
    Canvas(Modifier.matchParentSize()) {
        val k = pxPerPt
        val arm = 10.dp.toPx()
        val w = 4.dp.toPx()
        rotate(shown.rotation, pivot = Offset(shown.cx * k, shown.cy * k)) {
            // What a release keeps, outlined; the rest of the picture is still drawn under it until then.
            drawRect(CROP_COLOR, Offset(shown.x * k, shown.y * k), Size(shown.w * k, shown.h * k), style = Stroke(1.5f))
            val l = shown.x * k; val t = shown.y * k; val r = (shown.x + shown.w) * k; val b = (shown.y + shown.h) * k
            val cx = shown.cx * k; val cy = shown.cy * k
            for ((x, y, dx) in listOf(Triple(l, t, 1f), Triple(r, t, -1f), Triple(l, b, 1f), Triple(r, b, -1f))) {
                val dy = if (y == t) 1f else -1f
                drawLine(CROP_COLOR, Offset(x, y), Offset(x + dx * arm, y), w)
                drawLine(CROP_COLOR, Offset(x, y), Offset(x, y + dy * arm), w)
            }
            drawLine(CROP_COLOR, Offset(cx - arm / 2, t), Offset(cx + arm / 2, t), w)
            drawLine(CROP_COLOR, Offset(cx - arm / 2, b), Offset(cx + arm / 2, b), w)
            drawLine(CROP_COLOR, Offset(l, cy - arm / 2), Offset(l, cy + arm / 2), w)
            drawLine(CROP_COLOR, Offset(r, cy - arm / 2), Offset(r, cy + arm / 2), w)
        }
    }
    for (h in FrameHandle.values()) {
        val at = handlePoint(real, h) * pxPerPt
        Box(
            Modifier
                .offset { IntOffset(at.x.roundToInt() - target / 2, at.y.roundToInt() - target / 2) }
                .size(TOUCH_TARGET)
                .semantics { contentDescription = "Crop from the ${h.name.lowercase()}" }
                .handleDrag(
                    h,
                    onMove = { total -> preview = resizeFrame(latestFrame.rect(), h, total.x / latestScale, total.y / latestScale, keepAspect = false) },
                    onEnd = {
                        preview?.let { p -> cropDragParams(latestFrame.rect(), p, h, latestCrop)?.let { onCommit("picture.crop", it) } }
                        preview = null
                    },
                    onCancel = { preview = null },
                ),
        )
    }
}
