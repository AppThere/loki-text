package app.loki.text

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TableDialogsTest {
    @Test fun dialogsOpenForTheirCommands() {
        assertEquals(TableDialog.Split, tableDialogFor("table.split"))
        assertEquals(TableDialog.Properties, tableDialogFor("table.properties"))
        assertNull(tableDialogFor("table.merge"))
    }

    @Test fun splitTakesWholeNumbersUpTo63() {
        assertEquals(buildJsonObject { put("columns", 3); put("rows", 2); put("mergeFirst", false) }, splitParams("3", " 2 ", false))
        assertNull(splitParams("0", "1", true))
        assertNull(splitParams("64", "1", true))
        assertNull(splitParams("2", "", true))
    }

    @Test fun sortColumnsCountFromZero() =
        assertEquals(buildJsonObject { put("column", 0); put("descending", true); put("header", false) }, sortParams(-1, true, false))

    @Test fun formulaGetsItsEqualsSign() {
        assertEquals(buildJsonObject { put("formula", "=SUM(LEFT)") }, formulaParams("SUM(LEFT)"))
        assertEquals(buildJsonObject { put("formula", "=A1*2") }, formulaParams(" =A1*2 "))
        assertNull(formulaParams(" "))
        assertNull(formulaParams("="))
    }

    @Test fun marginsAreInchesAndMayBeZero() {
        assertEquals(
            buildJsonObject { put("top", 0f); put("left", 18f); put("bottom", 0f); put("right", 36f) },
            marginParams(listOf("0", "0.25\"", "0\"", "0.5 in")),
        )
        assertNull(marginParams(listOf("0", "x", "0", "0")))
        assertNull(marginParams(listOf("0", "-1", "0", "0")))
    }

    private val before = TablePropsForm("left", "", "0\"", "", false, true, false, "1.5\"", "", "top")

    @Test fun untouchedPropertiesSendNothing() = assertEquals(buildJsonObject {}, propertiesParams(before, before))

    @Test fun onlyChangedPropertiesAreSent() {
        val p = propertiesParams(before, before.copy(align = "center", rowHeight = "0.5", exact = true, valign = "bottom"))
        assertEquals(
            buildJsonObject { put("align", "center"); put("rowHeight", 36f); put("rowHeightRule", "exact"); put("valign", "bottom") },
            p,
        )
    }

    @Test fun blankSizesGoBackToAutomatic() {
        val p = propertiesParams(before.copy(width = "6\"", cellWidth = "1\""), before)
        assertEquals(JsonNull, p?.get("width"))
        assertEquals(JsonNull, p?.get("cellWidth"))
        // The column always has a width, so a blank one isn't accepted.
        assertNull(propertiesParams(before, before.copy(columnWidth = "")))
        assertNull(propertiesParams(before, before.copy(width = "wide")))
        assertEquals(JsonPrimitive(144f), propertiesParams(before, before.copy(columnWidth = "2"))?.get("columnWidth"))
    }

    @Test fun formStartsFromTheCaretTable() {
        val t = TableState(null, emptyMap(), 36f, true, 108f, null, emptyList(), align = "right", width = 432f, cellWidth = null, valign = "center")
        val f = tablePropsForm(t, headerRow = true)
        assertEquals(TablePropsForm("right", "6\"", "", "0.5\"", true, true, true, "1.5\"", "", "center"), f)
    }
}
