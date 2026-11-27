package com.appthere.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParagraphViewDialogsTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private val lines = json(
        """{"tabs":[{"pos":72,"align":"left","leader":"none"},{"pos":216,"align":"decimal","leader":"dot"}],"defaultTab":36,
        "borders":{"top":null,"left":null,"right":null,"between":null,"bottom":{"style":"double","width":1.5,"color":"FF0000"}},"shading":null}""",
    )

    @Test fun tabsSendOnlyWhatChanged() {
        val start = tabsFormFrom(lines)
        assertNull(tabsParams(start, start))
        val now = withStop(withoutStop(start, 72f), TabMark(216f, "right", "dot"))
        assertEquals(json("""{"clear":[72.0],"set":[{"pos":216.0,"align":"right","leader":"dot"}]}"""), tabsParams(start, now))
        assertEquals(json("""{"default":18.0}"""), tabsParams(start, start.copy(defaultTab = 18f)))
    }

    @Test fun removingEveryStopClearsTheInheritedOnesToo() {
        val start = tabsFormFrom(lines)
        assertEquals(json("""{"clearAll":true}"""), tabsParams(start, start.copy(stops = emptyList())))
    }

    @Test fun addedStopsStayInOrderAndReplaceTheOneAtTheirPosition() {
        val f = withStop(withStop(tabsFormFrom(lines), TabMark(36f)), TabMark(72.01f, "center"))
        assertEquals(listOf(36f, 72.01f, 216f), f.stops.map { it.pos })
        assertEquals("center", f.stops[1].align)
    }

    @Test fun bordersAndShadingEachRunOnlyWhenChanged() {
        val start = bordersFormFrom(lines)
        assertEquals(setOf("bottom"), start.sides)
        assertTrue(bordersSteps(start, start).isEmpty())
        val shaded = bordersSteps(start, start.copy(shading = "D4E4FA"))
        assertEquals(listOf("para.shading"), shaded.map { it.command })
        assertEquals("true", shaded.single().params["paragraph"]?.jsonPrimitive?.content)
        val both = bordersSteps(start, start.copy(shading = "D4E4FA", sides = emptySet()))
        assertEquals(listOf("para.borders", "para.shading"), both.map { it.command })
        assertEquals(json("""{"sides":{}}"""), both.first().params)
        assertEquals(JsonNull, bordersSteps(start.copy(shading = "D4E4FA"), start).single().params["color"])
    }

    @Test fun aSideAddedKeepsTheOthersAsTheyWereUntilTheLineIsChanged() {
        // The two sides differ; the dialog shows one line, and must not make them alike by adding a third.
        val start = bordersFormFrom(
            json("""{"borders":{"top":{"style":"single","width":3,"color":null},"bottom":{"style":"double","width":1.5,"color":"FF0000"}},"shading":null}"""),
        )
        fun side(f: BordersForm, name: String) = bordersSteps(start, f).single().params["sides"]!!.jsonObject[name]?.jsonObject
        val added = start.copy(sides = start.sides + "left")
        assertEquals("double", side(added, "bottom")?.get("style")?.jsonPrimitive?.content)
        assertEquals("single", side(added, "top")?.get("style")?.jsonPrimitive?.content)
        assertNotNull(side(added, "left"))
        // Choosing a line is choosing it for every side drawn.
        val restyled = added.copy(style = "dotted")
        assertEquals(setOf("dotted"), listOf("top", "bottom", "left").map { side(restyled, it)?.get("style")?.jsonPrimitive?.content }.toSet())
    }

    @Test fun aBorderColourThatIsNotOneHoldsOk() {
        val start = bordersFormFrom(lines)
        assertNotNull(bordersProblem(start.copy(color = "red")))
        assertNotNull(bordersProblem(start.copy(shading = "12345")))
        assertNull(bordersProblem(start.copy(color = "")))
    }

    @Test fun zoomOpensAsTheViewIsAndTakesOnlyWhatTheAppCanDraw() {
        val fitted = zoomFormFrom(json("""{"zoom":0.8,"fit":"pageWidth"}"""))
        assertEquals("pageWidth", zoomParams(fitted, 25..200)?.get("value")?.jsonPrimitive?.content)
        val plain = zoomFormFrom(json("""{"zoom":1.25,"fit":""}"""))
        assertEquals("125", zoomParams(plain, 25..200)?.get("value")?.jsonPrimitive?.content)
        assertNull(zoomParams(plain.copy(percent = "500"), 25..200))
        assertNull(zoomParams(plain.copy(percent = "big"), 25..200))
    }
}
