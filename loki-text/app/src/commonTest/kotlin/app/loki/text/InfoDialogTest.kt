package app.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfoDialogTest {
    private val info = parseDocInfo(Json.parseToJsonElement("""{"path":null,"dirty":true,"pages":3,"words":120,"paragraphs":9,"sections":1,"comments":2,
        "trackChanges":false,"encrypted":false,"layoutMs":4,
        "properties":{"title":"T","subject":"","creator":"Ann","keywords":"k","description":"d","category":"","lastModifiedBy":"Bo","created":"2026-11-01T09:00:00Z","modified":"","revision":3}}"""))!!

    @Test fun readsTheFactsAndProperties() {
        assertEquals(DocProperties("T", "", "Ann", "k", "d", ""), info.properties)
        val rows = info.rows().toMap()
        assertEquals("Not saved yet", rows["File"])
        assertEquals("Has unsaved changes", rows["Status"])
        assertEquals("2026-11-01 09:00", rows["Created"])
        assertTrue("Last modified" !in rows && "Password" !in rows)
    }

    @Test fun onlyChangedPropertiesAreSent() {
        assertTrue(info.properties.changesFrom(info.properties).isEmpty())
        assertEquals("""{"title":"New","category":"Work"}""", info.properties.copy(title = "New", category = "Work").changesFrom(info.properties).toString())
    }

    @Test fun nothingReadableIsNull() = assertNull(parseDocInfo(null))
}
