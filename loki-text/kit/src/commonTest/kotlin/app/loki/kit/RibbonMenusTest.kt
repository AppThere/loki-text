package app.loki.kit

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RibbonMenusTest {
    private val tabs = listOf(
        RibbonTab("File", listOf(
            RibbonGroup("File", listOf(RibbonItem("file.new", "New", "Mod+N"), RibbonItem("file.open", "Open", "Mod+O", needsInput = true))),
            RibbonGroup("Export", listOf(RibbonItem("file.export", "Export", ""))),
        )),
        RibbonTab("Edit", listOf(
            RibbonGroup("Edit", listOf(RibbonItem("edit.undo", "Undo", "Mod+Z"), RibbonItem("edit.hide", "Hide", "Mod+H"), RibbonItem("edit.again", "Again", "Mod+N"))),
            RibbonGroup("Empty", emptyList()),
        )),
    )
    private val menus = ribbonMenus(tabs, mac = true)
    private fun commands(m: BarMenu) = m.entries.filterIsInstance<MenuCommand>()

    @Test fun eachTabIsAMenuAndTheSystemOnesFollow() {
        assertEquals(listOf("File", "Edit", "Window", "Help"), menus.map { it.title })
    }

    @Test fun groupsAreDividedAndAnEmptyGroupLeavesNoDivider() {
        assertEquals(listOf("New", "Open…"), commands(menus[0]).take(2).map { it.label })
        assertEquals(1, menus[0].entries.count { it == MenuSeparator })
        assertEquals(0, menus[1].entries.count { it == MenuSeparator })
    }

    @Test fun keysAreTheEnginesAndOnlyTheFirstEntryKeepsOne() {
        assertEquals(MenuKey(Key.N, meta = true), commands(menus[0])[0].shortcut)
        assertNull(commands(menus[1]).first { it.id == "edit.again" }.shortcut)
        // Command-H hides the app on a Mac.
        assertNull(commands(menus[1]).first { it.id == "edit.hide" }.shortcut)
        assertEquals(MenuKey(Key.H, ctrl = true), ribbonMenus(tabs, mac = false)[1].entries.filterIsInstance<MenuCommand>().first { it.id == "edit.hide" }.shortcut)
    }

    @Test fun helpSearchesEveryCommand() {
        assertTrue(menus.last().entries.any { (it as? MenuCommand)?.id == MENU_COMMANDS })
        assertFalse(menus.any { it.entries.isEmpty() })
    }
}
