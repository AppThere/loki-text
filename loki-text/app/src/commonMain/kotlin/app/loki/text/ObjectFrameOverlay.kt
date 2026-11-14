package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.loki.text.engine.ObjectFrame
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

private val FRAME_COLOR = Color(0xFF1E6FE0)
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
 * [pxPerPt] turns page points into the page image's pixels.
 */
@Composable
fun BoxScope.ObjectFrameOverlay(frame: ObjectFrame, pxPerPt: Float, onCommit: (String, JsonObject) -> Unit) {
    val density = LocalDensity.current
    val target = with(density) { TOUCH_TARGET.roundToPx() }
    val knob = with(density) { KNOB.toPx() }
    val latestFrame by rememberUpdatedState(frame)
    val latestScale by rememberUpdatedState(pxPerPt)
    // What the outline shows while a drag is in progress; it starts again from the real frame when that changes.
    var preview by remember(frame) { mutableStateOf<FrameRect?>(null) }
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
    }

    // Dragging the body moves a floating object. The area turns with the frame, so its drags are in the frame's axes.
    if (frame.floating) {
        Box(
            Modifier
                .offset { IntOffset((real.x * pxPerPt).roundToInt(), (real.y * pxPerPt).roundToInt()) }
                .size(with(density) { (real.w * pxPerPt).toDp() }, with(density) { (real.h * pxPerPt).toDp() })
                .graphicsLayer { rotationZ = real.rotation }
                .pointerInput(Unit) {
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { total = Offset.Zero },
                        onDrag = { change, amount ->
                            change.consume()
                            total += rotateVec(amount, latestFrame.rotation)
                            preview = moveFrame(latestFrame.rect(), total.x / latestScale, total.y / latestScale)
                        },
                        onDragEnd = { preview?.let { onCommit("arrange.bounds", boundsParams(latestFrame, it)) }; preview = null },
                        onDragCancel = { preview = null },
                    )
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
                .pointerInput(h) {
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = { total = Offset.Zero },
                        onDrag = { change, amount ->
                            change.consume()
                            total += amount
                            preview = resizeFrame(latestFrame.rect(), h, total.x / latestScale, total.y / latestScale, keepAspect = latestFrame.kind == "image" && h.corner)
                        },
                        onDragEnd = { preview?.let { onCommit("arrange.bounds", boundsParams(latestFrame, it)) }; preview = null },
                        onDragCancel = { preview = null },
                    )
                },
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
