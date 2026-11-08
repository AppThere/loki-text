package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrangeRibbonTest {
    @Test fun readsTheSelectedObjectsWrapping() {
        val a = parseCommandState(Json.parseToJsonElement("""{"disabled":{},"on":[],"arrange":{"wrap":"square","moveWithText":true}}""")).arrange
        assertEquals("square", a?.wrap)
        assertTrue(a?.moveWithText == true)
        assertNull(parseCommandState(Json.parseToJsonElement("""{"disabled":{},"on":[],"arrange":null}""")).arrange)
    }

    @Test fun wrapMenuMarksTheCurrentWrap() {
        val (labels, current) = wrapMenu(ArrangeState("behindText", false))
        assertEquals("Behind Text", current)
        assertEquals(listOf("Move with Text", "Fix Position on Page"), labels.takeLast(2))
        assertNull(wrapMenu(null).second)
    }

    @Test fun wrapRowsRunTheirCommands() {
        assertEquals("arrange.wrap" to buildJsonObject { put("wrap", "tight") }, wrapChoice(2))
        assertEquals("arrange.moveWithText" to buildJsonObject { put("value", true) }, wrapChoice(WRAPS.size))
        assertEquals("arrange.moveWithText" to buildJsonObject { put("value", false) }, wrapChoice(WRAPS.size + 1))
        assertNull(wrapChoice(WRAPS.size + 2))
    }

    @Test fun positionInLineIsAWrap() {
        assertEquals("arrange.wrap" to buildJsonObject { put("wrap", "inline") }, positionChoice(0))
        assertEquals("arrange.position" to buildJsonObject { put("preset", "bottomRight") }, positionChoice(9))
        assertNull(positionChoice(10))
    }

    @Test fun pictureFormatGetsArrangeBeforeSize() {
        val groups = withAppGroups("Picture Format", listOf("Adjust", "Accessibility", "Size").map { RibbonGroup(it, emptyList()) })
        assertEquals(listOf("Adjust", "Accessibility", "Arrange", "Size"), groups.map { it.name })
        // Other tabs, and a tab that already has it, are left alone.
        assertEquals(listOf("Arrange"), withAppGroups("Picture Format", listOf(RibbonGroup("Arrange", emptyList()))).map { it.name })
        assertEquals(listOf("Size"), withAppGroups("Layout", listOf(RibbonGroup("Size", emptyList()))).map { it.name })
        assertTrue(hasCustomGroup("Layout", "Arrange"))
        assertTrue(hasCustomGroup("Picture Format", "Arrange"))
    }
}
