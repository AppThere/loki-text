package app.loki.text

import app.loki.kit.*

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavPaneTest {
    private val headings = parseNavHeadings(Json.parseToJsonElement("""{"headings":[
        {"pos":{"story":"body","path":[0],"off":0},"level":1,"text":"1 Intro"},
        {"pos":{"story":"body","path":[2],"off":0},"level":2,"text":"1.1 Scope"},
        {"pos":{"story":"body","path":[4],"off":0},"level":3,"text":"Detail"},
        {"pos":{"story":"body","path":[6],"off":0},"level":1,"text":"2 Method"},
        {"text":"no pos"},[1]],"current":3}"""))

    @Test
    fun parsesHeadingsAndCurrent() {
        assertEquals(listOf("1 Intro", "1.1 Scope", "Detail", "2 Method"), headings.items.map { it.text })
        assertEquals(3, headings.current)
        assertEquals(listOf(1, 2, 3, 1), headings.items.map { it.level })
    }

    @Test
    fun hostileInput() {
        assertEquals(0, parseNavHeadings(null).items.size)
        assertEquals(0, parseNavHeadings(Json.parseToJsonElement("[1]")).items.size)
        assertNull(parseNavHeadings(Json.parseToJsonElement("""{"headings":[],"current":4}""")).current)
        assertNull(parseNavHeadings(Json.parseToJsonElement("""{"headings":[{"pos":{},"level":1,"text":"a"}],"current":7}""")).current)
        assertEquals(9, parseNavHeadings(Json.parseToJsonElement("""{"headings":[{"pos":{},"level":99,"text":"a"}]}""")).items[0].level)
    }

    @Test
    fun childrenAndCollapsing() {
        assertEquals(listOf(true, true, false, false), hasChildren(headings.items))
        val levels = headings.items.map { it.level }
        assertEquals(listOf(true, true, true, true), visibleHeadings(levels, listOf(false, false, false, false)))
        // Collapsing "1 Intro" hides everything down to the next level-1 heading.
        assertEquals(listOf(true, false, false, true), visibleHeadings(levels, listOf(true, false, false, false)))
        // Collapsing "1.1 Scope" hides only its own children.
        assertEquals(listOf(true, true, false, true), visibleHeadings(levels, listOf(false, true, false, false)))
        // A collapsed heading inside a collapsed one changes nothing more.
        assertEquals(listOf(true, false, false, true), visibleHeadings(levels, listOf(true, true, false, false)))
    }

    @Test
    fun parsesResults() {
        val r = parseNavResults(Json.parseToJsonElement("""{"count":3,"results":[
            {"start":{"off":1},"end":{"off":4},"before":"The ","hit":"fox","after":" ran"},{"hit":"bad"}]}"""))
        assertEquals(3, r.count)
        assertEquals(1, r.items.size)
        assertEquals("fox", r.items[0].hit)
        assertEquals(0, parseNavResults(null).count)
    }
}
