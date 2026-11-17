package app.loki.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import java.awt.Desktop

/**
 * The window's menu bar from [appMenus]: on a Mac it is the screen's menu bar while the window is
 * in front. Entries are enabled and checked as the engine reports; [onRun] gets the chosen id.
 */
@Composable
fun FrameWindowScope.LokiMenuBar(bridge: MenuBridge, mac: Boolean, onRun: (String) -> Unit) {
    val catalog = bridge.catalog
    val menus = remember(catalog, mac) { appMenus(catalog, mac) }
    val disabled = bridge.disabled
    val on = bridge.on
    MenuBar {
        for (m in menus) Menu(m.title) { entries(m.entries, disabled, on, onRun) }
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
            if (e.check) {
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
