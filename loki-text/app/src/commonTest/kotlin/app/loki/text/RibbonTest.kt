package app.loki.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class RibbonTest {
    private fun commands(vararg c: Triple<String, String, String>) = Json.parseToJsonElement(
        c.joinToString(prefix = "[", postfix = "]") { (id, loc, params) ->
            """{"id":"$id","label":"$id","location":"$loc","shortcut":"","params":${Json.encodeToString(kotlinx.serialization.serializer<String>(), params)}}"""
        },
    )

    private fun ids(tabs: List<RibbonTab>) = tabs.flatMap { t -> t.groups.flatMap { g -> g.items.map { it.id } } }

    @Test
    fun plainCommandsBecomeButtons() {
        val tabs = parseRibbon(commands(Triple("format.bold", "Home › Font", "{}"), Triple("view.marks", "View › Show", "{\"on\"?: bool}")))
        assertEquals(listOf("format.bold", "view.marks"), ids(tabs))
    }

    @Test
    fun commandsThatNeedAValueGetNoButton() {
        assertFalse(runsWithoutParams("{\"size\": number}"))
        assertFalse(runsWithoutParams("{\"a\": 1} | {\"b\": 2}"))
        val tabs = parseRibbon(commands(Triple("format.size", "Home › Font", "{\"size\": number}")))
        assertTrue(ids(tabs).isEmpty())
    }

    /** These have only optional keys but still fail without input, so they must stay off the ribbon. */
    @Test
    fun needsInputCommandsAreExcludedEvenWithOptionalParams() {
        val tabs = parseRibbon(commands(
            Triple("format.font", "Home › Font", "{\"name\"?: string, \"eastAsia\"?: string}"),
            Triple("references.citation", "References › Citations", "{\"tag\"?: string, \"source\"?: string}"),
            Triple("format.bold", "Home › Font", "{}"),
        ))
        assertEquals(listOf("format.bold"), ids(tabs))
    }

    @Test
    fun uiCommandsAndContextualTabsAreLeftOut() {
        val tabs = parseRibbon(commands(Triple("ui.find", "Home › Editing", "{}"), Triple("table.merge", "Table Layout › Merge", "{}")))
        assertTrue(ids(tabs).isEmpty())
    }

    @Test
    fun commandStateReadsDisabledReasonsAndToggles() {
        val s = parseCommandState(Json.parseToJsonElement("""{"disabled":{"edit.cut":"nothing is selected"},"on":["format.bold","view.ruler"]}"""))
        assertEquals("nothing is selected", s.disabled["edit.cut"])
        assertTrue("format.bold" in s.on && "view.ruler" in s.on)
        // A malformed reply leaves every command enabled and every toggle off.
        val bad = parseCommandState(Json.parseToJsonElement("[1]"))
        assertTrue(bad.disabled.isEmpty() && bad.on.isEmpty())
    }

    @Test
    fun characterCountIncludesSpaces() {
        val c = parseCounts(Json.parseToJsonElement("""{"words":2,"pages":1,"characters":9,"charactersWithSpaces":10}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(2, c.words)
        assertEquals(10, c.characters)
    }
}
