package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListsDialogTest {
    private val get = Json.parseToJsonElement("""{"levels":[
        {"align":"left","alignedAt":18.0,"bold":true,"color":null,"follow":"tab","font":null,"format":"decimal","indentAt":36.0,"italic":false,"legal":false,"restartAfter":null,"start":1,"style":null,"tabAt":null,"text":"%1."},
        {"align":"left","alignedAt":54.0,"format":"lowerLetter","indentAt":72.0,"restartAfter":0,"start":3,"style":"Heading2","tabAt":72.0,"text":"%1.%2)","legal":true}]}""")

    @Test
    fun readsLevelsAndFillsMissingOnes() {
        val ls = levelsFrom(get)
        assertEquals(LIST_LEVELS, ls.size)
        assertTrue(ls[0].bold && ls[0].restart && ls[0].style.isEmpty() && !ls[0].tabOn)
        assertTrue(!ls[1].restart && ls[1].tabOn && ls[1].legal)
        assertEquals("Heading2", ls[1].style)
        assertEquals(3, ls[1].start)
        // Levels the engine didn't send are still editable.
        assertEquals("%3.", ls[2].text)
    }

    @Test
    fun noListGivesDefaultLevels() {
        val ls = levelsFrom(null)
        assertEquals(listOf("decimal", "lowerLetter", "lowerRoman", "decimal"), ls.take(4).map { it.format })
        assertEquals(LIST_LEVELS, ls.size)
    }

    @Test
    fun paramsRoundTripTheEngineShape() {
        val ps = listParams(levelsFrom(get))["levels"]!!.jsonArray
        assertEquals(LIST_LEVELS, ps.size)
        val l1 = ps[1].jsonObject
        assertEquals(0, l1["restartAfter"]!!.jsonPrimitive.int)
        assertEquals("Heading2", l1["style"]!!.jsonPrimitive.content)
        assertEquals(72f, l1["tabAt"]!!.jsonPrimitive.content.toFloat())
        assertEquals(JsonNull, ps[0].jsonObject["style"])
        assertEquals(JsonNull, ps[0].jsonObject["tabAt"])
        assertEquals(JsonNull, ps[0].jsonObject["restartAfter"])
    }

    @Test
    fun restartAfterOnlyWhenAnEarlierLevel() {
        val l = LevelForm(restart = true, restartAfter = 1)
        assertEquals(1, levelParams(l, 2)["restartAfter"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, levelParams(l, 0)["restartAfter"])
        assertEquals(JsonNull, levelParams(l.copy(restartAfter = 5), 2)["restartAfter"])
    }

    @Test
    fun numberFormats() {
        assertEquals(listOf("a", "z", "aa", "bb", "aaa"), listOf(1, 26, 27, 28, 53).map { formatNumber("lowerLetter", it) })
        assertEquals(listOf("i", "iv", "ix", "xiv", "mcmxcix"), listOf(1, 4, 9, 14, 1999).map { formatNumber("lowerRoman", it) })
        assertEquals("XII", formatNumber("upperRoman", 12))
        assertEquals("07", formatNumber("decimalZero", 7))
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "22nd"), listOf(1, 2, 3, 4, 11, 22).map { formatNumber("ordinal", it) })
        assertEquals("Three", formatNumber("cardinalText", 3))
        assertEquals("5000", formatNumber("lowerRoman", 5000))
    }

    @Test
    fun sampleNumbers() {
        val ls = listOf(LevelForm(text = "%1.", format = "upperRoman"), LevelForm(text = "%1.%2)", format = "lowerLetter", start = 2), LevelForm(text = "%1.%3", format = "decimal", legal = true))
        assertEquals("I.", sampleNumber(ls, 0))
        assertEquals("I.b)", sampleNumber(ls, 1))
        // Legal style shows earlier levels as plain numbers, so "I" becomes "1".
        assertEquals("1.1", sampleNumber(ls, 2))
        assertEquals("•", sampleNumber(listOf(LevelForm(text = "•", format = "bullet")), 0))
        assertEquals("", sampleNumber(listOf(LevelForm(format = "none")), 0))
        assertEquals("", sampleNumber(ls, 8))
    }

    @Test
    fun smallDialogParams() {
        assertEquals("★", bulletParams(" ★x")!!["char"]!!.jsonPrimitive.content)
        assertEquals("😀", bulletParams("😀a")!!["char"]!!.jsonPrimitive.content)
        assertNull(bulletParams("  "))
        assertEquals(4, numberParams("upperRoman", "%1)", "4")!!["start"]!!.jsonPrimitive.int)
        for (bad in listOf("", "x", "-1", "100001", "NaN")) assertNull(numberParams("decimal", "%1.", bad), bad)
        assertNull(numberParams("decimal", " ", "1"))
        assertEquals(7, numberingValueParams(" 7 ")!!["value"]!!.jsonPrimitive.int)
        for (bad in listOf("", "a", "-3", "1e9")) assertNull(numberingValueParams(bad), bad)
    }
}
