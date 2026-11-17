package app.loki.text

import app.loki.kit.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Draw Table and Eraser are page tools (Insert › Tables, Table Layout › Draw): while one is on, a
 * press on the page draws or erases instead of moving the caret. The engine decides what a stroke
 * means (`table.draw`: a rectangle makes a table, a line splits cells; `table.eraser`: the border
 * nearest the click goes).
 */
internal val TABLE_TOOLS = setOf("table.draw", "table.eraser")

/** What the status bar says while [tool] is on. */
internal fun tableToolHint(tool: String): String = when (tool) {
    "table.draw" -> "Drag a rectangle to draw a table, or a line across cells to split them. Esc stops."
    else -> "Click a border between two cells to merge them. Esc stops."
}

/** A stroke on one page, from where the press started to where the pointer is, in page points. */
data class PenStroke(val page: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    /** Too short to be a stroke: a click (within 2 pt both ways). */
    val isClick: Boolean get() = abs(x1 - x0) < 2f && abs(y1 - y0) < 2f
}

/**
 * The command a finished [stroke] runs with [tool]: Draw Table takes the stroke (a click draws
 * nothing), Eraser the point where it ended. Null when nothing should run.
 */
internal fun penCommand(tool: String, stroke: PenStroke): Pair<String, JsonObject>? = when (tool) {
    "table.draw" -> if (stroke.isClick) null else tool to buildJsonObject {
        put("page", stroke.page); put("x0", stroke.x0); put("y0", stroke.y0); put("x1", stroke.x1); put("y1", stroke.y1)
    }
    "table.eraser" -> tool to buildJsonObject { put("page", stroke.page); put("x", stroke.x1); put("y", stroke.y1) }
    else -> null
}

/**
 * How the preview draws [stroke]: a line when it runs nearly along one axis (under 6 pt across),
 * which the engine reads as a cell border, else a rectangle.
 */
internal fun penPreviewIsLine(stroke: PenStroke): Boolean = abs(stroke.x1 - stroke.x0) < 6f || abs(stroke.y1 - stroke.y0) < 6f
