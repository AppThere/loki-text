package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FontDialogTest {
    private fun state(json: String) = fontFormFrom(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun readsTheSelectionsFormatting() {
        val f = state("""{"font":"Carlito","size":10.5,"bold":true,"italic":false,"color":"c0ffee","spacing":1.5,"superscript":true}""")
        assertEquals("Carlito", f.font)
        assertEquals("10.5", f.size)
        assertTrue(f.bold && f.superscript && !f.italic)
        assertEquals("c0ffee", f.color)
        assertEquals("1.5", f.spacing)
    }

    @Test
    fun mixedSelectionShowsBlankAndLeavesItAloneUntilChanged() {
        val start = state("""{"font":null,"size":null,"bold":false,"color":null}""")
        assertEquals("", start.font)
        assertTrue(fontChanges(start, start).isEmpty())
        assertTrue(fontChanges(start, start.copy(bold = true))["bold"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun onlyChangedFieldsAreSent() {
        val start = state("""{"font":"Tinos","size":12,"bold":true}""")
        val c = fontChanges(start, start.copy(size = "14", italic = true))
        assertEquals(setOf("size", "italic"), c.keys)
    }

    @Test
    fun verticalAlignmentAndColour() {
        val start = state("""{"font":"Tinos","size":12}""")
        assertEquals("subscript", fontChanges(start, start.copy(subscript = true))["vertAlign"]!!.jsonPrimitive.content)
        assertEquals("baseline", fontChanges(start.copy(superscript = true), start)["vertAlign"]!!.jsonPrimitive.content)
        assertEquals("Auto", fontChanges(start.copy(color = "ff0000"), start.copy(color = "AUTO"))["color"]!!.jsonPrimitive.content)
        assertTrue(fontChanges(start, start.copy(color = "ff0000")).containsKey("color"))
    }

    @Test
    fun hostileInputIsIgnored() {
        val start = state("""{"font":"Tinos","size":12}""")
        val now = start.copy(size = "NaN", spacing = "inf", color = "zzz", font = "   ")
        assertTrue(fontChanges(start, now).isEmpty())
        assertTrue(fontChanges(start, start.copy(size = "99999")).isEmpty())
    }
}
