package app.loki.text

import app.loki.kit.CommandInfo
import app.loki.kit.RibbonGroup
import app.loki.kit.RibbonItem
import app.loki.kit.RibbonTab
import app.loki.kit.parseChord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomizationTest {
    private val layoutJson = """{"qat":["file.save","edit.undo","file.print"],"tabs":[{"name":"Home"},
        {"custom":true,"groups":[{"commands":[],"name":"New Group"},{"commands":["format.smallCaps","no.such"],"name":"Fav"}],"name":"Mine"},
        {"groups":[{"commands":["format.bold"],"name":"Extra"}],"name":"Insert"},{"hidden":true,"name":"Mailings"},{"name":"Review"},{"name":"Help"}]}"""
    private val layout = parseRibbonLayout(Json.parseToJsonElement(layoutJson))!!
    private val catalog = listOf(
        CommandInfo("format.smallCaps", "Small Caps", "Home › Font", "{}", "Mod+Shift+K", true),
        CommandInfo("format.bold", "Bold", "Home › Font", "{}", "Mod+B", true),
        CommandInfo("edit.find", "Find", "Home › Editing", "{}", "Mod+F / F5", false),
    ).associateBy { it.id }
    private fun tab(name: String) = RibbonTab(name, listOf(RibbonGroup("$name group", listOf(RibbonItem("x.$name", name, "")))))
    private val base = listOf(tab("Home"), tab("Insert"), tab("Mailings"), tab("Review"), tab("Table Design"))

    @Test fun theRibbonFollowsTheLayout() {
        val shown = applyRibbonLayout(base, layout, catalog)
        // Order as laid out, Mailings hidden, a tab the app has no ribbon for (Help) dropped, tool tabs kept after.
        assertEquals(listOf("Home", "Mine", "Insert", "Review", "Table Design"), shown.map { it.name })
        // The tab the person made has only their group with something in it, and only commands the engine has.
        assertEquals(listOf("Fav"), shown[1].groups.map { it.name })
        assertEquals(listOf("format.smallCaps"), shown[1].groups[0].items.map { it.id })
        // A built-in tab keeps its own groups and gets theirs at the end.
        assertEquals(listOf("Insert group", "Extra"), shown[2].groups.map { it.name })
    }

    @Test fun theLayoutRoundTrips() {
        assertEquals(layout, parseRibbonLayout(layout.toJson()))
        assertEquals(listOf("file.save", "edit.undo", "file.print"), layout.qat)
        assertNull(parseRibbonLayout(Json.parseToJsonElement("[]")))
    }

    @Test fun changedKeysReplaceACommandsKeysAndTakeTheKeyFromOthers() {
        val base = mapOf(parseChord("Mod+Shift+K")!! to "format.smallCaps", parseChord("Mod+B")!! to "format.bold", parseChord("F2")!! to "edit.find")
        val keys = applyKeyOverrides(base, KeyCustom(listOf("format.smallCaps" to listOf("F2"), "format.bold" to emptyList())))
        assertEquals("format.smallCaps", keys[parseChord("F2")])
        assertNull(keys[parseChord("Mod+Shift+K")])
        assertNull(keys[parseChord("Mod+B")])
    }

    @Test fun savedKeysReplayAsRemovalsThenAssignments() {
        val custom = KeyCustom(listOf("format.smallCaps" to listOf("Cmd+Shift+K", "F2"), "edit.find" to listOf("Mod+F")))
        val ops = keyReplay(custom, builtInKeys(catalog.values))
        // Cmd and Mod are the same key, so Small Caps keeps its built-in one and gains F2; Find loses F5.
        assertEquals(
            listOf("""{"remove":{"command":"edit.find","key":"F5"}}""", """{"assign":{"command":"format.smallCaps","key":"F2"}}"""),
            ops.map(JsonObject::toString),
        )
        assertTrue(keyReplay(KeyCustom(emptyList()), builtInKeys(catalog.values)).isEmpty())
    }

    @Test fun keysRoundTripThroughTheSettingsFile() {
        val custom = KeyCustom(listOf("edit.find" to listOf("F2"), "format.bold" to emptyList()))
        assertEquals(custom, parseKeyCustom(JsonObject(mapOf("custom" to custom.toJson()))))
    }
}
