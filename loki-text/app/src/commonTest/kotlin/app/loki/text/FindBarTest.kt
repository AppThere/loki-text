package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindBarTest {
    @Test
    fun paramsCarryOnlyTheOptionsThatAreOn() {
        val p = findParams(FindState(query = "cat", matchCase = true))
        assertEquals("cat", p["text"]!!.jsonPrimitive.content)
        assertTrue(p["matchCase"]!!.jsonPrimitive.boolean)
        assertFalse("wholeWord" in p || "regex" in p || "with" in p)
        assertEquals("dog", findParams(FindState(query = "cat", with = "dog"), true)["with"]!!.jsonPrimitive.content)
    }

    @Test
    fun goToParams() {
        assertEquals(3, goParams(FindState(goTarget = "page", goValue = " 3 "))!!["page"]!!.jsonPrimitive.int)
        assertEquals(7, goParams(FindState(goTarget = "paragraph", goValue = "7"))!!["paragraph"]!!.jsonPrimitive.int)
        assertEquals("Intro", goParams(FindState(goTarget = "bookmark", goValue = "Intro"))!!["bookmark"]!!.jsonPrimitive.content)
        for (bad in listOf("", "abc", "0", "-2", "99999999999", "NaN")) assertNull(goParams(FindState(goTarget = "page", goValue = bad)), bad)
    }

    @Test
    fun matchText() {
        fun t(json: String) = matchText(Json.parseToJsonElement(json))
        assertEquals("3 of 12", t("""{"index":2,"count":12}"""))
        assertEquals("No matches", t("""{"count":0}"""))
        assertEquals("1 match", t("""{"count":1}"""))
        assertEquals("5 matches", t("""{"count":5,"matches":[]}"""))
        assertEquals("", matchText(null))
        assertEquals("", t("[1]"))
    }
}
