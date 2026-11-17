package app.loki.text

import app.loki.kit.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StylesDialogTest {
    private val list = parseStyleEntries(Json.parseToJsonElement("""[
        {"id":"Normal","name":"Normal","type":"paragraph","builtIn":true,"inGallery":true,"hidden":false,"basedOn":null,"format":{"font":"Carlito","size":11,"spaceAfter":8}},
        {"id":"Mine","name":"Mine","type":"linked","builtIn":false,"inGallery":false,"hidden":true,"basedOn":"Normal","format":{"bold":true,"color":"c00000"}},
        {"name":"no id"},[1]]"""))

    @Test
    fun parsesEveryStyleAndSkipsBadRows() {
        assertEquals(listOf("Normal", "Mine"), list.map { it.id })
        assertTrue(list[1].hidden && !list[1].builtIn)
    }

    @Test
    fun describes() {
        assertEquals("Paragraph style, Built-in style, Font: Carlito, 11 pt, Space after: 8 pt", describeStyle(list[0]))
        assertEquals("Linked style (paragraph and character), Bold, Color: #c00000, Based on: Normal", describeStyle(list[1]))
    }

    @Test
    fun modifySendsOnlyChanges() {
        val start = styleFormFrom(list[0])
        assertNull(styleChanges("Normal", start, start.copy()))
        val c = styleChanges("Normal", start, start.copy(size = "12", bold = true, after = "10", color = "ff0000"))!!
        assertEquals("Normal", c["style"]!!.jsonPrimitive.content)
        assertEquals(12f, c["chr"]!!.jsonObject["size"]!!.jsonPrimitive.float)
        assertTrue(c["chr"]!!.jsonObject["bold"]!!.jsonPrimitive.boolean)
        assertEquals(10f, c["para"]!!.jsonObject["spaceAfter"]!!.jsonPrimitive.float)
        assertTrue("name" !in c)
        assertEquals("Renamed", styleChanges("Mine", styleFormFrom(list[1]), styleFormFrom(list[1]).copy(name = " Renamed "))!!["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun clearedAndHostileFieldsAreIgnored() {
        val start = styleFormFrom(list[0])
        assertNull(styleChanges("Normal", start, start.copy(font = "", size = "NaN", color = "zzz", before = "inf", name = "  ")))
    }

    @Test
    fun createParams() {
        assertNull(createStyleParams("  ", "Normal", true))
        val p = createStyleParams(" Callout ", "Normal", false)!!
        assertEquals("Callout", p["name"]!!.jsonPrimitive.content)
        assertEquals(false, p["fromSelection"]!!.jsonPrimitive.boolean)
    }
}
