package com.appthere.loki.text

import com.appthere.loki.kit.*

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppMenusTest {
    private fun info(id: String, label: String, shortcut: String = "", params: String = "{}") =
        CommandInfo(id, label, "Home › Editing", params, shortcut, mutates = true)

    private val catalog = listOf(
        info("file.new", "New", "Mod+N"),
        info("file.save", "Save", "Mod+S"),
        info("edit.undo", "Undo", "Mod+Z"),
        info("edit.findReplace", "Find and Replace", "Mod+H / Ctrl+H"),
        info("view.ruler", "Ruler"),
        info("insert.bookmark", "Bookmark", params = """{"name": string}"""),
        info("table.merge", "Merge Cells"),
    ).associateBy { it.id }

    private fun all(entries: List<BarEntry>): List<MenuCommand> =
        entries.flatMap { e -> when (e) { is MenuCommand -> listOf(e); is SubMenu -> all(e.entries); MenuSeparator -> emptyList() } }

    private val mac = appMenus(catalog, mac = true)
    private fun find(id: String) = all(mac.flatMap { it.entries }).first { it.id == id }

    @Test fun modIsCommandOnAMacAndCtrlElsewhere() {
        assertEquals(MenuKey(Key.S, meta = true), menuKey("Mod+S", mac = true))
        assertEquals(MenuKey(Key.S, ctrl = true), menuKey("Mod+S", mac = false))
        assertEquals(MenuKey(Key.F7), menuKey("F7", mac = true))
        assertNull(menuKey("", mac = true))
    }

    // Command-H hides the app on a Mac: the next binding shows instead.
    @Test fun keysTheMacKeepsAreSkipped() {
        assertEquals(MenuKey(Key.H, ctrl = true), menuKey("Mod+H / Ctrl+H", mac = true))
        assertNull(menuKey("Mod+Q", mac = true))
        assertEquals(MenuKey(Key.H, ctrl = true), find("edit.findReplace").shortcut)
    }

    @Test fun commandsTheEngineLacksAreLeftOut() {
        val ids = all(mac.flatMap { it.entries }).map { it.id }.toSet()
        assertTrue("file.new" in ids && "edit.undo" in ids && "table.merge" in ids)
        assertFalse("file.exportPdf" in ids)
        // The app's own entries stay.
        assertTrue("app.commands" in ids && MENU_MINIMIZE in ids)
    }

    @Test fun noMenuStartsEndsOrDoublesADivider() {
        fun check(entries: List<BarEntry>) {
            assertTrue(entries.firstOrNull() != MenuSeparator && entries.lastOrNull() != MenuSeparator)
            entries.zipWithNext().forEach { (a, b) -> assertFalse(a == MenuSeparator && b == MenuSeparator) }
            entries.filterIsInstance<SubMenu>().forEach { check(it.entries) }
        }
        mac.forEach { check(it.entries) }
    }

    @Test fun labelsAndShortcutsComeFromTheEngine() {
        assertEquals(MenuKey(Key.Z, meta = true), find("edit.undo").shortcut)
        assertEquals("Bookmark…", find("insert.bookmark").label)
        assertTrue(find("view.ruler").check)
    }

    // A key press can reach both the window and the menu's key equivalent: only the first runs.
    @Test fun aPressSeenByBothSidesRunsOnce() {
        val b = MenuBridge()
        val ran = mutableListOf<String>()
        b.runner = { ran += it }
        // A held key repeats from the same side: every repeat runs.
        assertTrue(b.allow("edit.undo", fromMenu = false))
        assertTrue(b.allow("edit.undo", fromMenu = false))
        // The menu's copy of that press is dropped; another command from the menu runs.
        b.run("edit.undo")
        b.run("edit.redo")
        assertEquals(listOf("edit.redo"), ran)
        // And the other way round: the window's copy of a menu key equivalent is dropped.
        assertFalse(b.allow("edit.redo", fromMenu = false))
    }
}
