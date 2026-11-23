package app.loki.kit

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredParamsTest {
    private fun shape(doc: String, name: String): ParamShape? {
        val form = parseParamForm(doc)
        return shapeOf(form.fields.first { it.name == name }, form.defs)
    }

    @Test fun aListOfANamedTypeIsAListOfObjects() {
        val s = shape("""{"tabs"?: [TabStop] (replace), "clear"?: [pt]} where TabStop = {"pos": pt, "align"?: "left|center|right"}""", "tabs")
        val item = assertIs<ParamShape.Obj>(assertIs<ParamShape.ListOf>(s).item)
        assertEquals(listOf("pos", "align"), item.form.fields.map { it.name })
        val clear = assertIs<ParamShape.Scalar>(assertIs<ParamShape.ListOf>(shape("""{"clear"?: [pt]}""", "clear")).item)
        assertEquals(FieldKind.Number, clear.field.kind)
    }

    @Test fun nestedObjectsAndListsInsideThem() {
        val data = assertIs<ParamShape.Obj>(shape("""{"data": {"categories"?: [string], "series"?: [{"name": string, "values": [number|null]}]}}""", "data"))
        val series = assertIs<ParamShape.Obj>(assertIs<ParamShape.ListOf>(shapeOf(data.form.fields[1], data.form.defs)).item)
        assertIs<ParamShape.ListOf>(shapeOf(series.form.fields[1], series.form.defs))
    }

    @Test fun aStringOrObjectListDrawsTheObject() {
        val s = shape("""{"items": [string | {"text": string, "level"?: 0-7}]}""", "items")
        assertIs<ParamShape.Obj>(assertIs<ParamShape.ListOf>(s).item)
    }

    @Test fun shapesBeyondTheFormStayJson() {
        assertNull(shape("""{"styleLevels"?: {style name: 1-9}}""", "styleLevels"))
        assertNull(shape("""{"objects"?: [Pos | n]}""", "objects"))
    }

    @Test fun objectsBuildFromTheirFieldsAndBlankOnesAreLeftOut() {
        val rule = assertIs<ParamShape.Obj>(assertIs<ParamShape.ListOf>(shape("""{"rules"?: [{"field": string, "op": "equal|blank", "value"?: string}]}""", "rules")).item)
        assertEquals<Any?>(null, buildObject(rule.form, mapOf("field" to " ")).getOrThrow())
        assertEquals<Any?>(Json.parseToJsonElement("""{"field":"City","op":"equal","value":"Oslo"}"""), buildObject(rule.form, mapOf("field" to "City", "op" to "equal", "value" to "Oslo")).getOrThrow())
        assertTrue(buildObject(rule.form, mapOf("value" to "x")).isFailure, "a required field left blank is an error")
    }

    @Test fun listsBuildDropBlanksAndNameTheBadItem() {
        val tabs = assertIs<ParamShape.ListOf>(shape("""{"clear"?: [pt]}""", "clear"))
        assertEquals<Any?>(Json.parseToJsonElement("[36,72.5]"), buildList(tabs.item, listOf("36", "", "72.5")).getOrThrow())
        assertEquals<Any?>(null, buildList(tabs.item, listOf("", " ")).getOrThrow())
        val e = buildList(tabs.item, listOf("1", "x")).exceptionOrNull()
        assertTrue(e?.message.orEmpty().startsWith("Item 2"), e?.message)
    }

    @Test fun valuesRoundTripThroughTheirTexts() {
        val stop = assertIs<ParamShape.Obj>(assertIs<ParamShape.ListOf>(shape("""{"tabs"?: [TabStop]} where TabStop = {"pos": pt, "align"?: "left|right"}""", "tabs")).item)
        val v = Json.parseToJsonElement("""[{"pos":36,"align":"right"},{"pos":72}]""")
        val texts = listTexts(v)
        assertEquals(mapOf("pos" to "36", "align" to "right"), objectTexts(stop.form, parseJsonOrNull(texts[0])))
        assertEquals<Any?>(v, buildList(stop, texts).getOrThrow())
    }

    @Test fun pairsAndNamedValueLists() {
        val widths = assertIs<ParamShape.Tuple>(assertIs<ParamShape.ListOf>(shape("""{"widths"?: [[width pt, space after pt], …]}""", "widths")).item)
        assertEquals(listOf("width", "space after"), widths.fields.map { it.name })
        assertEquals<Any?>(Json.parseToJsonElement("[144,18]"), buildTuple(widths, listOf("144", "18")).getOrThrow())
        assertTrue(buildTuple(widths, listOf("144", "")).isFailure)
        assertNull(shape("""{"values"?: [[cell, …], …]}""", "values"))
        val remove = assertIs<ParamShape.Scalar>(assertIs<ParamShape.ListOf>(shape("""{"remove"?: ["comments", "revisions", "hidden"]}""", "remove")).item)
        assertEquals(listOf("comments", "revisions", "hidden"), remove.field.options.map { it.text })
    }
}
