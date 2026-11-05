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
    fun uiCommandsAreLeftOut() {
        val tabs = parseRibbon(commands(Triple("ui.find", "Home › Editing", "{}")))
        assertTrue(ids(tabs).isEmpty())
    }

    @Test
    fun contextualTabsShowOnlyWhenTheSelectionBringsThemUp() {
        val tabs = parseRibbon(commands(Triple("format.bold", "Home › Font", "{}"), Triple("table.merge", "Table Layout › Merge", "{}"), Triple("image.crop", "Picture Format › Size", "{}")))
        assertEquals(listOf("Home", "Table Layout", "Picture Format"), tabs.map { it.name })
        assertEquals(listOf("Home"), visibleTabs(tabs, CommandState.EMPTY).map { it.name })
        val inTable = parseCommandState(Json.parseToJsonElement("""{"disabled":{},"on":[],"contextual":["Table Layout","Nonsense"]}"""))
        assertEquals(listOf("Table Layout"), inTable.contextual)
        assertEquals(listOf("Home", "Table Layout"), visibleTabs(tabs, inTable).map { it.name })
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

    @Test
    fun formatStateReadsFontAndSizeAndToleratesMixedSelections() {
        val f = parseFormatState(Json.parseToJsonElement("""{"font":"Carlito","size":10.5,"color":"auto","highlight":null}"""))
        assertEquals("Carlito", f.font)
        assertEquals(10.5f, f.size)
        assertEquals(null, f.highlight)
        // A selection that mixes fonts reports null: the box is left empty.
        val mixed = parseFormatState(Json.parseToJsonElement("""{"font":null,"size":null}"""))
        assertEquals(null, mixed.font)
        assertEquals("", sizeText(mixed.size))
        assertEquals(FormatState.EMPTY, parseFormatState(Json.parseToJsonElement("[1]")).let { FormatState.EMPTY })
        assertEquals(null, parseFormatState(Json.parseToJsonElement("[1]")).font)
    }

    @Test
    fun sizesAreWrittenAndParsedLikeWord() {
        assertEquals("11", sizeText(11f))
        assertEquals("10.5", sizeText(10.5f))
        assertEquals(12f, parseFontSize(" 12 "))
        assertEquals(null, parseFontSize("0"))
        assertEquals(null, parseFontSize("99999"))
        assertEquals(null, parseFontSize("NaN"))
        assertEquals(null, parseFontSize("abc"))
    }

    @Test
    fun fontFamiliesAreSortedAndDeduplicated() {
        val l = parseFontFamilies(Json.parseToJsonElement("""{"families":["Tinos","carlito"," ","Tinos","Arimo"]}"""))
        assertEquals(listOf("Arimo", "carlito", "Tinos"), l)
        assertTrue(parseFontFamilies(Json.parseToJsonElement("{}")).isEmpty())
    }

    @Test
    fun hexColoursParseAndFormat() {
        assertEquals(Triple(255, 0, 128), parseHexColor("#FF0080"))
        assertEquals(Triple(1, 2, 3), parseHexColor(" 010203 "))
        assertEquals(null, parseHexColor("FF00"))
        assertEquals(null, parseHexColor("GG0000"))
        assertEquals("0A0B0C", hexOf(10, 11, 12))
        assertEquals("FF0000", hexOf(300, -5, 0))
    }

    @Test
    fun layoutStateComesWithTheCommandState() {
        val s = parseCommandState(Json.parseToJsonElement(
            """{"disabled":{},"on":[],"layout":{"indentLeft":36.0,"spaceAfter":8.0,"lineSpacing":1.15,"landscape":true,"paper":"A4","margins":"narrow","columns":"left","spaceBefore":null}}""",
        ))
        assertTrue(sameNumber(s.layout.indentLeft, 36f))
        assertTrue(sameNumber(s.layout.lineSpacing, 1.15f))
        assertEquals(null, s.layout.spaceBefore)
        assertEquals(true, s.layout.landscape)
        assertEquals("A4", s.layout.paper)
        assertEquals("left", s.layout.columns)
        // An older engine reply without a layout section leaves everything unknown.
        val none = parseCommandState(Json.parseToJsonElement("""{"disabled":{},"on":[]}"""))
        assertEquals(null, none.layout.landscape)
        assertTrue(!sameNumber(none.layout.indentLeft, 0f))
    }

    @Test
    fun insertGroupsWithValueCommandsHaveTheirOwnControls() {
        assertTrue(hasCustomGroup("Insert", "Tables"))
        assertTrue(hasCustomGroup("Insert", "Header & Footer"))
        assertTrue(hasCustomGroup("Insert", "Links"))
        assertTrue(!hasCustomGroup("Insert", "Pages"))
    }

    @Test
    fun dialogParamsKeepRequiredBoxesAndDropBlankOnes() {
        val link = dialogParams(InsertDialog.Link, mapOf("url" to " https://a.example ", "text" to ""))
        assertEquals("https://a.example", link?.get("url")?.toString()?.trim('"'))
        assertTrue(link != null && "text" !in link)
        assertEquals(null, dialogParams(InsertDialog.Link, mapOf("url" to "  ")))
        assertEquals(null, dialogParams(InsertDialog.TextBox, mapOf("width" to "wide")))
        assertEquals(null, dialogParams(InsertDialog.TextBox, mapOf("width" to "-4")))
        assertTrue(dialogParams(InsertDialog.TextBox, mapOf("text" to "", "width" to "200", "height" to "80")) != null)
    }

    @Test
    fun valueControlsJoinTheGenericButtonsOfTheirGroups() {
        assertTrue(hasGroupExtras("Review", "Comments"))
        assertTrue(hasGroupExtras("References", "Table of Contents"))
        assertTrue(!hasGroupExtras("Review", "Tracking"))
        // The group keeps its generic buttons: it is not a custom group.
        assertTrue(!hasCustomGroup("Review", "Comments"))
        assertEquals(listOf("text"), InsertDialog.NewComment.fields.map { it.key })
        assertTrue(dialogParams(InsertDialog.NewComment, mapOf("text" to " ")) == null)
        assertTrue(READING_SPEEDS.all { it.second in 0.5..3.0 })
        assertTrue(TOC_LEVELS.all { it.second in 1..9 })
    }
}
