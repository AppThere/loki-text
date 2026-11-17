package app.loki.text

import app.loki.kit.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageSetupDialogTest {
    private val letter = pageFormFrom(Json.parseToJsonElement("""{"pageW":612,"pageH":792,"landscape":false,"marginTop":72,"marginBottom":72,"marginLeft":72,"marginRight":72,"gutter":0,"valign":"top"}""") as JsonObject)

    @Test
    fun readsTheSection() {
        assertEquals(612f, letter.width)
        assertEquals("top", letter.valign)
        assertEquals("Letter", paperName(letter.width, letter.height))
        assertEquals("A4", paperName(841.89f, 595.28f))
        assertNull(paperName(500f, 500f))
    }

    @Test
    fun unitsRoundTrip() {
        assertEquals("1", lengthText(72f, LengthUnit.Inches))
        assertEquals("2.54", lengthText(72f, LengthUnit.Centimetres))
        assertEquals(72f, parseLength("1", LengthUnit.Inches))
        assertEquals(36f, parseLength(" 0.5 ", LengthUnit.Inches))
        for (bad in listOf("", "abc", "-1", "NaN", "inf", "1e99")) assertNull(parseLength(bad, LengthUnit.Inches), bad)
    }

    @Test
    fun nothingChangedSendsNothing() = assertNull(pageChanges(letter, letter.copy()))

    @Test
    fun onlyChangedFieldsAreSent() {
        val c = pageChanges(letter, letter.copy(left = 54f, valign = "center", landscape = true, width = 792f, height = 612f))!!
        assertEquals(setOf("width", "height", "landscape", "left", "valign"), c.keys)
        assertEquals(792f, c["width"]!!.jsonPrimitive.float)
        assertTrue(c["landscape"]!!.jsonPrimitive.boolean)
        assertEquals("center", c["valign"]!!.jsonPrimitive.content)
    }
}
