package app.loki.text

import app.loki.kit.*

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableRibbonTest {
    private fun state(json: String) = parseCommandState(Json.parseToJsonElement(json))

    @Test fun noTableOutsideATable() {
        assertNull(state("""{"disabled":{},"on":[],"table":null}""").table)
        assertNull(state("""{"disabled":{},"on":[]}""").table)
    }

    @Test fun readsTheCaretTable() {
        val t = state(
            """{"disabled":{},"on":["table.repeatHeader"],"table":{"style":"TableGrid","look":{"headerRow":true,"bandedRows":false},
            "rowHeight":30,"exact":true,"columnWidth":144,"cellAlign":"bottomRight",
            "styles":[{"id":"TableGrid","name":"Table Grid"},{"id":"Plain1"}]}}""",
        ).table
        assertEquals("TableGrid", t?.style)
        assertEquals(mapOf("headerRow" to true, "bandedRows" to false), t?.look)
        assertEquals(30f, t?.rowHeight)
        assertTrue(t?.exact == true)
        assertEquals(144f, t?.columnWidth)
        assertEquals("bottomRight", t?.cellAlign)
        // A style without a name is listed by its id.
        assertEquals(listOf("TableGrid" to "Table Grid", "Plain1" to "Plain1"), t?.styles)
    }

    @Test fun unsetSizesAreNull() {
        val t = state("""{"disabled":{},"on":[],"table":{"style":null,"look":{},"rowHeight":null,"exact":false,"columnWidth":null,"cellAlign":null,"styles":[]}}""").table
        assertNull(t?.rowHeight)
        assertNull(t?.columnWidth)
        assertNull(t?.cellAlign)
        assertEquals("", inchText(t?.rowHeight))
    }

    @Test fun inchesShowWithoutTrailingZeros() {
        assertEquals("1\"", inchText(72f))
        assertEquals("1.5\"", inchText(108f))
        assertEquals("0.25\"", inchText(18f))
        assertEquals("0.07\"", inchText(5f))
    }

    @Test fun typedInchesBecomePoints() {
        assertEquals(108f, parseInches("1.5"))
        assertEquals(72f, parseInches("1\""))
        assertEquals(36f, parseInches(" 0.5 in "))
        // Not a size, nothing, or past Word's 22-inch limit.
        assertNull(parseInches("abc"))
        assertNull(parseInches("0"))
        assertNull(parseInches("23"))
        assertNull(parseInches("NaN"))
    }

    @Test fun alignmentTooltipsNameThePosition() {
        assertEquals("Align Top Left", cellAlignLabel("topLeft"))
        assertEquals("Align Center", cellAlignLabel("center"))
        assertEquals("Align Bottom Center", cellAlignLabel("bottomCenter"))
    }

    @Test fun theDrawGroupStaysThoughItsCommandsNeedPagePoints() {
        val tabs = parseRibbon(Json.parseToJsonElement(
            """[{"id":"table.eraser","label":"Eraser","location":"Table Layout › Draw","params":"{\"page\": n, \"x\": pt, \"y\": pt}"},
               {"id":"table.merge","label":"Merge Cells","location":"Table Layout › Merge","params":""},
               {"id":"table.moveDrag","label":"Move","location":"Table Layout › Table","params":"{\"page\": n, \"x\": pt}"}]""",
        ))
        val layout = tabs.single { it.name == "Table Layout" }
        // Draw and Table keep their place though their commands need a mouse; the app draws them.
        assertEquals(listOf("Draw", "Merge", "Table"), layout.groups.map { it.name })
        assertTrue(layout.groups.first { it.name == "Draw" }.items.isEmpty())
    }

    @Test fun tableTabsGetTheirControls() {
        assertTrue(hasCustomGroup("Table Design", "Table Style Options"))
        for (group in TABLE_LAYOUT_GROUPS) assertTrue(hasCustomGroup("Table Layout", group), group)
        for ((tab, group) in TABLE_EXTRA_GROUPS) assertTrue(hasGroupExtras(tab, group), "$tab › $group")
    }
}
