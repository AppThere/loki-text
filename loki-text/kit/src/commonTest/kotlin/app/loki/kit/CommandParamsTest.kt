package app.loki.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandParamsTest {
    private fun form(doc: String) = parseParamForm(doc)
    private fun ParamForm.field(name: String) = fields.first { it.name == name }
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun requiredAndOptionalKeysBecomeFieldsOfTheirKind() {
        val f = form("""{"text": string, "raw"?: bool, "paragraphs"?: bool} (paragraphs: each line a paragraph, one undo step)""")
        assertEquals(listOf("text", "raw", "paragraphs"), f.fields.map { it.name })
        assertEquals(listOf(false, true, true), f.fields.map { it.optional })
        assertEquals(listOf(FieldKind.Text, FieldKind.Bool, FieldKind.Bool), f.fields.map { it.kind })
        assertTrue(f.notes.startsWith("(paragraphs"))
        assertFalse(f.alternatives)
    }

    @Test
    fun valuesListedInOneStringOrAsAlternativesAreChoices() {
        val a = form("""{"kind": "page|column|textWrapping|nextPage"}""").field("kind")
        assertEquals(FieldKind.Choice, a.kind)
        assertEquals(listOf("page", "column", "textWrapping", "nextPage"), a.options.map { it.text })
        val b = form("""{"value": "auto" | "narrow" | "medium" | "wide" | "page"}""").field("value")
        assertEquals(FieldKind.Choice, b.kind)
        assertEquals(5, b.options.size)
        // Numbers beside names are still a closed list; each is sent as what it is.
        val c = form("""{"value": "document" | 1.0 | 1.25 | 1.5 | 2.0}""").field("value")
        assertEquals(FieldKind.Choice, c.kind)
        assertEquals(JsonPrimitive("document"), fieldValue(c, "document"))
        assertEquals(1.25, fieldValue(c, "1.25")!!.jsonPrimitive.content.toDouble())
        assertFalse(fieldValue(c, "1.25")!!.jsonPrimitive.isString)
    }

    @Test
    fun aNumberThatAlsoTakesNamesIsANumberFieldWithSuggestions() {
        val f = form("""{"value": percent (10-500) | "pageWidth" | "onePage" | "multiplePages"}""").field("value")
        assertEquals(FieldKind.Number, f.kind)
        assertEquals(listOf("pageWidth", "onePage", "multiplePages"), f.options.map { it.text })
        assertEquals("10-500", f.hint)
        assertEquals(JsonPrimitive(150L), fieldValue(f, "150"))
        assertEquals(JsonPrimitive("onePage"), fieldValue(f, "onePage"))
        assertTrue(runCatching { fieldValue(f, "wide") }.isFailure)
    }

    @Test
    fun coloursTakeHexAndTheNamedAlternatives() {
        val f = form("""{"color": "RRGGBB" | null}""").field("color")
        assertEquals(FieldKind.Color, f.kind)
        assertEquals(JsonPrimitive("1F4E79"), fieldValue(f, "#1f4e79"))
        assertEquals(JsonNull, fieldValue(f, "null"))
        assertTrue(runCatching { fieldValue(f, "blue") }.isFailure)
        val auto = form("""{"color": "RRGGBB" | "auto"} (the underline's own colour)""").field("color")
        assertEquals(JsonPrimitive("auto"), fieldValue(auto, "auto"))
    }

    @Test
    fun pathsPositionsAndListsGetTheControlThatFits() {
        assertEquals(FieldKind.Path, form("""{"path": string, "markup"?: bool}""").field("path").kind)
        assertEquals(FieldKind.Json, form("""{"anchor": Pos, "focus": Pos}""").field("anchor").kind)
        val items = form("""{"items": [string | {"text": string, "level"?: 0-7}]} (replaces the outline)""").field("items")
        assertEquals(FieldKind.Json, items.kind)
        assertEquals(json("""["a","b"]"""), fieldValue(items, """["a", "b"]"""))
        assertTrue(runCatching { fieldValue(items, "[oops") }.isFailure)
        assertEquals(FieldKind.Number, form("""{"level": 0-8}""").field("level").kind)
        assertEquals(FieldKind.Number, form("""{"width"?: pt, "count": n}""").field("width").kind)
    }

    @Test
    fun keysWithoutATypeAndKeysOfferedTogetherAreRead() {
        val props = form("""{"title"?, "subject"?, "author"?, "custom"?: {name: string | {"value": x}}}""")
        assertEquals(listOf("title", "subject", "author", "custom"), props.fields.map { it.name })
        assertEquals(FieldKind.Text, props.field("title").kind)
        assertEquals(FieldKind.Json, props.field("custom").kind)
        val either = form("""{"delta": pt (-1638..1638, not 0) | "percent": number (-99..1000, not 0; 10 = +10%)} (exactly one)""")
        assertEquals(listOf("delta", "percent"), either.fields.map { it.name })
        assertTrue(either.fields.all { it.optional && it.kind == FieldKind.Number })
        val several = form("""{"enabled"?: bool (all of AutoCorrect), "replaceText"|"twoInitialCaps"|"capSentences"?: bool}""")
        assertEquals(listOf("enabled", "replaceText", "twoInitialCaps", "capSentences"), several.fields.map { it.name })
    }

    @Test
    fun aDocWithSeveralShapesRequiresNothingAndKeepsTheFirst() {
        val f = form("""{"anchor": Pos, "focus": Pos} | {"from": {"page": n, "x": pt, "y": pt}, "to": {"page": n}} | {"on"?: bool}""")
        assertTrue(f.alternatives)
        assertEquals(listOf("anchor", "focus"), f.fields.map { it.name })
        assertTrue(buildParams(f, emptyMap()).getOrThrow().isEmpty())
    }

    @Test
    fun whatCannotBeReadIsNeverAnError() {
        for (doc in listOf("", "{}", "{", "}{", "{\"a\": ", "nonsense", "{\"a\": (unclosed", "{,,,}", "{\"\": string}", "{\"a\"?: \"x|y\" | [", "\u0000{\"a\": 1}")) {
            val f = form(doc)
            assertEquals(f.fields.map { it.name }.distinct(), f.fields.map { it.name }, doc)
        }
        assertTrue(form("{}").fields.isEmpty())
        assertTrue(form("{} → {\"removePersonalInfo\": bool}").fields.isEmpty())
    }

    @Test
    fun theFormSendsFilledFieldsAndSaysWhatIsMissing() {
        val f = form("""{"kind": "rectangle|ellipse", "width"?: pt, "fill"?: "RRGGBB"}""")
        assertEquals(json("""{"kind":"ellipse","width":120}"""), buildParams(f, mapOf("kind" to "ellipse", "width" to "120", "fill" to " ")).getOrThrow())
        assertEquals("Kind is needed", buildParams(f, mapOf("width" to "120")).exceptionOrNull()?.message)
        assertEquals("Width: needs a number", buildParams(f, mapOf("kind" to "ellipse", "width" to "wide")).exceptionOrNull()?.message)
        // The JSON box, when used, is sent as it is.
        assertEquals(json("""{"kind":"star"}"""), buildParams(f, emptyMap(), """{"kind": "star"}""").getOrThrow())
        assertTrue(buildParams(f, emptyMap(), "[1]").isFailure)
        assertEquals(1.5, (buildParams(f, mapOf("kind" to "ellipse", "width" to "1,5")).getOrThrow()["width"] as JsonPrimitive).content.toDouble())
    }

    @Test
    fun fieldNamesReadAsWords() {
        assertEquals("Space before", fieldLabel("spaceBefore"))
        assertEquals("Line numbers2 d", fieldLabel("lineNumbers2D"))
        assertEquals("Path", fieldLabel("path"))
    }

    @Test
    fun onlyAnswersWorthReadingAreShown() {
        assertFalse(isInformative(null))
        assertFalse(isInformative(json("""{"value": true}""")))
        assertFalse(isInformative(json("""{"fit": "", "zoom": 1.0}""")))
        assertFalse(isInformative(json("""{"anchor": {"off": 1}, "focus": {"off": 1}}""")))
        assertTrue(isInformative(json("""{"words": 3, "pages": 1, "lines": 2}""")))
        assertTrue(isInformative(json("""{"issues": []}""")))
        assertTrue(isInformative(json("[]")))
        val profile = CommandProfile(tabs = listOf("Review"), quietResults = setOf("view.state"), quietPrefixes = listOf("draw."))
        val count = CommandInfo("review.wordCount", "Word Count", "Review › Proofing", "{}", "", mutates = false)
        assertTrue(profile.showsResult(count, json("""{"words": 3, "pages": 1, "lines": 2}""")))
        // A command that changes the document reports through the surface, not a dialog.
        assertFalse(profile.showsResult(CommandInfo("x.y", "X", "Home", "{}", "", mutates = true), json("""{"a": 1, "b": 2, "c": 3}""")))
        assertFalse(profile.showsResult(CommandInfo("view.state", "State", "View", "{}", "", mutates = false), json("""{"a": 1, "b": 2, "c": 3}""")))
        assertFalse(profile.showsResult(CommandInfo("draw.pen", "Pen", "Draw", "{}", "", mutates = false), json("""{"a": 1, "b": 2, "c": 3}""")))
    }

    @Test
    fun answersAreSetOutAsNamedLines() {
        val lines = describeResult(json("""{"words": 3, "dirty": false, "path": null, "properties": {"title": "Plan"}, "tags": ["a", "b"], "issues": []}"""))
        assertEquals(listOf("Words" to "3", "Dirty" to "No", "Path" to "—", "Properties" to "", "Title" to "Plan", "Tags" to "a, b", "Issues" to "None"), lines.map { it.name to it.value })
        assertEquals(1, lines.first { it.name == "Title" }.depth)
        assertEquals("Nothing to show", describeResult(json("[]")).single().value)
        val list = describeResult(json("""[{"issue": "No title"}, {"issue": "No alt text"}]"""))
        assertEquals(listOf("1", "Issue", "2", "Issue"), list.map { it.name })
        // A huge answer is cut, with a line that says so.
        val big = describeResult(json((1..2000).joinToString(prefix = "{", postfix = "}") { "\"k$it\": $it" }))
        assertTrue(big.size <= 401 && big.last().value == "… and more")
    }

    private val profile = CommandProfile(
        tabs = listOf("Home", "View"), contextualTabs = listOf("Table Layout"), otherPlaces = listOf("File"),
        needsInput = setOf("format.font"), paramDocs = mapOf("design.themeColors" to """{"name": "Craft|Studio"}"""),
        pointerGestures = setOf("caret.set"), pointerGaps = setOf("table.resizeDrag"), typing = setOf("text.insert"), stateReaders = setOf("view.state"),
    )
    private fun commands(vararg c: Triple<String, String, String>) = Json.parseToJsonElement(
        c.joinToString(prefix = "[", postfix = "]") { (id, loc, params) ->
            """{"id":"$id","label":"$id","location":"$loc","shortcut":"","params":${Json.encodeToString(kotlinx.serialization.serializer<String>(), params)}}"""
        },
    )

    @Test
    fun theAllCommandsListCoversEveryPlaceAndSearchesNamesPlacesAndIds() {
        fun c(id: String, loc: String, params: String = "{}") = CommandInfo(id, id.substringAfter('.'), loc, params, "", true)
        val all = listOf(c("format.bold", "Home › Font"), c("file.exportPdf", "File › Export", """{"path": string}"""), c("caret.set", "Navigation"), c("x.new", "Somewhere New"), c("view.zoom", "View › Zoom"))
        // The ribbon's tabs in order, then File and the rest, then a place this app has never heard of; a pointer command is not listed.
        assertEquals(listOf("Home", "View", "File", "Somewhere New"), catalogSections(all, profile))
        assertTrue(matchesQuery(all[1], "export pdf"))
        assertTrue(matchesQuery(all[1], "FILE"))
        assertTrue(matchesQuery(all[1], "file.exportPdf"))
        assertFalse(matchesQuery(all[1], "export png"))
        assertTrue(all[1].needsInput && all[1].hasParams)
        assertFalse(all[0].needsInput || all[0].hasParams)
        val parsed = parseCommands(json("""[{"id":"ui.dark","label":"Dark"},{"id":"a.b","label":"","location":"Home › G","params":"{}","shortcut":"","mutates":false},7,{"label":"no id"}]"""), profile)
        assertEquals(listOf("a.b"), parsed.map { it.id })
        assertEquals("a.b", parsed.single().label)
        assertFalse(parsed.single().mutates)
        assertTrue(parseCommands(null, profile).isEmpty())
    }

    @Test
    fun theProfileDecidesWhatNeedsInputAndSuppliesMissingDocs() {
        val parsed = parseCommands(commands(
            Triple("format.font", "Home › Font", "{\"name\"?: string}"),
            Triple("design.themeColors", "Home › Design", "{}"),
            Triple("format.bold", "Home › Font", "{}"),
            Triple("nav.headings", "View › Show", "(no params) → {\"headings\": [{\"level\": 1-9}]}"),
            Triple("font.missing", "View › Show", "{} → {\"missing\": [{\"font\": string}]}"),
        ), profile).associateBy { it.id }
        // A rule in prose, known only to the profile; and a doc the profile supplies.
        assertTrue(parsed.getValue("format.font").needsInput)
        assertTrue(parsed.getValue("design.themeColors").needsInput)
        assertEquals(listOf("name"), parseParamForm(parsed.getValue("design.themeColors").params).fields.map { it.name })
        assertFalse(parsed.getValue("format.bold").needsInput)
        // What a doc says the command returns is not input.
        assertFalse(parsed.getValue("nav.headings").needsInput || parsed.getValue("nav.headings").hasParams)
        assertFalse(parsed.getValue("font.missing").needsInput)
    }

    @Test
    fun theRibbonHoldsEveryListedCommandOfItsTabs() {
        val json = commands(
            Triple("format.size", "Home › Font", "{\"size\": number}"), Triple("format.bold", "Home › Font", "{}"),
            Triple("table.resizeDrag", "Table Layout › Cell Size", "{\"x\": pt}"), Triple("caret.set", "Table Layout › Draw", "{\"x\": pt}"),
            Triple("file.info", "File", "{}"), Triple("ui.dark", "View", "{}"),
        )
        val tabs = parseRibbon(json, profile)
        assertEquals(listOf("Home"), tabs.map { it.name })
        val items = tabs.single().groups.single().items
        assertEquals(listOf("format.size…", "format.bold"), items.map { it.menuLabel })
        // A group whose commands are all gestures is dropped unless the app draws it.
        val kept = parseRibbon(json, profile) { tab, group -> tab == "Table Layout" && group == "Cell Size" }
        assertEquals(listOf("Home", "Table Layout"), kept.map { it.name })
        assertTrue(kept.last().groups.single().items.isEmpty())
        assertEquals("Ctrl+O", displayShortcut("Mod+O / Mod+F12"))
    }

    @Test
    fun theCoverageCheckNamesWhatHasNoWay() {
        val json = commands(
            Triple("format.bold", "Home › Font", "{}"), Triple("caret.set", "Navigation", "{\"x\": pt}"), Triple("table.resizeDrag", "Table Layout › Cell Size", "{\"x\": pt}"),
            Triple("text.insert", "Editing", "{\"text\": string}"), Triple("view.state", "View", "{}"), Triple("odd.one", "Home › Odd", "{} | {}"),
        )
        val ribbon = parseRibbon(json, profile)
        assertEquals(emptyList(), coverageProblems(json, profile, ribbon, { _, _ -> false }, listOf("format.bold"), setOf("odd.one"), maxGaps = 1))
        // Each kind of problem is reported in words.
        val problems = coverageProblems(json, profile, ribbon.map { RibbonTab(it.name, it.groups + RibbonGroup("Empty", emptyList())) }, { _, _ -> false }, listOf("gone.away"), emptySet(), maxGaps = 0)
        assertTrue(problems.any { "odd.one needs input" in it }, problems.toString())
        assertTrue(problems.any { "gone.away" in it })
        assertTrue(problems.any { "may only shrink" in it })
        assertTrue(problems.any { "Home › Empty is empty" in it })
        // A profile that names a command the engine dropped is told so.
        val stale = CommandProfile(tabs = listOf("Home"), stateReaders = setOf("old.state"))
        assertTrue(coverageProblems(json, stale, parseRibbon(json, stale), { _, _ -> false }, emptyList(), setOf("odd.one"), 0).any { "old.state" in it })
        // A command on a tab that the app's ribbon leaves out.
        assertTrue(coverageProblems(json, profile, emptyList(), { _, _ -> false }, emptyList(), setOf("odd.one"), 1).any { "format.bold is on tab Home but not on the ribbon" in it })
    }

    @Test
    fun docsWithBareKeysAndBareValuesAreRead() {
        // As GridCraft, DeckCraft, VectorCraft and most of the others write them.
        val a = form("{kind: chart|image|shape|slicer, id, mode: moveAndSize|moveOnly|absolute}")
        assertEquals(listOf("kind", "id", "mode"), a.fields.map { it.name })
        assertEquals(FieldKind.Choice, a.field("kind").kind)
        assertEquals(listOf("chart", "image", "shape", "slicer"), a.field("kind").options.map { it.text })
        assertEquals(JsonPrimitive("moveOnly"), fieldValue(a.field("mode"), "moveOnly"))
        assertEquals(FieldKind.Text, a.field("id").kind)
        assertFalse(a.field("id").optional)

        val b = form("{action?: list (default) | add | modify | delete | unlock, title?, range?, password?} → ranges")
        assertEquals(FieldKind.Choice, b.field("action").kind)
        assertEquals(5, b.field("action").options.size)
        assertTrue(b.fields.all { it.optional })
        assertEquals("→ ranges", b.notes)

        val c = form("{preset?: normal|none|narrow|wide, left?, right?, top?, bottom?: pt, cells?, id?}")
        assertEquals(FieldKind.Number, c.field("bottom").kind)
        assertEquals(7, c.fields.size)

        // Kinds of value are not a choice between two words; an open list takes any text.
        assertEquals(FieldKind.Text, form("{item: id|name}").field("item").kind)
        val open = form("{type?: column|bar|line|…, rect?, series?: [{name, values}]}")
        assertEquals(FieldKind.Text, open.field("type").kind)
        assertEquals(listOf("column", "bar", "line"), open.field("type").options.map { it.text })
        assertEquals(FieldKind.Json, open.field("series").kind)

        assertEquals(FieldKind.Bool, form("{on?: bool}").field("on").kind)
        assertEquals(FieldKind.Choice, form("{slot: F10|F11|F12, comp?}").field("slot").kind)
        assertEquals(FieldKind.Choice, form("{direction: 1|-1, count?: n}").field("direction").kind)
        assertEquals(FieldKind.Number, form("{index, x, y: slide pt, id?}").field("y").kind)
        // One number alone is the default, not a choice of one.
        val d = form("{minPpi?: 150} → issues")
        assertEquals(FieldKind.Number, d.field("minPpi").kind)
        assertTrue(d.field("minPpi").options.isEmpty() && "150" in d.field("minPpi").hint)
        // One of several keys.
        val e = form("{ids?|id?} detach from the grid")
        assertEquals(listOf("ids", "id"), e.fields.map { it.name })
        assertTrue(runsWithoutParams("{ids?|id?} detach from the grid"))
        assertFalse(runsWithoutParams("{align: left|center|right}"))
        assertTrue(runsWithoutParams("{} → [{id, title, summary}]"))
        // Prose after an empty object is not a rule about keys, whatever words it uses.
        assertTrue(runsWithoutParams("{} cut the paths at their anchors: one of each cut's two anchors stays selected"))
    }

    @Test
    fun docsThatMarkOptionalAndDefaultsAfterTheTypeAreRead() {
        // As FilmCraft and PhotoCraft write them.
        val a = form("""{"position":[x,y]?,"size":[w,h]=[400,200],"clip":id?,"seconds":f64=5,"track":index?}""")
        assertEquals(listOf("position", "size", "clip", "seconds", "track"), a.fields.map { it.name })
        assertTrue(a.fields.all { it.optional })
        assertEquals(FieldKind.Json, a.field("position").kind)
        assertEquals(FieldKind.Number, a.field("seconds").kind)
        assertTrue("default 5" in a.field("seconds").hint)
        assertEquals(FieldKind.Number, a.field("track").kind)

        val b = form("""{"clips":[id]?,"speed":percent=100,"reverse":bool,"interpolation":"frameSampling|frameBlending|opticalFlow"?}""")
        assertEquals(FieldKind.Choice, b.field("interpolation").kind)
        assertTrue(b.field("interpolation").optional)
        assertFalse(b.field("reverse").optional)
        assertEquals(FieldKind.Bool, b.field("reverse").kind)
        assertFalse(runsWithoutParams("""{"name":str}"""))
        assertTrue(runsWithoutParams("""{"on":bool?}"""))
        assertTrue(runsWithoutParams("""{"wait":bool=false}"""))

        val c = form("""{"maxRadius":4..127=8,"channel1":-360..360=108}""")
        assertEquals(FieldKind.Number, c.field("maxRadius").kind)
        assertEquals(FieldKind.Number, c.field("channel1").kind)
        assertEquals(JsonPrimitive(12L), fieldValue(c.field("maxRadius"), "12"))
        // Several shapes: nothing is required.
        assertTrue(form("""{"path":str} | {"back":true} | {"forward":true}""").alternatives)
    }

    @Test
    fun commandsAreReadFromEitherListingShape() {
        val sibling = parseCommands(json("""[
            {"id":"object.align","label":"Align","menu":["Object","Align"],"shortcut":null,"params":"{align: left|center|right}","undoable":true},
            {"id":"view.zoomIn","label":"Zoom In","menu":["View"],"shortcut":"Cmd+=","params":"{}","journal":false},
            {"id":"mix.state","label":"State","menu":[],"shortcut":null,"params":"{}"}
        ]"""), CommandProfile(tabs = listOf("Object", "View")))
        assertEquals(listOf("Object › Align", "View", ""), sibling.map { it.location })
        assertEquals("Object", sibling[0].tab)
        assertEquals("Align", sibling[0].group)
        assertEquals(listOf(true, false, true), sibling.map { it.mutates })
        assertEquals("", sibling[0].shortcut)
        assertTrue(sibling[0].needsInput)
        // A command on no menu has no place of its own: the all-commands list files it under Other.
        assertEquals(listOf("Object", "View", "Other"), catalogSections(sibling, CommandProfile(tabs = listOf("Object", "View"))))
    }

    @Test
    fun fitsAreWorkedOutFromTheContentAndTheWindow() {
        assertEquals(1f, fitZoom("pageWidth", 612f, 792f, 918f, 594f, 1.5f)!!, 0.001f)
        assertEquals(0.5f, fitZoom("whole", 612f, 792f, 918f, 594f, 1.5f)!!, 0.001f)
        assertNull(fitZoom("", 612f, 792f, 918f, 594f, 1.5f))
        assertNull(fitZoom("width", 0f, 792f, 918f, 594f, 1.5f))
    }

    @Test
    fun aReplyThatIsNotJsonIsAnEngineErrorNotACrash() {
        class Fake(val reply: String) : EngineTransport {
            override fun callRaw(method: String, paramsJson: String?) = reply
            override fun close() {}
        }
        assertEquals("x", (EngineClient(Fake("""{"ok":true,"result":"x"}""")).call("m") as JsonPrimitive).content)
        assertEquals("no", runCatching { EngineClient(Fake("""{"ok":false,"error":"no"}""")).call("m") }.exceptionOrNull()?.message)
        for (bad in listOf("", "not json", "[1]", "7", "{\"ok\":")) assertTrue(runCatching { EngineClient(Fake(bad)).call("m") }.exceptionOrNull() is EngineException, bad)
        assertEquals(1, EngineClient(Fake("""{"ok":true,"result":[{"open":"x"},7]}""")).uiRequests().size)
    }
}
