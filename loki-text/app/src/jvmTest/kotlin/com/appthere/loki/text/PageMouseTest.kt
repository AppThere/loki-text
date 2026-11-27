package com.appthere.loki.text

import androidx.compose.ui.geometry.Offset
import com.appthere.loki.text.engine.PointerHit
import com.appthere.loki.text.engine.TableSpot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The mouse gestures turn a press, its moves and its release into the engine commands the desktop front end sends. */
class PageMouseTest {
    private val pos = buildJsonObject { put("off", 3) }

    private class Host(val spot: TableSpot, val hit: PointerHit, val results: Map<String, JsonElement> = emptyMap()) : MouseHost {
        val sent = mutableListOf<Pair<String, JsonObject>>()
        val carets = mutableListOf<Pair<Offset, Boolean>>()
        override suspend fun probe(p: MousePress) = Probe(spot, hit)
        override suspend fun hit(page: Int, x: Float, y: Float) = hit
        override suspend fun exec(id: String, params: JsonObject): JsonElement? { sent += id to params; return results[id] }
        override suspend fun setCaret(page: Int, at: Offset, pxPerPt: Float, extend: Boolean) { carets += at to extend }
        override fun guide(page: Int, from: Offset?, to: Offset?) {}
        override fun selecting(on: Boolean) {}
        val previews = mutableListOf<String>()
        var ended = 0
        override suspend fun preview(id: String, params: JsonObject) { previews += id }
        override suspend fun endPreview() { ended++ }
    }

    private fun press(clicks: Int = 1, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false) =
        MousePress(0, 2f, Offset(200f, 400f), clicks, shift, ctrl, alt, 2.5f)

    private fun run(host: Host, p: MousePress, moves: List<Offset> = emptyList()) = runBlocking {
        val ch = Channel<Offset>(Channel.UNLIMITED)
        moves.forEach { ch.trySend(it) }
        ch.close()
        host.mouseGesture(p, ch)
    }

    private val none = PointerHit(null, null, null, null)
    private fun ids(h: Host) = h.sent.map { it.first }

    @Test fun dragFromABorderResizesWithTheKeysHeld() {
        val h = Host(TableSpot("resize", false), none)
        run(h, press(shift = true), listOf(Offset(260f, 400f)))
        assertEquals(listOf("table.resizeDrag"), ids(h))
        val q = h.sent[0].second
        assertEquals(100f, q["x"]!!.jsonPrimitive.content.toFloat())
        assertEquals(130f, q["toX"]!!.jsonPrimitive.content.toFloat())
        assertEquals("true", q["shift"]!!.jsonPrimitive.content)
        assertEquals("false", q["ctrl"]!!.jsonPrimitive.content)
    }

    @Test fun aBorderPressThatNeverMovesChangesNothing() {
        val h = Host(TableSpot("resize", false), none)
        run(h, press())
        assertTrue(h.sent.isEmpty())
    }

    @Test fun doubleClickOnABorderFitsTheColumn() {
        val h = Host(TableSpot("resize", false), none)
        run(h, press(clicks = 2))
        assertEquals(listOf("table.fitColumn"), ids(h))
    }

    @Test fun theMoveHandleMovesOnADragAndSelectsOnAClick() {
        val moved = Host(TableSpot("handle", true), none)
        run(moved, press(), listOf(Offset(300f, 500f)))
        assertEquals(listOf("table.moveDrag"), ids(moved))
        val clicked = Host(TableSpot("handle", true), none)
        run(clicked, press())
        assertEquals(listOf("table.selectAt"), ids(clicked))
        // A table that cannot float is selected, never moved.
        val fixed = Host(TableSpot("handle", false), none)
        run(fixed, press(), listOf(Offset(300f, 500f)))
        assertEquals(listOf("table.selectAt"), ids(fixed))
    }

    @Test fun insertButtonsInsert() {
        for (kind in listOf("insertRow", "insertColumn")) {
            val h = Host(TableSpot(kind, false), none)
            run(h, press())
            assertEquals(listOf("table.insertAt"), ids(h))
        }
    }

    @Test fun selectionAreasSelectAndADragKeepsSelecting() {
        val h = Host(TableSpot("column", false), none)
        run(h, press(), listOf(Offset(300f, 400f), Offset(340f, 400f)))
        assertEquals(listOf("table.selectAt", "table.selectAt", "table.selectAt"), ids(h))
        assertEquals(null, h.sent[0].second["toX"])
        assertEquals(150f, h.sent[1].second["toX"]!!.jsonPrimitive.content.toFloat())
        // Beside a row, a triple-click is the selection bar's: the whole document.
        val row = Host(TableSpot("row", false), none)
        run(row, press(clicks = 3))
        assertEquals(listOf("select.all"), ids(row))
        // A double-click along a cell's edge then selects its row.
        val cell = Host(TableSpot("cell", false), none)
        run(cell, press(clicks = 2))
        assertEquals(listOf("table.selectAt", "table.selectRow"), ids(cell))
    }

    @Test fun theSelectionBarSelectsLinesAndADragGrowsThemByLine() {
        val line = buildJsonObject { put("off", 9) }
        val h = Host(TableSpot(null, false), PointerHit(pos, line, pos, null), mapOf("select.line" to buildJsonObject { put("anchor", pos); put("focus", line) }))
        run(h, press(), listOf(Offset(200f, 500f)))
        assertEquals(listOf("caret.set", "select.line", "select.dragUnit"), ids(h))
        assertEquals("line", h.sent[2].second["unit"]!!.jsonPrimitive.content)
        assertEquals(line, h.sent[2].second["pos"])
    }

    @Test fun doubleClickDragSelectsByWords() {
        val h = Host(TableSpot(null, false), PointerHit(pos, null, null, null), mapOf("select.word" to buildJsonObject { put("anchor", pos); put("focus", pos) }))
        run(h, press(clicks = 2), listOf(Offset(260f, 400f)))
        assertEquals(listOf("select.word", "select.dragUnit"), ids(h))
        assertEquals("word", h.sent[1].second["unit"]!!.jsonPrimitive.content)
        assertEquals(listOf(Offset(200f, 400f) to false), h.carets)
    }

    @Test fun aPlainDragSelectsFromThePressToThePointer() {
        val h = Host(TableSpot(null, false), none)
        run(h, press(), listOf(Offset(260f, 400f)))
        assertTrue(h.sent.isEmpty())
        assertEquals(listOf(Offset(200f, 400f) to false, Offset(260f, 400f) to true), h.carets)
    }

    @Test fun altDragSelectsAColumnOfText() {
        val h = Host(TableSpot(null, false), none)
        run(h, press(alt = true), listOf(Offset(260f, 500f)))
        assertEquals(listOf("select.column"), ids(h))
        val to = h.sent[0].second["to"]!!.jsonObject
        assertEquals(130f, to["x"]!!.jsonPrimitive.content.toFloat())
        assertEquals(250f, to["y"]!!.jsonPrimitive.content.toFloat())
    }

    @Test fun onlyTableAndSelectionBarPressesAreOwned() {
        assertTrue(ownsPress(Probe(TableSpot("cell", false), none), press()))
        assertTrue(ownsPress(Probe(TableSpot(null, false), PointerHit(pos, null, pos, null)), press()))
        // Alt and Shift in the bar are the text's: a block, an extension.
        assertEquals(false, ownsPress(Probe(TableSpot(null, false), PointerHit(pos, null, pos, null)), press(alt = true)))
        assertEquals(false, ownsPress(Probe(TableSpot(null, false), PointerHit(pos, null, null, null)), press()))
    }

    @Test fun clicksCountUpToThree() {
        val c = ClickCounter()
        val at = Offset(10f, 10f)
        assertEquals(listOf(1, 2, 3, 1), List(4) { c.press(at, 20f) })
        assertEquals(1, c.press(Offset(500f, 500f), 20f))
    }

    @Test fun unitsByClicks() {
        assertEquals("word", textUnit(2, false, false))
        assertEquals("paragraph", textUnit(3, false, false))
        assertEquals("sentence", textUnit(1, true, false))
        assertEquals(null, textUnit(1, false, false))
        assertEquals(null, textUnit(2, false, true))
        assertEquals(listOf("line", "paragraph", "document", "document"), listOf(barUnit(1, false), barUnit(2, false), barUnit(3, false), barUnit(1, true)))
    }

    @Test fun aBorderDragShowsItsResultAsItGoesThenCommitsOnce() {
        val h = Host(TableSpot("resize", false), none)
        run(h, press(), listOf(Offset(220f, 400f), Offset(240f, 400f), Offset(260f, 400f)))
        assertTrue(h.previews.size >= 2 && h.previews.all { it == "table.resizeDrag" }, "${h.previews}")
        assertEquals(1, h.ended, "the preview is taken back before the drag's own command")
        assertEquals(listOf("table.resizeDrag"), ids(h))
    }

    @Test fun aPressThatNeverMovesShowsNoPreview() {
        val h = Host(TableSpot("resize", false), none)
        run(h, press(), listOf(Offset(200.2f, 400f)))
        assertTrue(h.previews.isEmpty())
        assertEquals(0, h.ended)
        assertTrue(h.sent.isEmpty())
    }

    @Test fun aMovableTableFollowsThePointer() {
        val h = Host(TableSpot("handle", true), none)
        run(h, press(), listOf(Offset(260f, 460f)))
        assertEquals(listOf("table.moveDrag"), h.previews)
        assertEquals(listOf("table.moveDrag"), ids(h))
    }
}
