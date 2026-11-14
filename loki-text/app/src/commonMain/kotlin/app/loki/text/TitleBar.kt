package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp

/**
 * A window whose title bar the app draws (desktop with a JetBrains Runtime). The app puts its own
 * controls in the bar; the operating system keeps dragging, resizing and snapping.
 */
class TitleBarConfig(
    val height: Dp,
    /** Room the system keeps at the left (macOS traffic lights). */
    val leftInset: Dp,
    /** Room the system keeps at the right (Windows caption buttons). */
    val rightInset: Dp,
    /** Called for each pointer event over the bar: true on a control (clicks go to it), false elsewhere (the system drags the window). */
    val onControl: (Boolean) -> Unit,
    /** Minimize, maximize and close drawn by the app, where the system does not draw them (Linux); null otherwise. */
    val windowButtons: (@Composable () -> Unit)?,
)

/** Whether the pointer is on one of the title bar's controls, kept by [titleBarControl] for [titleBarArea]. */
class TitleBarHits {
    internal var overControl = false
}

/** Marks an interactive part of the title bar: while the pointer is on it, the bar does not start a window drag. */
fun Modifier.titleBarControl(hits: TitleBarHits): Modifier = pointerInput(hits) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press -> hits.overControl = true
                PointerEventType.Exit -> hits.overControl = false
            }
        }
    }
}

/**
 * The whole title bar. The JetBrains Runtime treats a window with mouse listeners (every Compose
 * window) as client area unless told otherwise for each mouse event, so every event over the bar is
 * reported: a control, or the bar itself, which the system drags. The Main pass reaches the
 * controls first, so [hits] is current when this runs.
 */
fun Modifier.titleBarArea(config: TitleBarConfig, hits: TitleBarHits): Modifier = pointerInput(config, hits) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Main)
            config.onControl(hits.overControl)
        }
    }
}

/** The text in the title bar: the document's file name (or Untitled), a mark when it has unsaved changes, and the app. */
internal fun windowTitle(path: String?, unsaved: Boolean): String {
    val name = path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "Untitled"
    return (if (unsaved) "● " else "") + "$name — $APP_NAME"
}
