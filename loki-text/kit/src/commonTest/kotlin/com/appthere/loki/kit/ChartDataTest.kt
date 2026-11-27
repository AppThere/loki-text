package com.appthere.loki.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartDataTest {
    @Test fun aTableIsTheChartsData() {
        val t = ChartTable(listOf("Q1", "Q2"), listOf("North", "South"), listOf(listOf("1", ""), listOf("2.5", "3")))
        assertEquals(
            Json.parseToJsonElement("""{"categories":["Q1","Q2"],"series":[{"name":"North","values":[1,2.5]},{"name":"South","values":[null,3]}]}"""),
            t.toData().getOrThrow(),
        )
        assertEquals(t, ChartTable.from(t.toData().getOrThrow()))
    }

    @Test fun aCellThatIsNotANumberIsNamed() {
        val e = ChartTable(listOf("Q1"), listOf("North"), listOf(listOf("lots"))).toData().exceptionOrNull()
        assertTrue(e?.message.orEmpty().startsWith("North, Q1"), e?.message)
    }

    @Test fun rowsAndColumnsStayRectangular() {
        val t = ChartTable.from(null).withColumn().withRow().withoutColumn(0).withoutRow(0)
        assertEquals(t.categories.size, t.cells.size)
        assertTrue(t.cells.all { it.size == t.series.size })
    }

    @Test fun theChartFormIsRecognised() {
        val form = parseParamForm("""{"data": {"categories"?: [string], "series"?: [{"name": string, "values": [number|null]}]}}""")
        val data = shapeOf(form.fields.single(), form.defs) as ParamShape.Obj
        assertTrue(isChartData(data.form))
        assertTrue(ChartTable.from(Json.parseToJsonElement("{}") as JsonObject).categories.isNotEmpty(), "a fresh grid has rows to type into")
    }

    @Test fun aPressedChordIsWrittenAsTheEngineReadsIt() {
        for (text in listOf("Mod+Shift+K", "Alt+F7", "Mod+=", "F12")) {
            val c = parseChord(text)!!
            assertEquals(c, parseChord(chordText(c)!!))
        }
    }
}
