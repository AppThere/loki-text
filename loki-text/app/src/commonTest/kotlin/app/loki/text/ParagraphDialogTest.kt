package app.loki.text

import app.loki.kit.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParagraphDialogTest {
    private fun state(json: String) = paraFormFrom(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun readsStateAndTreatsNullAsMixed() {
        val f = state("""{"align":"center","left":36,"right":0,"indentFirst":-18,"spaceBefore":null,"spaceAfter":6,"lineSpacing":{"rule":"multiple","value":1.5},"keepNext":true,"keepLines":null}""")
        assertEquals("center", f.align)
        assertEquals("36", f.left)
        assertEquals("-18", f.first)
        assertEquals("", f.before)
        assertEquals("1.5", f.line)
        assertEquals(true, f.keepNext)
        assertNull(f.keepLines)
    }

    @Test
    fun noChangeSendsNothing() {
        val f = state("""{"align":"left","left":0,"spaceBefore":null,"keepLines":null}""")
        assertNull(paraChanges(f, f))
    }

    @Test
    fun splitsPageFieldsFromProperties() {
        val start = state("""{"align":"left","left":0,"right":0,"indentFirst":0,"spaceBefore":0,"spaceAfter":0,"lineSpacing":{"rule":"multiple","value":1}}""")
        val c = paraChanges(start, start.copy(align = "justify", left = "36", after = "12", line = "2", keepNext = true))!!
        assertEquals("justify", c["onPage"]!!.jsonObject["align"]!!.jsonPrimitive.content)
        assertEquals(36f, c["onPage"]!!.jsonObject["left"]!!.jsonPrimitive.float)
        val props = c["props"]!!.jsonObject
        assertEquals(12f, props["spaceAfter"]!!.jsonPrimitive.float)
        assertEquals(2f, props["lineSpacing"]!!.jsonObject["value"]!!.jsonPrimitive.float)
        assertTrue(props["keepNext"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun mixedFieldIsOnlySentOnceTouched() {
        val start = state("""{"align":"left","spaceBefore":null,"keepLines":null}""")
        assertNull(paraChanges(start, start.copy()))
        assertEquals(6f, paraChanges(start, start.copy(before = "6"))!!["props"]!!.jsonObject["spaceBefore"]!!.jsonPrimitive.float)
        assertTrue(paraChanges(start, start.copy(keepLines = true))!!["props"]!!.jsonObject["keepLines"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun hostileNumbersAreIgnoredOrClamped() {
        val start = state("""{"align":"left","left":0,"spaceBefore":0,"lineSpacing":{"rule":"multiple","value":1}}""")
        assertNull(paraChanges(start, start.copy(left = "NaN", before = "inf", line = "abc")))
        assertEquals(5f, paraChanges(start, start.copy(line = "99"))!!["props"]!!.jsonObject["lineSpacing"]!!.jsonObject["value"]!!.jsonPrimitive.float)
    }
}
