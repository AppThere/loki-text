package app.loki.text

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
        val count = CommandInfo("review.wordCount", "Word Count", "Review › Proofing", "{}", "", mutates = false)
        assertTrue(showsResult(count, json("""{"words": 3, "pages": 1, "lines": 2}""")))
        // A command that changes the document reports through the page, not a dialog.
        assertFalse(showsResult(CommandInfo("x.y", "X", "Home", "{}", "", mutates = true), json("""{"a": 1, "b": 2, "c": 3}""")))
        assertFalse(showsResult(CommandInfo("view.state", "State", "View", "{}", "", mutates = false), json("""{"a": 1, "b": 2, "c": 3}""")))
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

    @Test
    fun theAllCommandsListCoversEveryPlaceAndSearchesNamesPlacesAndIds() {
        fun c(id: String, loc: String, params: String = "{}") = CommandInfo(id, id.substringAfter('.'), loc, params, "", true)
        val all = listOf(c("format.bold", "Home › Font"), c("file.exportPdf", "File › Export", """{"path": string}"""), c("caret.set", "Navigation"), c("x.new", "Somewhere New"), c("view.zoom", "View › Zoom"))
        // The ribbon's tabs in order, then File and the rest, then a place this app has never heard of; a pointer command is not listed.
        assertEquals(listOf("Home", "View", "File", "Somewhere New"), catalogSections(all))
        assertTrue(matchesQuery(all[1], "export pdf"))
        assertTrue(matchesQuery(all[1], "FILE"))
        assertTrue(matchesQuery(all[1], "file.exportPdf"))
        assertFalse(matchesQuery(all[1], "export png"))
        assertTrue(all[1].needsInput && all[1].hasParams)
        assertFalse(all[0].needsInput || all[0].hasParams)
        val parsed = parseCommands(json("""[{"id":"ui.dark","label":"Dark"},{"id":"a.b","label":"","location":"Home › G","params":"{}","shortcut":"","mutates":false},7,{"label":"no id"}]"""))
        assertEquals(listOf("a.b"), parsed.map { it.id })
        assertEquals("a.b", parsed.single().label)
        assertFalse(parsed.single().mutates)
        assertTrue(parseCommands(null).isEmpty())
    }
}
