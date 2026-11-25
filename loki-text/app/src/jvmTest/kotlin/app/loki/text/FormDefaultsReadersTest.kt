package app.loki.text

import app.loki.text.engine.Engine
import app.loki.kit.ChartTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Against a real engine: a form's starting values are read without changing anything (some commands the engine
 * calls pure still flip a setting when run bare, as Proofing does), and the table's paths find what the readers
 * really answer.
 */
class FormDefaultsReadersTest {
    private fun Engine.snapshot() = listOf(call("document.inspect"), execute("view.state"), call("loki.commandState")).map { it.toString() }

    @Test
    fun readingStartingValuesChangesNothing() {
        Engine().use { engine ->
            engine.insertText("Some text to read the formatting of.")
            engine.execute("select.all")
            // Read each once first: whatever a first read sets up (lazily filled settings) is not a change of the person's.
            val readers = FORM_DEFAULTS.values.map { it.reader }.toSet()
            // A reader with nothing to read (Chart Data with no chart selected) is an error, as the app takes it.
            for (r in readers) runCatching { engine.execute(r) }
            val before = engine.snapshot()
            for (r in readers) {
                runCatching { engine.execute(r) }
                assertEquals(before, engine.snapshot(), "reading with $r changed the document or the view")
            }
        }
    }

    @Test
    fun startingValuesFollowTheDocument() {
        Engine().use { engine ->
            engine.insertText("Numbered lines.")
            engine.execute("view.zoom", buildJsonObject { put("value", 150) })
            engine.execute("layout.lineNumbers", buildJsonObject { put("value", "restartSection"); put("countBy", 5) })
            engine.execute("para.align", buildJsonObject { put("value", "center") })
            fun start(id: String): Map<String, String> {
                val source = FORM_DEFAULTS.getValue(id)
                return formDefaults(source, engine.execute(source.reader))
            }
            assertEquals("150", start("view.zoom")["value"])
            assertEquals("restartSection", start("layout.lineNumbers")["value"])
            assertEquals("5", start("layout.lineNumbers")["countBy"])
            assertEquals("center", start("para.align")["value"])
            // A new chart is selected: Edit Data starts with its categories.
            engine.execute("insert.chart", Json.parseToJsonElement("""{"type":"column","data":{"categories":["North","South"],"series":[{"name":"Sales","values":[1,2]}]}}""").jsonObject)
            val data = Json.parseToJsonElement(start("chart.editData").getValue("data")).jsonObject
            assertEquals(listOf("North", "South"), ChartTable.from(data).categories)
        }
    }
}
