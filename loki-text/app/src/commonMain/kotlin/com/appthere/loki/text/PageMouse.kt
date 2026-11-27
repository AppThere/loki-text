package com.appthere.loki.text

import com.appthere.loki.text.engine.PointerHit
import com.appthere.loki.text.engine.TableSpot
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * The mouse and pen gestures of the desktop front end that the page's taps and touch drags do not cover: a press on a
 * table's border, move handle, insert button or selection area; dragging by words, sentences, lines or paragraphs;
 * Alt+drag for a column of text. Each is one or two engine commands taking page points, issued here as the gesture
 * goes (`table.*`, `select.dragUnit`, `select.column`). What is under the pointer is the engine's to say
 * (`loki.tableSpot`, `loki.hit`); this file only turns a press, its moves and its release into commands.
 */

/** A press on a page: where (pixels of the page image), how many clicks in a row, and the keys held. */
internal class MousePress(val page: Int, val pxPerPt: Float, val down: Offset, val clicks: Int, val shift: Boolean, val ctrl: Boolean, val alt: Boolean, val reachPt: Float) {
    val x get() = down.x / pxPerPt
    val y get() = down.y / pxPerPt
}

/** What a gesture needs of the app. Everything that reads or changes the engine suspends: the app serialises those calls. */
internal interface MouseHost {
    /**
     * What the press is on, asked in one engine call. The host also records whether the press is a gesture of its own
     * ([ownsPress]) while it still holds the engine, so the page's tap, which waits for the engine too, sees it.
     */
    suspend fun probe(p: MousePress): Probe
    suspend fun hit(page: Int, x: Float, y: Float): PointerHit
    /** Runs a command and returns its result; an engine refusal is shown in the status line and gives null. */
    suspend fun exec(id: String, params: JsonObject = JsonObject(emptyMap())): JsonElement?
    suspend fun setCaret(page: Int, at: Offset, pxPerPt: Float, extend: Boolean)
    /** A line from [from] to [to] (page pixels) while a border or the handle is dragged; null clears it. */
    fun guide(page: Int, from: Offset?, to: Offset?)
    /** A drag is selecting (the floating toolbar waits). */
    fun selecting(on: Boolean)
    /**
     * Shows what a drag would do as it goes: runs [id] in the document from where the drag began (taking back the last
     * preview first) and redraws. [endPreview] takes the last one back, before the drag's own command runs.
     */
    suspend fun preview(id: String, params: JsonObject)
    suspend fun endPreview()
}

/** What is under a press: the table's spot and the text's positions. */
internal class Probe(val spot: TableSpot, val hit: PointerHit)

/**
 * Whether a press is a gesture of its own (a table's border, handle, button or selection area; the selection bar), so the
 * tap that follows it must not move the caret. A press that only selects by dragging is not: a plain click is the tap's.
 */
internal fun ownsPress(probe: Probe, p: MousePress): Boolean =
    probe.spot.kind != null || (probe.hit.bar != null && !p.alt && !p.shift)

/** Moves smaller than this many page points are a click, not a drag. */
internal const val DRAG_MIN_PT = 0.5f

private fun pagePoint(page: Int, x: Float, y: Float) = buildJsonObject { put("page", page); put("x", x); put("y", y) }

/** `table.resizeDrag`: a drag from a border or the resize handle to ([toX], [toY]); Shift and Ctrl choose who gives way. */
internal fun resizeDragParams(p: MousePress, toX: Float, toY: Float) = buildJsonObject {
    put("page", p.page); put("x", p.x); put("y", p.y); put("toX", toX); put("toY", toY)
    put("shift", p.shift); put("ctrl", p.ctrl); put("reach", p.reachPt)
}

/** `table.moveDrag`: a drag from the move handle to ([toX], [toY]). */
internal fun moveDragParams(p: MousePress, toX: Float, toY: Float) = buildJsonObject {
    put("page", p.page); put("x", p.x); put("y", p.y); put("toX", toX); put("toY", toY); put("reach", p.reachPt)
}

/** `table.selectAt`: a click on a selection area, or a drag from it to ([toX], [toY]) when given. */
internal fun selectAtParams(p: MousePress, toX: Float? = null, toY: Float? = null) = buildJsonObject {
    put("page", p.page); put("x", p.x); put("y", p.y); put("reach", p.reachPt); put("extend", p.shift)
    if (toX != null && toY != null) { put("toX", toX); put("toY", toY) }
}

/** `table.insertAt` and `table.fitColumn`: a click or double-click on a button or border. */
internal fun clickAtParams(p: MousePress) = buildJsonObject { put("page", p.page); put("x", p.x); put("y", p.y); put("reach", p.reachPt) }

/** `select.dragUnit`: grow the selection made on [start]..[end] by whole [unit]s to [pos]. */
internal fun dragUnitParams(unit: String, start: JsonElement, end: JsonElement, pos: JsonElement) = buildJsonObject {
    put("unit", unit); put("start", start); put("end", end); put("pos", pos)
}

/** `select.column`: the block of text between two page points. */
internal fun columnParams(page: Int, from: Offset, to: Offset, pxPerPt: Float) = buildJsonObject {
    put("from", pagePoint(page, from.x / pxPerPt, from.y / pxPerPt))
    put("to", pagePoint(page, to.x / pxPerPt, to.y / pxPerPt))
}

private fun JsonElement?.field(k: String): JsonElement? = (this as? JsonObject)?.get(k)

/** The unit a press in the selection bar (the margin left of the text) selects, by clicks: a line, a paragraph, the document. */
internal fun barUnit(clicks: Int, ctrl: Boolean): String = when {
    ctrl || clicks >= 3 -> "document"
    clicks == 2 -> "paragraph"
    else -> "line"
}

/** The unit a press in the text selects, by clicks (a double-click a word, a triple-click a paragraph, Ctrl+click a sentence); null for a plain click. */
internal fun textUnit(clicks: Int, ctrl: Boolean, shift: Boolean): String? = when {
    shift -> null
    clicks >= 3 -> "paragraph"
    clicks == 2 -> "word"
    ctrl -> "sentence"
    else -> null
}

/**
 * One press of the mouse or a pen, and what follows: [moves] delivers the pointer's latest place until the button comes up
 * (the channel then closes). A press that is nothing special here is left alone for the page's tap, or turns into a
 * selecting drag at its first move.
 */
internal suspend fun MouseHost.mouseGesture(p: MousePress, moves: ReceiveChannel<Offset>) {
    val k = p.pxPerPt
    val probe = probe(p)
    val spot = probe.spot
    when (spot.kind) {
        "resize" -> {
            if (p.clicks >= 2) { exec("table.fitColumn", clickAtParams(p)); for (m in moves) {}; return }
            // The columns take their new widths as the border moves; the drag's own command, on release, is the one undo step.
            val to = dragLive(p, moves) { m -> preview("table.resizeDrag", resizeDragParams(p, m.x / k, m.y / k)) } ?: return
            exec("table.resizeDrag", resizeDragParams(p, to.x / k, to.y / k))
        }
        "handle" -> {
            // A movable table goes where the pointer is as it moves; it is placed for good on release.
            val to = if (spot.movable) dragLive(p, moves) { m -> preview("table.moveDrag", moveDragParams(p, m.x / k, m.y / k)) } else dragLine(p, moves)
            if (to != null && spot.movable) exec("table.moveDrag", moveDragParams(p, to.x / k, to.y / k)) else exec("table.selectAt", selectAtParams(p))
        }
        "insertRow", "insertColumn" -> { exec("table.insertAt", clickAtParams(p)); for (m in moves) {} }
        "column", "row", "cell" -> {
            // Beside a row, a triple-click or Ctrl+click is the selection bar's: the whole document.
            if (spot.kind == "row" && !p.shift && (p.clicks >= 3 || p.ctrl)) { exec("select.all", buildJsonObject { put("whole", true) }); for (m in moves) {}; return }
            exec("table.selectAt", selectAtParams(p))
            selecting(true)
            var sent = p.down
            for (m in moves) {
                if (abs(m.x - sent.x) / k < DRAG_MIN_PT && abs(m.y - sent.y) / k < DRAG_MIN_PT) continue
                sent = m
                exec("table.selectAt", selectAtParams(p, m.x / k, m.y / k))
            }
            selecting(false)
            // A double-click along a cell's left edge selects its row, as in Word.
            if (spot.kind == "cell" && p.clicks == 2) exec("table.selectRow")
        }
        else -> textGesture(p, probe.hit, moves)
    }
}

/** Follows a drag from the press, drawing a line; the place it ended, or null when it never left the press. */
private suspend fun MouseHost.dragLine(p: MousePress, moves: ReceiveChannel<Offset>): Offset? {
    var last: Offset? = null
    for (m in moves) { last = m; guide(p.page, p.down, m) }
    guide(p.page, null, null)
    val to = last ?: return null
    return if (abs(to.x - p.down.x) / p.pxPerPt < DRAG_MIN_PT && abs(to.y - p.down.y) / p.pxPerPt < DRAG_MIN_PT) null else to
}

/**
 * Follows a drag from the press like [dragLine], and shows its result as it goes with [show] (a preview the host takes
 * back); the place it ended, or null when it never left the press. The preview is always taken back here: the caller runs
 * the drag's command for good.
 */
private suspend fun MouseHost.dragLive(p: MousePress, moves: ReceiveChannel<Offset>, show: suspend (Offset) -> Unit): Offset? {
    var last: Offset? = null
    var shown: Offset? = null
    try {
        for (m in moves) {
            last = m
            guide(p.page, p.down, m)
            val far = abs(m.x - p.down.x) / p.pxPerPt >= DRAG_MIN_PT || abs(m.y - p.down.y) / p.pxPerPt >= DRAG_MIN_PT
            // Moves come conflated, so a slow redraw only skips places in between.
            if (far && m != shown) { show(m); shown = m }
        }
    } finally {
        guide(p.page, null, null)
        if (shown != null) endPreview()
    }
    val to = last ?: return null
    return if (abs(to.x - p.down.x) / p.pxPerPt < DRAG_MIN_PT && abs(to.y - p.down.y) / p.pxPerPt < DRAG_MIN_PT) null else to
}

/** A press that is not on a table: the selection bar, Alt+drag, and drags that grow the selection by units. */
private suspend fun MouseHost.textGesture(p: MousePress, at: PointerHit, moves: ReceiveChannel<Offset>) {
    val k = p.pxPerPt
    // The selection bar: a click selects the line, more clicks the paragraph and the document; a drag grows it by lines.
    if (at.bar != null && !p.alt && !p.shift) {
        val unit = barUnit(p.clicks, p.ctrl)
        exec("caret.set", buildJsonObject { put("pos", at.bar) })
        val r = exec(if (unit == "document") "select.all" else "select.$unit", if (unit == "document") buildJsonObject { put("whole", true) } else JsonObject(emptyMap()))
        val (start, end) = r.field("anchor") to r.field("focus")
        selecting(true)
        if (start != null && end != null && unit != "document") for (m in moves) {
            // Lines grow line by line wherever the pointer is across; a paragraph from the text position.
            val h = hit(p.page, m.x / k, m.y / k)
            val pos = (if (unit == "line") h.line else h.pos) ?: continue
            exec("select.dragUnit", dragUnitParams(unit, start, end, pos))
        } else for (m in moves) {}
        selecting(false)
        return
    }
    // Alt+drag: a block of text between the press and the pointer.
    if (p.alt && !p.shift && !p.ctrl) {
        var started = false
        for (m in moves) {
            if (!started) { started = true; selecting(true); setCaret(p.page, p.down, k, false) }
            exec("select.column", columnParams(p.page, p.down, m, k))
        }
        selecting(false)
        return
    }
    // A press with clicks selects a unit at once (a drag from it grows by whole units); a plain press selects by its first move.
    val unit = textUnit(p.clicks, p.ctrl, p.shift)
    var start: JsonElement? = null
    var end: JsonElement? = null
    var began = false
    for (m in moves) {
        if (!began) {
            began = true
            selecting(true)
            setCaret(p.page, p.down, k, false)
            if (unit != null) {
                val r = exec("select.$unit")
                start = r.field("anchor"); end = r.field("focus")
            }
        }
        if (unit != null && start != null && end != null) {
            val pos = hit(p.page, m.x / k, m.y / k).pos ?: continue
            exec("select.dragUnit", dragUnitParams(unit, start, end, pos))
        } else {
            setCaret(p.page, m, k, true)
        }
    }
    if (began) selecting(false)
}

/** Counts presses that follow each other closely (within [DOUBLE_TAP_MS] and [slopPx]): 1, 2, 3, then 1 again. */
internal class ClickCounter {
    private var mark: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
    private var at = Offset.Zero
    private var count = 0

    fun press(pos: Offset, slopPx: Float): Int {
        val close = mark?.let { isDoubleTap(it.elapsedNow().inWholeMilliseconds, at, pos, slopPx) } == true
        count = if (close && count < 3) count + 1 else 1
        mark = kotlin.time.TimeSource.Monotonic.markNow()
        at = pos
        return count
    }
}

/** The line drawn from a press to the pointer while a table border or move handle is dragged (page pixels). */
internal class MouseGuide(val page: Int, val from: Offset, val to: Offset)
