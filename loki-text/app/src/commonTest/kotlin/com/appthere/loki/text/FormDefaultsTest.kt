package com.appthere.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormDefaultsTest {
    private val section = Json.parseToJsonElement("""{"marginTop":72.0,"marginLeft":90.5,"landscape":true,"pageW":612.0,"columns":{"count":2,"space":35.999999,"separator":false},"valign":"top","gutter":null}""")

    @Test fun numbersBooleansAndTextAreShownAsAFieldHoldsThem() {
        val d = formDefaults(FORM_DEFAULTS.getValue("layout.pageLayout"), section)
        assertEquals("72", d["top"])
        assertEquals("90.5", d["left"])
        assertEquals("true", d["landscape"])
        assertEquals("top", d["valign"])
        assertEquals("612", d["width"])
    }

    @Test fun missingOrNullValuesAreLeftBlank() {
        val d = formDefaults(FORM_DEFAULTS.getValue("layout.margins"), section)
        assertNull(d["gutter"])
        assertNull(d["bottom"])
    }

    @Test fun nestedValuesAndConvertedValues() {
        assertEquals(mapOf("count" to "2", "space" to "36", "separator" to "false"), formDefaults(FORM_DEFAULTS.getValue("layout.columns"), section))
        assertEquals(mapOf("value" to "landscape"), formDefaults(FORM_DEFAULTS.getValue("layout.orientation"), section))
    }

    @Test fun objectsComeAsJson() {
        val d = formDefaults(FORM_DEFAULTS.getValue("file.options"), Json.parseToJsonElement("""{"author":"Ann","marks":{"tabs":true}}"""))
        assertEquals("Ann", d["author"])
        assertEquals("""{"tabs":true}""", d["marks"])
    }

    @Test fun anythingUnreadableGivesNothing() {
        assertEquals(emptyMap(), formDefaults(FORM_DEFAULTS.getValue("para.indents"), null))
        assertEquals(emptyMap(), formDefaults(FORM_DEFAULTS.getValue("para.indents"), Json.parseToJsonElement("[1]")))
    }
}
