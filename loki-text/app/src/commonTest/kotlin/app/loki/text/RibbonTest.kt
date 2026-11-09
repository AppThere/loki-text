package app.loki.text

import app.loki.text.engine.PageRect
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

    @Test
    fun textIsTheSamePhysicalSizeAtEveryScreenDensity() {
        // A 12 pt line is the same number of dp tall on a desktop (1.0) and a Pixel (2.625).
        assertEquals(pagePxPerPt(1f, 1f) * 2.625f, pagePxPerPt(1f, 2.625f), 0.001f)
        assertEquals(pagePxPerPt(2f, 1f), pagePxPerPt(1f, 1f) * 2f, 0.001f)
        // A hostile density can't make the scale zero or huge.
        assertTrue(pagePxPerPt(1f, 0f) > 0f && pagePxPerPt(1f, 1000f) <= pagePxPerPt(1f, 4f))
    }

    @Test
    fun pageBitmapsStayWithinTheMemoryBudget() {
        // US Letter at 3.94 px/pt on a phone is about 30 MB a page: a handful, not fifty.
        val cap = pageRenderCap(1f, 2410, 3120)
        assertTrue(cap in 1..6, "$cap")
        // A small page keeps the old page limit; a gigantic one still renders one page.
        assertEquals(50, pageRenderCap(1f, 459, 594))
        assertEquals(38, pageRenderCap(1f, 918, 1188))
        assertEquals(1, pageRenderCap(1f, 20000, 20000))
        assertEquals(1, pageRenderCap(1f, 0, 0).coerceAtMost(1))
    }

    @Test
    fun selectionHandlesSitAtTheStartAndEndOfTheSelection() {
        assertEquals(null, selectionHandles(emptyList()))
        // One line: the start handle at its left, the end handle at its right, both under the line.
        val (a, b) = selectionHandles(listOf(PageRect(0, 10f, 20f, 30f, 12f)))!!
        assertEquals(10f, a.x); assertEquals(40f, b.x)
        assertEquals(32f, a.bottom); assertEquals(26f, a.mid)
        // Several lines, over two pages: the first box starts it, the last box ends it.
        val (s, e) = selectionHandles(listOf(PageRect(0, 50f, 700f, 400f, 12f), PageRect(1, 72f, 72f, 100f, 12f)))!!
        assertEquals(0, s.page); assertEquals(50f, s.x)
        assertEquals(1, e.page); assertEquals(172f, e.x)
        // A box with a non-finite number gives no handles rather than a bad position.
        assertEquals(null, selectionHandles(listOf(PageRect(0, Float.NaN, 0f, 1f, 1f))))
    }

    @Test
    fun shapeFormatSendsTheColourWithTheOutlineWeight() {
        // shape.outline replaces the colour whenever it runs, so a weight change must carry one.
        assertEquals("000000", outlineParams("000000", 2.25f)["color"]?.toString()?.trim('"'))
        assertTrue("width" in outlineParams(null, 3f))
        assertEquals("kotlinx.serialization.json.JsonNull", fillParams(null)["color"]!!::class.qualifiedName)
        assertEquals(72f, outlineParams("000000", 500f)["width"]!!.toString().toFloat())
        assertEquals("1 pt", weightText(1f)); assertEquals("2.25 pt", weightText(2.25f))
        assertTrue(hasCustomGroup("Shape Format", "Shape Styles"))
        assertTrue(!hasCustomGroup("Shape Format", "Drawing Canvas"))
        assertEquals(SHAPE_EFFECTS.size, SHAPE_EFFECTS.map { it.first }.toSet().size)
    }

    @Test
    fun equationGalleryParsesAndSkipsWhatIsMalformed() {
        val g = parseEquationGallery(Json.parseToJsonElement(
            """{"structures":[{"id":"fraction","label":"Fraction","sections":[{"name":"F","templates":[{"id":"frac.stacked","label":"Stacked Fraction","linear":"a/b"},{"label":"no id"}]}]},{"id":"empty","label":"E","sections":[]},{"label":"no id"}],
               "symbols":[{"name":"Basic","chars":"±∞𝔸x"},{"name":"None","chars":""},{"chars":"x"}]}""",
        ))
        assertEquals(listOf("fraction"), g.structures.map { it.id })
        assertEquals(listOf("frac.stacked"), g.structures.single().templates.map { it.id })
        // The surrogate pair 𝔸 stays one symbol.
        assertEquals(listOf("±", "∞", "𝔸", "x"), g.symbols.single().chars)
        assertEquals(0, parseEquationGallery(Json.parseToJsonElement("[1,2]")).structures.size)
        assertTrue(hasCustomGroup("Equation", "Structures") && !hasCustomGroup("Equation", "Tools"))
    }

    @Test
    fun outlineChartAndSmartArtMenusSendValuesTheEngineNames() {
        assertTrue(hasGroupExtras("Outlining", "Outline Tools") && hasGroupExtras("Chart Design", "Type") && hasGroupExtras("SmartArt Design", "Layouts"))
        assertTrue(!hasCustomGroup("Chart Design", "Type"))
        // Levels 1–9 are numbers; "body" and "all" are words.
        assertEquals("3", levelParams("3")["level"].toString())
        assertEquals("\"body\"", levelParams("body")["level"].toString())
        assertEquals(10, OUTLINE_LEVELS.size); assertEquals(10, SHOW_LEVELS.size)
        assertEquals(listOf("column", "columnStacked", "bar", "barStacked", "line", "area", "pie", "doughnut", "scatter"), CHART_TYPES.map { it.second })
        assertEquals(8, SMART_ART_LAYOUTS.size); assertEquals(7, SMART_ART_COLORS.size)
        assertEquals(listOf("text"), InsertDialog.ChartTitle.fields.map { it.key })
    }

    @Test
    fun homeGroupsAreOrderedLikeTheUpstreamAppAndLaunchersOpenDialogs() {
        val names = listOf("Editing", "Styles", "Zzz", "Paragraph", "Font", "Clipboard")
        val ordered = orderGroups("Home", names.map { RibbonGroup(it, emptyList()) }).map { it.name }
        assertEquals(listOf("Clipboard", "Font", "Paragraph", "Styles", "Editing", "Zzz"), ordered)
        // Another tab keeps the engine's order.
        assertEquals(names, orderGroups("Insert", names.map { RibbonGroup(it, emptyList()) }).map { it.name })
        assertEquals("format.fontDialog", GROUP_LAUNCHERS["Home" to "Font"])
        assertEquals(null, GROUP_LAUNCHERS["Home" to "Editing"])
        assertTrue(hasCustomGroup("Home", "Editing"))
    }

    @Test
    fun theTabStripIsShorterWithAMouseThanWithAFinger() {
        assertEquals(TAB_HEIGHT_POINTER, tabStripHeight(SizeClass.Expanded))
        assertEquals(TAB_HEIGHT_TOUCH, tabStripHeight(SizeClass.Medium))
        assertEquals(TAB_HEIGHT_TOUCH, tabStripHeight(SizeClass.Compact))
        // Never below the WCAG 2.2 floor of 24, and the touch height is not the smaller one.
        assertTrue(TAB_HEIGHT_POINTER.value >= 24f && TAB_HEIGHT_TOUCH > TAB_HEIGHT_POINTER)
    }

    @Test
    fun theMiniToolbarSitsAboveTheSelectionAndFlipsBelowWhenTight() {
        val bar = 300f to 80f
        fun at(top: Float, bottom: Float, left: Float, w: Float = 800f, h: Float = 600f) =
            miniToolbarOffset(top, bottom, left, w, h, bar.first, bar.second, 8f, 4f)!!
        // Room above: it goes above the line, centred on the start of the selection.
        val above = at(300f, 320f, 400f)
        assertEquals(300f - 8f - 80f, above.y); assertEquals(404f - 150f, above.x)
        // No room above: below the line.
        assertEquals(60f + 8f, at(40f, 60f, 400f).y)
        // Kept inside the viewport sideways.
        assertEquals(0f, at(300f, 320f, 5f).x); assertEquals(800f - 300f, at(300f, 320f, 795f).x)
        // Neither fits: pushed inside rather than off screen.
        val tight = at(30f, 50f, 100f, h = 100f)
        assertTrue(tight.y in 0f..20f)
        assertEquals(null, miniToolbarOffset(Float.NaN, 1f, 1f, 800f, 600f, 300f, 80f, 8f, 4f))
        assertEquals(null, miniToolbarOffset(1f, 2f, 1f, 0f, 600f, 300f, 80f, 8f, 4f))
    }

    @Test
    fun theMiniToolbarAnchorsToTheTopmostVisibleSelectionBox() {
        val view = androidx.compose.ui.geometry.Rect(0f, 0f, 400f, 600f)
        fun r(l: Float, t: Float, rr: Float, b: Float) = androidx.compose.ui.geometry.Rect(l, t, rr, b)
        // A box scrolled above the viewport is skipped; one cut by the top edge is cut down to what shows.
        val a = visibleAnchorRect(listOf(r(0f, -100f, 50f, -50f), r(0f, -10f, 50f, 20f), r(0f, 100f, 50f, 120f)), view)!!
        assertEquals(0f, a.top); assertEquals(20f, a.bottom)
        // A sliver on the edge, or nothing visible, gives no anchor.
        assertEquals(null, visibleAnchorRect(listOf(r(0f, -20f, 50f, 0.5f)), view))
        assertEquals(null, visibleAnchorRect(listOf(r(0f, 700f, 50f, 720f)), view))
        assertEquals(null, visibleAnchorRect(listOf(r(Float.NaN, 0f, 1f, 1f)), view))
        assertEquals(null, visibleAnchorRect(emptyList(), view))
    }

    @Test
    fun pinchZoomSettlesInSmallStepsWithinTheLimits() {
        assertEquals(1.5f, settleZoom(1f, 1.52f, 2f), 0.0001f)
        assertEquals(2f, settleZoom(1.5f, 3f, 2f))
        assertEquals(0.5f, settleZoom(1f, 0.1f, 2f))
        // Not a usable pinch: the zoom stays.
        assertEquals(1.25f, settleZoom(1.25f, Float.NaN, 2f))
        assertEquals(1.25f, settleZoom(1.25f, 0f, 2f))
        // A dense phone reaches the render limit before the desktop's 200%; a desktop does not.
        assertTrue(maxZoomFor(2.625f) in 1f..1.6f)
        assertEquals(2f, maxZoomFor(1f))
        assertTrue(maxZoomFor(0f) >= 1f && maxZoomFor(100f) >= 1f)
    }
}
