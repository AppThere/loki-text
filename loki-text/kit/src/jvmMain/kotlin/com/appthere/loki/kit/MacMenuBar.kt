package com.appthere.loki.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import java.awt.Desktop

/**
 * The window's menu bar from the app's menus: on a Mac it is the screen's menu bar while the window is
 * in front. [menus] is the app's layout (see [buildMenus]). Entries are enabled and checked as the engine reports; [onRun] gets the chosen id.
 */
@Composable
fun FrameWindowScope.LokiMenuBar(bridge: MenuBridge, mac: Boolean, menus: (MenuBridge, Boolean) -> List<BarMenu>, onRun: (String) -> Unit) {
    val catalog = bridge.catalog
    val bar = remember(catalog, bridge.ribbon, mac) { menus(bridge, mac) }
    val disabled = bridge.disabled
    val on = bridge.on
    MenuBar {
        for (m in bar) Menu(m.title) { entries(m.entries, disabled, on, onRun) }
    }
}

@Composable
private fun MenuScope.entries(list: List<BarEntry>, disabled: Map<String, String>, on: Set<String>, onRun: (String) -> Unit) {
    for (e in list) when (e) {
        MenuSeparator -> Separator()
        is SubMenu -> Menu(e.label) { entries(e.entries, disabled, on, onRun) }
        is MenuCommand -> {
            val key = e.shortcut?.let { KeyShortcut(it.key, ctrl = it.ctrl, meta = it.meta, alt = it.alt, shift = it.shift) }
            val enabled = disabled[e.id] == null
            if (e.check || e.id in on) {
                CheckboxItem(e.label, checked = e.id in on, enabled = enabled, shortcut = key, onCheckedChange = { onRun(e.id) })
            } else {
                Item(e.label, enabled = enabled, shortcut = key, onClick = { onRun(e.id) })
            }
        }
    }
}

/**
 * The application menu's About, Settings and Quit, which the Mac builds itself. Quit is turned
 * into closing each window (so unsaved changes are asked about); the app ends with the last one.
 */
fun installMacAppMenu(onAbout: () -> Unit, onSettings: () -> Unit, onQuit: () -> Unit) {
    if (!Desktop.isDesktopSupported()) return
    val d = Desktop.getDesktop()
    runCatching { if (d.isSupported(Desktop.Action.APP_ABOUT)) d.setAboutHandler { onAbout() } }
    runCatching { if (d.isSupported(Desktop.Action.APP_PREFERENCES)) d.setPreferencesHandler { onSettings() } }
    runCatching {
        if (d.isSupported(Desktop.Action.APP_QUIT_HANDLER)) d.setQuitHandler { _, response -> response.cancelQuit(); onQuit() }
    }
}

/** True on macOS. */
val IS_MAC: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

/**
 * Names the Mac's application menu and Dock entry, and puts the menus in the screen's menu bar. Call it first in `main`: AWT reads
 * these when it starts, and without them a `gradle run` shows the main class (MainKt). A packaged app has its bundle name.
 */
fun nameMacApplication(name: String) {
    if (!IS_MAC) return
    System.setProperty("apple.awt.application.name", name)
    System.setProperty("apple.laf.useScreenMenuBar", "true")
}
