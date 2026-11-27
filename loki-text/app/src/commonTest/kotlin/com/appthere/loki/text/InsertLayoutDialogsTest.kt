package com.appthere.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsertLayoutDialogsTest {
    private val twoColumns = Json.parseToJsonElement(
        """{"pageW":612,"marginLeft":72,"marginRight":72,"columns":{"count":2,"space":36,"separator":true,"widths":[]}}""",
    ).jsonObject

    private fun total(f: ColumnsForm) = f.laidOut.sumOf { (it.first + it.second).toDouble() }

    @Test fun columnsOpenAsTheSectionIsAndGoBackAsEqualColumns() {
        val f = columnsFormFrom(twoColumns, selected = false)
        assertEquals("2", presetOf(f))
        val p = columnsParams(f)
        assertEquals(2, p["count"]?.jsonPrimitive?.content?.toInt())
        assertEquals("true", p["separator"]?.jsonPrimitive?.content)
        assertEquals("section", p["apply"]?.jsonPrimitive?.content)
        assertNull(p["widths"])
        // With text selected, the columns go to that text.
        assertEquals("selection", columnsParams(columnsFormFrom(twoColumns, selected = true))["apply"]?.jsonPrimitive?.content)
    }

    @Test fun editingAWidthKeepsTheColumnsInsideTheText() {
        val f = withEqual(withCount(columnsFormFrom(twoColumns, false), 3), false)
        val before = total(f)
        val wider = withWidth(f, 0, f.laidOut[0].first + 40f)
        assertTrue(wider.laidOut[0].first > f.laidOut[0].first)
        assertTrue(kotlin.math.abs(total(wider) - before) < 0.01)
        val widerGap = withSpace(wider, 1, wider.laidOut[1].second + 10f)
        assertTrue(kotlin.math.abs(total(widerGap) - before) < 0.01)
        assertNull(columnsProblem(widerGap))
        // Unequal columns go to the engine one by one.
        val widths = columnsParams(widerGap)["widths"] as? JsonArray
        assertEquals(3, widths?.size)
        assertNull(columnsParams(widerGap)["count"])
    }

    @Test fun columnsThatDoNotFitAreRefused() {
        val f = withEqual(columnsFormFrom(twoColumns, false), false)
        assertNotNull(columnsProblem(withWidth(f, 0, f.textWidth)))
        assertNotNull(columnsProblem(f.copy(equal = true, count = 12, space = 100f)))
    }

    @Test fun leftAndRightPresetsAreTwoUnequalColumns() {
        val f = columnsFormFrom(twoColumns, false)
        for (side in listOf("left", "right")) {
            val p = withPreset(f, side)
            assertEquals(side, presetOf(p))
            val (a, b) = p.laidOut
            assertTrue(if (side == "left") a.first < b.first else a.first > b.first)
            assertTrue(total(p) <= p.textWidth + 0.5)
        }
    }

    @Test fun dropCapLeavesOutTheTextsOwnFont() {
        val start = dropCapFormFrom(Json.parseToJsonElement("""{"position":"none","lines":3,"distance":0,"font":null,"textFont":"Liberation Serif"}""").jsonObject)
        val on = start.copy(position = "dropped")
        assertNull(dropCapParams(on)?.get("font"))
        assertEquals("Georgia", dropCapParams(on.copy(font = "Georgia"))?.get("font")?.jsonPrimitive?.content)
        assertNull(dropCapParams(on.copy(lines = "11")))
        // Turning it off sends only that, whatever else the fields hold.
        assertEquals(setOf("position"), dropCapParams(on.copy(position = "none", lines = "x"))?.keys)
    }

    @Test fun symbolCodesBeyondTheBasicPlaneRoundTrip() {
        val cp = parseCharCode("U+1F600")
        assertEquals(0x1F600, cp)
        val text = codePointText(0x1F600)
        assertEquals(2, text?.length)
        assertEquals(0x1F600, text?.let(::firstCodePoint))
        assertNull(parseCharCode("D800"))
        assertNull(parseCharCode("zz"))
        assertEquals(null, symbolParams(0x1F600, "  ")?.get("font"))
    }

    @Test fun recentSymbolsKeepTheLatestFirstWithoutRepeats() {
        val r = withRecentSymbol(withRecentSymbol(withRecentSymbol(emptyList(), "©"), "€"), "©")
        assertEquals(listOf("©", "€"), r)
    }

    @Test fun dateFormatsSkipEntriesWithoutASample() {
        val r = Json.parseToJsonElement("""{"formats":[{"format":"M/d/yyyy","sample":"1/2/2026"},{"format":"x"},7]}""")
        assertEquals(listOf("M/d/yyyy"), parseDateFormats(r).map { it.format })
        assertEquals(emptyList(), parseDateFormats(JsonObject(emptyMap())))
    }
}
