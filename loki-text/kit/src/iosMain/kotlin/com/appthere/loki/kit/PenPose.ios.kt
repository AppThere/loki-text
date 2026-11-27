package com.appthere.loki.kit

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange

/**
 * Compose on iOS reports an Apple Pencil as a stylus with its force as pressure, through the common
 * API; its altitude, azimuth and roll (UITouch) are not exposed there yet, so no tilt here.
 */
actual fun penPose(event: PointerEvent, change: PointerInputChange): PenPose? = null
