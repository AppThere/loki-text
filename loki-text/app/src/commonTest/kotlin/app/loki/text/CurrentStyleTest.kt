package app.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrentStyleTest {
    private fun preview(id: String) = StylePreview(id, id, null, null, false, false, false)
    private val gallery = listOf("Normal", "Heading1", "Strong").map(::preview)
    private fun state(json: String) = parseCommandState(Json.parseToJsonElement(json))

    @Test fun readsTheStylesAtTheCaret() {
        val s = state("""{"disabled":{},"on":[],"styleAt":{"paragraph":"Heading1","character":null}}""")
        assertEquals("Heading1", s.styleParagraph)
        assertNull(s.styleCharacter)
    }

    @Test fun theParagraphStyleIsMarked() =
        assertEquals("Heading1", currentStyle(gallery, CommandState(emptyMap(), emptySet(), styleParagraph = "Heading1")))

    @Test fun aCharacterStyleInTheTextWins() =
        assertEquals("Strong", currentStyle(gallery, CommandState(emptyMap(), emptySet(), styleParagraph = "Normal", styleCharacter = "Strong")))

    @Test fun aStyleOutsideTheGalleryFallsBackOrMarksNothing() {
        assertEquals("Normal", currentStyle(gallery, CommandState(emptyMap(), emptySet(), styleParagraph = "Normal", styleCharacter = "Emphasis")))
        assertNull(currentStyle(gallery, CommandState(emptyMap(), emptySet(), styleParagraph = "BodyText")))
        assertNull(currentStyle(gallery, CommandState.EMPTY))
    }
}
