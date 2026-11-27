package com.appthere.loki.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import kotlin.math.max

/*
 * A window frame the app draws, for a desktop whose JVM cannot give the app a custom title bar through the JetBrains
 * Runtime (on Linux the runtime reports window decorations as unsupported): the window is undecorated, and the app
 * supplies what the system would have: a title bar to drag, caption buttons, resize edges and an outline.
 */

/** The JetBrains Runtime's custom title bar, when this JVM has it (looked up by name, so the kit needs no JBR library). */
fun jbrWindowDecorationsSupported(): Boolean = runCatching {
    Class.forName("com.jetbrains.JBR").getMethod("isWindowDecorationsSupported").invoke(null) as Boolean
}.getOrDefault(false)

/**
 * Whether the app should draw its own frame: on Linux when the JVM has no JBR custom title bar. Windows and macOS keep
 * the native bar. `LOKI_FRAME=own` or `native` overrides, to look at either one anywhere.
 */
fun useOwnFrame(): Boolean {
    when (System.getenv("LOKI_FRAME")?.lowercase()) {
        "own" -> return true
        "native" -> return false
    }
    return System.getProperty("os.name").orEmpty().startsWith("Linux") && !jbrWindowDecorationsSupported()
}

enum class FrameEdge(val cursor: Int) {
    N(Cursor.N_RESIZE_CURSOR), S(Cursor.S_RESIZE_CURSOR), E(Cursor.E_RESIZE_CURSOR), W(Cursor.W_RESIZE_CURSOR),
    NE(Cursor.NE_RESIZE_CURSOR), NW(Cursor.NW_RESIZE_CURSOR), SE(Cursor.SE_RESIZE_CURSOR), SW(Cursor.SW_RESIZE_CURSOR),
}

/** [start] after the pointer moved [dx], [dy] from where the drag began, holding the opposite edge still and never below the minimum size. */
fun resizedBounds(start: Rectangle, edge: FrameEdge, dx: Int, dy: Int, minWidth: Int = 480, minHeight: Int = 320): Rectangle {
    val west = edge == FrameEdge.W || edge == FrameEdge.NW || edge == FrameEdge.SW
    val east = edge == FrameEdge.E || edge == FrameEdge.NE || edge == FrameEdge.SE
    val north = edge == FrameEdge.N || edge == FrameEdge.NE || edge == FrameEdge.NW
    val south = edge == FrameEdge.S || edge == FrameEdge.SE || edge == FrameEdge.SW
    var x = start.x; var y = start.y; var w = start.width; var h = start.height
    if (east) w = max(minWidth, start.width + dx)
    if (west) { w = max(minWidth, start.width - dx); x = start.x + start.width - w }
    if (south) h = max(minHeight, start.height + dy)
    if (north) { h = max(minHeight, start.height - dy); y = start.y + start.height - h }
    return Rectangle(x, y, w, h)
}

/** Where the pointer is on the screen. A window being dragged moves under the pointer, so the window's own coordinates cannot measure the drag. Replaced by tests. */
internal var screenPointer: () -> Point? = { runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() }

private fun pointer(): Point? = screenPointer()

/** Dragging this moves [window] (by the pointer's place on the screen, so the window does not chase its own motion); a double click calls [onDoubleClick]. */
fun Modifier.windowDrag(window: Window, onDoubleClick: () -> Unit = {}): Modifier = this
    .pointerInput(window) { detectTapGestures(onDoubleTap = { onDoubleClick() }) }
    .pointerInput(window) {
        var from = Point(); var origin = Point()
        detectDragGestures(
            onDragStart = { from = pointer() ?: Point(); origin = window.location },
            onDrag = { change, _ ->
                change.consume()
                pointer()?.let { p -> window.setLocation(origin.x + p.x - from.x, origin.y + p.y - from.y) }
            },
        )
    }

internal fun Modifier.edgeDragForTest(window: Window, edge: FrameEdge): Modifier = edgeDrag(window, edge)

private fun Modifier.edgeDrag(window: Window, edge: FrameEdge): Modifier = pointerInput(window, edge) {
    var from = Point(); var start = Rectangle()
    detectDragGestures(
        onDragStart = { from = pointer() ?: Point(); start = window.bounds },
        onDrag = { change, _ ->
            change.consume()
            pointer()?.let { p -> window.bounds = resizedBounds(start, edge, p.x - from.x, p.y - from.y) }
        },
    )
}

/** Thin strips along the window's edges and corners that resize it, with the cursor a system frame shows there. Put it over the whole window. */
@Composable
fun ResizeEdges(window: Window, thickness: Dp = 5.dp, corner: Dp = 12.dp) {
    Box(Modifier.fillMaxSize()) {
        // Edges first, so the corners are on top of them.
        for (edge in FrameEdge.entries) {
            val (align, size) = when (edge) {
                FrameEdge.N -> Alignment.TopCenter to Modifier.fillMaxWidth().height(thickness)
                FrameEdge.S -> Alignment.BottomCenter to Modifier.fillMaxWidth().height(thickness)
                FrameEdge.W -> Alignment.CenterStart to Modifier.fillMaxHeight().width(thickness)
                FrameEdge.E -> Alignment.CenterEnd to Modifier.fillMaxHeight().width(thickness)
                FrameEdge.NW -> Alignment.TopStart to Modifier.size(corner)
                FrameEdge.NE -> Alignment.TopEnd to Modifier.size(corner)
                FrameEdge.SW -> Alignment.BottomStart to Modifier.size(corner)
                FrameEdge.SE -> Alignment.BottomEnd to Modifier.size(corner)
            }
            Box(Modifier.align(align).then(size).pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(edge.cursor))).edgeDrag(window, edge))
        }
    }
}

/** Minimize, maximize/restore and close, for a frame the system does not draw. */
@Composable
fun WindowButtons(maximized: Boolean, onMinimize: () -> Unit, onMaximize: () -> Unit, onClose: () -> Unit) {
    Row {
        IconButton(onClick = onMinimize, modifier = Modifier.size(32.dp)) { Icon(Lucide.Minus, contentDescription = "Minimize", modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onMaximize, modifier = Modifier.size(32.dp)) {
            Icon(if (maximized) Lucide.Copy else Lucide.Square, contentDescription = if (maximized) "Restore" else "Maximize", modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Lucide.X, contentDescription = "Close", modifier = Modifier.size(16.dp)) }
    }
}

fun WindowState.toggleMaximized() { placement = if (placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized }

/**
 * The frame for an app that has no title bar of its own: a bar with the app's name (drag it to move the window, double
 * click to maximize) and the caption buttons, the app under it, resize edges and an outline over everything. Themed by
 * the app: it is composed inside [CraftTheme] (see [LocalWindowFrame]).
 */
@Composable
fun OwnFrame(window: Window, state: WindowState, title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val maximized = state.placement == WindowPlacement.Maximized
    Box(Modifier.fillMaxSize().then(if (maximized) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant))) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(FRAME_BAR_HEIGHT).windowDrag(window) { state.toggleMaximized() }.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    WindowButtons(maximized, onMinimize = { state.isMinimized = true }, onMaximize = { state.toggleMaximized() }, onClose = onClose)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
        if (!maximized) ResizeEdges(window)
    }
}

val FRAME_BAR_HEIGHT: Dp = 34.dp
