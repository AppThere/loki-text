package com.appthere.loki.kit

import android.view.MotionEvent
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A pen's tilt and side button from the MotionEvent behind [event] (an S Pen, a USI pen on a
 * Chromebook). Android gives the tilt as an angle from the perpendicular (AXIS_TILT) and the
 * direction the pen leans (AXIS_ORIENTATION, 0 pointing up the screen, clockwise); Pointer Events'
 * tiltX/tiltY are the angles in the x and y planes, so this converts. No Android pen reports barrel
 * rotation.
 */
actual fun penPose(event: PointerEvent, change: PointerInputChange): PenPose? {
    val m: MotionEvent = event.motionEvent ?: return null
    val i = (0 until m.pointerCount).firstOrNull {
        val t = m.getToolType(it)
        t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    } ?: return null
    val tilt = m.getAxisValue(MotionEvent.AXIS_TILT, i).toDouble()
    val orientation = m.getAxisValue(MotionEvent.AXIS_ORIENTATION, i).toDouble()
    val barrel = (m.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
    if (!tilt.isFinite() || !orientation.isFinite()) return PenPose(null, null, null, barrel)
    val t = tan(tilt.coerceIn(0.0, 1.5))
    val deg = 180.0 / Math.PI
    val tiltX = (atan(t * sin(orientation)) * deg).toFloat().coerceIn(-90f, 90f)
    val tiltY = (atan(-t * cos(orientation)) * deg).toFloat().coerceIn(-90f, 90f)
    return PenPose(tiltX, tiltY, null, barrel)
}
