package app.loki.text

import app.loki.text.engine.Engine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading the styles for the gallery and the Manage Styles list must not leave the engine's "open Manage
 * Styles" request behind: the next edit collects pending requests, so the dialog came up after a keystroke.
 */
class StyleListRequestTest {
    @Test
    fun readingTheStyleListLeavesNoRequestToOpenTheDialog() {
        Engine().use { engine ->
            engine.uiRequests() // whatever a new session starts with
            engine.styleList("recommended")
            engine.styleList("alphabetical")
            assertEquals(emptyList(), engine.uiRequests())
            engine.insertText("c")
            assertEquals(emptyList(), engine.uiRequests())
        }
    }

    @Test
    fun theRawCommandDoesQueueTheRequest() {
        // Why styleList exists: if this stops being true the helper can go.
        Engine().use { engine ->
            engine.uiRequests()
            engine.execute("styles.manage")
            assertTrue(engine.uiRequests().any { it.toString().contains("manageStyles") })
        }
    }
}
