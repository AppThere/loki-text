package app.loki.kit

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange

/** The desktop's AWT events carry no pen tilt; pressure comes through Compose's common API. */
actual fun penPose(event: PointerEvent, change: PointerInputChange): PenPose? = null
