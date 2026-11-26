package app.loki.text

import app.loki.kit.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Object and list parameters of the engine's real commands are drawn as fields, not JSON boxes. */
class StructuredFieldsTest {
    private val commands = parseCommands(Json.parseToJsonElement(File("src/jvmTest/resources/engine-commands.json").readText())).associateBy { it.id }

    private fun shape(id: String, field: String): ParamShape? {
        val form = parseParamForm(commands.getValue(id).params)
        return shapeOf(form.fields.first { it.name == field }, form.defs)
    }

    @Test fun theListsPeopleEditAreFields() {
        for ((id, field) in listOf(
            "para.tabs" to "tabs", "para.tabs" to "clear", "mailings.filterRecipients" to "rules", "mailings.filterRecipients" to "sort",
            "chart.editData" to "data", "insert.chart" to "data", "smartArt.items" to "items", "tools.customizeKeyboard" to "assign",
            "tools.customizeKeyboard" to "remove", "design.pageBorders" to "sides", "file.options" to "marks", "references.toc" to "styleLevels",
            "insert.spreadsheet" to "values", "table.newStyle" to "parts", "table.modifyStyle" to "parts",
        )) assertTrue(shape(id, field) != null, "$id $field is still a JSON box")
    }

    /**
     * Object and list types written out in a doc are drawn as fields. Left as JSON, each for its reason: positions
     * (the engine's own, set by pressing on the page), types a doc names without spelling out, and these.
     */
    private val leftAsJson = mapOf(
        "mailings.recipients rows" to "the recipient list has its own table editor",
        "mailings.editRecipients rows" to "the recipient list has its own table editor",
        "draw.lasso points" to "drawn on the page",
        "draw.stroke points" to "drawn on the page",
        "select.addObject objects" to "positions",
    )

    @Test fun everyWrittenOutObjectOrListIsFields() {
        val missing = ArrayList<String>()
        for (c in commands.values) {
            val form = parseParamForm(c.params)
            for (f in form.fields) {
                val t = f.type.trim()
                if (f.kind != FieldKind.Json || !(t.startsWith("{") || t.startsWith("["))) continue
                if (shapeOf(f, form.defs) == null && "${c.id} ${f.name}" !in leftAsJson) missing += "${c.id} ${f.name}: ${t.take(80)}"
            }
        }
        assertTrue(missing.isEmpty(), "still JSON boxes:\n" + missing.joinToString("\n"))
    }
}
