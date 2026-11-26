package app.loki.text

import app.loki.kit.CommandInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyboardDialogTest {
    @Test fun readsTheKeysThePersonChanged() {
        // What the engine answers: each changed command with all its keys now.
        val c = parseKeyCustom(Json.parseToJsonElement("""{"custom":[{"command":"format.smallCaps","keys":["Cmd+Shift+K","F2"]},{"command":"format.bold","keys":[]}],"removed":true}"""))
        assertEquals(listOf("format.bold" to emptyList(), "format.smallCaps" to listOf("Cmd+Shift+K", "F2")), c.commands)
        // What its params doc describes.
        val doc = parseKeyCustom(Json.parseToJsonElement("""{"custom":{"assigned":{"F2":"format.smallCaps","Alt+F1":"format.smallCaps"},"removed":["Mod+J"]}}"""))
        assertEquals(listOf("format.smallCaps" to listOf("Alt+F1", "F2")), doc.commands)
        assertEquals(KeyCustom(emptyList()), parseKeyCustom(Json.parseToJsonElement("""{"custom":{}}""")))
        assertEquals(KeyCustom(emptyList()), parseKeyCustom(null))
    }

    @Test fun keysAreShownAsThisPlatformNamesThem() = assertEquals(keyLabel("Mod+Shift+K"), keyLabel("Cmd+Shift+K"))

    @Test fun findingACommandMatchesEveryWordAndPutsTheNameFirst() {
        val all = listOf(
            CommandInfo("format.bold", "Bold", "Home › Font", "{}", "Mod+B", true),
            CommandInfo("table.boldHeader", "Header Row Bold", "Table", "{}", "", true),
            CommandInfo("edit.find", "Find", "Home › Editing", "{}", "Mod+F", false),
        )
        assertEquals(listOf("Bold", "Header Row Bold"), findCommands(all, "bold").map { it.label })
        assertEquals(listOf("Header Row Bold"), findCommands(all, "bold header").map { it.label })
        assertTrue(findCommands(all, " ").isEmpty())
    }

    @Test fun assignAndRemoveSendTheCommandAndKey() {
        assertEquals("""{"assign":{"command":"edit.find","key":"Alt+F1"}}""", assignParams("edit.find", "Alt+F1").toString())
        assertEquals("""{"remove":{"command":"edit.find","key":"Alt+F1"}}""", removeParams("edit.find", "Alt+F1").toString())
    }
}
