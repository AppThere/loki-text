package app.loki.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class PasteSpecialTest {
    @Test fun readsTheFormatsTheEngineLists() {
        val formats = parsePasteFormats(Json.parseToJsonElement("""{"formats":[{"id":"formatted","label":"WordCraft Document"},{"id":"text"},{"label":"no id"}]}"""))
        assertEquals(listOf("formatted" to "WordCraft Document", "text" to "text"), formats)
        assertEquals(emptyList(), parsePasteFormats(Json.parseToJsonElement("""{"formats":[]}""")))
        assertEquals(emptyList(), parsePasteFormats(null))
    }

    @Test fun theChoiceKeepsTheClipboardText() {
        val req = PasteSpecialRequest(listOf("text" to "Unformatted Text"), buildJsonObject { put("text", "Hello") })
        assertEquals(buildJsonObject { put("text", "Hello"); put("as", "text") }, pasteSpecialParams(req, "text"))
    }
}
