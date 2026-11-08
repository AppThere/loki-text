package app.loki.text

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    /** Called with true while the pointer is over a control, so clicks go to it and not to the window drag. */
    val onControl: (Boolean) -> Unit,
    /** Minimize, maximize and close drawn by the app, where the system does not draw them (Linux); null otherwise. */
    val windowButtons: (@Composable () -> Unit)?,
)

/** Marks an interactive part of the title bar: while the pointer is on it, the bar does not start a window drag. */
fun Modifier.titleBarControl(config: TitleBarConfig): Modifier = pointerInput(config) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press -> config.onControl(true)
                PointerEventType.Exit -> config.onControl(false)
            }
        }
    }
}

/** The text in the title bar: the document's file name (or Untitled), a mark when it has unsaved changes, and the app. */
internal fun windowTitle(path: String?, unsaved: Boolean): String {
    val name = path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "Untitled"
    return (if (unsaved) "● " else "") + "$name — $APP_NAME"
}
