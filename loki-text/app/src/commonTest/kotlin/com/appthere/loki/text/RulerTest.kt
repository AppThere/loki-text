package com.appthere.loki.text

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RulerTest {
    // Letter paper, 1-inch margins, a paragraph indented half an inch with a quarter-inch hanging first line.
    private val info = RulerInfo(612f, 72f, 72f)
    private val lay = LayoutState(36f, 18f, null, null, null, null, null, null, null, firstLine = -18f)
    private val g = rulerGeometry(info, lay)

    @Test fun markersSitWhereTheIndentsAre() {
        assertEquals(RulerGeometry(612f, 72f, 540f, firstLine = 90f, left = 108f, right = 522f), g)
    }

    @Test fun noIndentsPutTheMarkersOnTheMargins() =
        assertEquals(RulerGeometry(612f, 72f, 540f, firstLine = 72f, left = 72f, right = 540f), rulerGeometry(info, LayoutState.EMPTY))

    @Test fun pressesPickTheNearestMarkerInTheirHalf() {
        assertEquals(IndentMarker.FirstLine, markerAt(g, 92f, upper = true, reach = 6f))
        assertEquals(IndentMarker.Left, markerAt(g, 106f, upper = false, reach = 6f))
        assertEquals(IndentMarker.Right, markerAt(g, 520f, upper = true, reach = 6f))
        assertNull(markerAt(g, 300f, upper = false, reach = 6f))
        // The left marker hangs from the bottom: a press in the top half at its place misses it.
        assertNull(markerAt(g, 108f, upper = true, reach = 6f))
    }

    @Test fun droppedMarkersSendIndents() {
        assertEquals(buildJsonObject { put("firstLine", 0f) }, indentParams(g, IndentMarker.FirstLine, 108.4f))
        // The left marker moves; the first line stays at 90 pt.
        assertEquals(buildJsonObject { put("left", 72f); put("firstLine", -54f) }, indentParams(g, IndentMarker.Left, 144f))
        assertEquals(buildJsonObject { put("right", 36f) }, indentParams(g, IndentMarker.Right, 504f))
    }

    @Test fun dropsSnapAndStayOnThePage() {
        assertEquals(103.5f, snap(102f))
        assertEquals(buildJsonObject { put("right", -72f) }, indentParams(g, IndentMarker.Right, 900f))
    }

    // No indents: the left and right markers sit on the margins' edges.
    private val plain = rulerGeometry(info, LayoutState.EMPTY)
    private val tabs = listOf(TabMark(144f, "center"), TabMark(36f))

    @Test fun theMarkersTheMarginsAndTheTabsEachHaveTheirBand() {
        // With no indent the markers sit on the margin's edge: the bottom band takes the left marker, the top the
        // first-line one, and the middle the margin itself.
        assertEquals(RulerTarget.Marker(IndentMarker.Left), rulerTargetAt(plain, tabs, 72f, RulerBand.Bottom, reach = 4f))
        assertEquals(RulerTarget.Marker(IndentMarker.FirstLine), rulerTargetAt(plain, tabs, 72f, RulerBand.Top, reach = 4f))
        assertEquals(RulerTarget.Margin(left = true), rulerTargetAt(plain, tabs, 72f, RulerBand.Middle, reach = 4f))
        assertEquals(RulerTarget.Margin(left = false), rulerTargetAt(plain, tabs, 540f, RulerBand.Middle, reach = 4f))
        // A tab stop, in the bottom band, by its place after the left margin.
        assertEquals(RulerTarget.Tab(0), rulerTargetAt(plain, tabs, 72f + 144f + 2f, RulerBand.Bottom, reach = 4f))
        // Near nothing in its band, a press falls back to the rest, then to nothing.
        assertEquals(RulerTarget.Margin(left = true), rulerTargetAt(g, emptyList(), 72f, RulerBand.Bottom, reach = 4f))
        assertEquals(RulerTarget.Empty, rulerTargetAt(g, emptyList(), 300f, RulerBand.Middle, reach = 4f))
    }

    @Test fun bandsSplitTheRulerInThree() {
        assertEquals(listOf(RulerBand.Top, RulerBand.Middle, RulerBand.Bottom), listOf(2f, 10f, 18f).map { rulerBand(it, 20f) })
    }

    @Test fun aClickInTheTextSetsALeftTabAndOutsideItNothing() {
        assertEquals(buildJsonObject { put("set", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("pos", 108f); put("align", "left") }) }) }, tabAddParams(plain, 180.4f))
        assertNull(tabAddParams(plain, 30f))
        assertNull(tabAddParams(plain, 600f))
    }

    @Test fun aDraggedTabMovesInOneStepKeepingItsKindOrComesOff() {
        val moved = tabAddParams(plain, 0f) // nothing at the page edge
        assertNull(moved)
        val drop = tabDropParams(plain, TabMark(144f, "center", "dot"), 72f + 216f, off = false)
        assertEquals("""{"clear":[144.0],"set":[{"pos":216.0,"align":"center","leader":"dot"}]}""", drop.toString())
        assertEquals("""{"clear":[144.0]}""", tabDropParams(plain, TabMark(144f), 300f, off = true).toString())
    }

    @Test fun aMarginsEdgeKeepsSomeTextBetweenTheMargins() {
        assertEquals(buildJsonObject { put("left", 90f) }, marginParams(plain, left = true, 90.2f))
        val tooFar = marginParams(plain, left = true, 600f)
        assertEquals(612f - 72f - 36f, tooFar["left"].toString().toFloat())
        assertEquals(buildJsonObject { put("right", 108f) }, marginParams(plain, left = false, 504f))
    }

    @Test fun tabStopsAreReadInOrder() {
        val read = parseTabMarks(kotlinx.serialization.json.Json.parseToJsonElement("""[{"pos":144,"align":"Right","leader":"Dot"},{"pos":36},{"align":"left"},5]"""))
        assertEquals(listOf(TabMark(36f), TabMark(144f, "right", "dot")), read)
    }
}
