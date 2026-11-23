package app.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipientsTest {
    @Test fun rowsComeAsObjectsOrArrays() {
        val byObject = parseRecipients(Json.parseToJsonElement("""{"headers":["Name","City"],"rows":[{"Name":"Ann","City":null},{"City":"Oslo"}],"records":2}"""))
        assertEquals(RecipientTable(listOf("Name", "City"), listOf(listOf("Ann", ""), listOf("", "Oslo"))), byObject)
        val byArray = parseRecipients(Json.parseToJsonElement("""{"headers":["A","B"],"rows":[["1"],["2","3","extra"]]}"""))
        assertEquals(listOf(listOf("1", ""), listOf("2", "3")), byArray?.rows)
        assertNull(parseRecipients(Json.parseToJsonElement("[]")))
        assertNull(parseRecipients(null))
    }

    @Test fun editsKeepEveryRowAsLongAsTheFieldList() {
        var t = RecipientTable(listOf("A"), listOf(listOf("x")))
        t = t.withField("B").withRow()
        assertEquals(listOf(listOf("x", ""), listOf("", "")), t.rows)
        t = t.withCell(1, 1, "z").withoutField(0)
        assertEquals(RecipientTable(listOf("B"), listOf(listOf(""), listOf("z"))), t)
        assertEquals(listOf(listOf("z")), t.withoutRow(0).rows)
        assertEquals("""{"fields":["B"],"rows":[[""],["z"]]}""", t.toParams().toString())
    }

    @Test fun aNewFieldNeedsANameNotAlreadyUsed() {
        val t = RecipientTable(listOf("Name"), emptyList())
        assertFalse(t.canAddField(" "))
        assertFalse(t.canAddField("name"))
        assertTrue(t.canAddField("City"))
    }
}
