package app.loki.text

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
}
