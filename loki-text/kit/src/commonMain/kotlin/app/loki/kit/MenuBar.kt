package app.loki.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import kotlin.time.TimeSource

/**
 * The menus of a platform menu bar (the Mac's, and later iPadOS's), built from the engine's own
 * commands: labels, shortcuts and whether an entry is on or available all come from the engine,
 * so the menus follow it. Only the arrangement is this app's.
 */
sealed interface BarEntry

/** A command: an engine id, or an `app.*` id this app handles itself. [check] shows its on state. */
data class MenuCommand(val id: String, val label: String, val shortcut: MenuKey? = null, val check: Boolean = false) : BarEntry

data object MenuSeparator : BarEntry

data class SubMenu(val label: String, val entries: List<BarEntry>) : BarEntry

data class BarMenu(val title: String, val entries: List<BarEntry>)

/** A menu key equivalent. On a Mac the engine's `Mod` is Command ([meta]); elsewhere it is Ctrl. */
data class MenuKey(val key: Key, val meta: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false)

/**
 * The first of an engine shortcut's chords (`Mod+H / Ctrl+H`) a menu can show, or null. On a Mac,
 * chords the system keeps for itself (Hide, Hide Others, Quit, Minimize, switching apps and windows,
 * Spotlight) are skipped, so the next binding shows, or none.
 */
fun menuKey(shortcut: String, mac: Boolean): MenuKey? {
    for (part in shortcut.split(" / ")) {
        val names = part.trim().split('+').map { it.trim() }
        if (names.size < 2 && names.firstOrNull()?.startsWith("F") != true) continue
        if (names.any { it.isEmpty() }) continue
        val mods = names.dropLast(1)
        if (mods.any { it !in setOf("Mod", "Cmd", "Ctrl", "Alt", "Shift") }) continue
        val key = keyNamed(names.last()) ?: continue
        val k = MenuKey(
            key,
            meta = mac && ("Mod" in mods || "Cmd" in mods),
            ctrl = "Ctrl" in mods || (!mac && ("Mod" in mods || "Cmd" in mods)),
            alt = "Alt" in mods,
            shift = "Shift" in mods,
        )
        if (mac && k.isMacReserved()) continue
        return k
    }
    return null
}

private fun MenuKey.isMacReserved(): Boolean =
    meta && !ctrl && !shift && (key == Key.H || key == Key.Q || key == Key.M || key == Key.Tab || key == Key.Spacebar || key == Key.Grave)

/** An entry of an app's menu layout: a command (an id, with an optional label of the app's), a divider, or a submenu. */
sealed interface MenuSpec
class MenuCmd(val id: String, val label: String? = null, val check: Boolean = false, val key: String = "") : MenuSpec
data object MenuSep : MenuSpec
class MenuSub(val label: String, val entries: List<MenuSpec>) : MenuSpec

fun menuCmd(id: String, label: String? = null, key: String = "") = MenuCmd(id, label, key = key)
fun menuCheck(id: String, label: String? = null) = MenuCmd(id, label, check = true)

/** Ids this app handles without the engine, so they are listed whatever the engine has. */
private fun isAppId(id: String) = id.startsWith("app.")

/**
 * The menu bar for [catalog] (the engine's commands by id). Entries for commands the engine doesn't
 * have are left out, and dividers and submenus left empty by that go too. A label is the engine's
 * unless the layout gives one; a command that asks for values ends in "…". Shortcuts are the
 * engine's ([menuKey]); `key` in the layout only stands in for a command the app runs itself.
 */
fun buildMenus(layout: List<Pair<String, List<MenuSpec>>>, catalog: Map<String, CommandInfo>, mac: Boolean): List<BarMenu> {
    // A key equivalent may belong to one entry only: the first one in the bar keeps it.
    val taken = mutableSetOf<MenuKey>()
    fun build(specs: List<MenuSpec>): List<BarEntry> {
        val out = mutableListOf<BarEntry>()
        for (s in specs) when (s) {
            MenuSep -> if (out.isNotEmpty() && out.last() != MenuSeparator) out.add(MenuSeparator)
            is MenuSub -> build(s.entries).takeIf { it.isNotEmpty() }?.let { out.add(SubMenu(s.label, it)) }
            is MenuCmd -> {
                val c = catalog[s.id]
                if (c == null && !isAppId(s.id)) continue
                val base = s.label ?: c?.label ?: continue
                val label = if (c != null && c.needsInput && s.label == null && !base.endsWith("…")) "$base…" else base
                val key = menuKey(c?.shortcut?.takeIf { it.isNotBlank() } ?: s.key, mac)?.takeIf { taken.add(it) }
                out.add(MenuCommand(s.id, label, key, s.check))
            }
        }
        if (out.lastOrNull() == MenuSeparator) out.removeAt(out.lastIndex)
        return out
    }
    return layout.mapNotNull { (title, specs) -> build(specs).takeIf { it.isNotEmpty() }?.let { BarMenu(title, it) } }
}

/**
 * What a window shares with its platform menu bar: the engine's commands and their state, for the
 * menus to show, and the way back in to run one. [App] fills it; the platform draws the bar.
 */
class MenuBridge {
    var catalog by mutableStateOf<Map<String, CommandInfo>>(emptyMap())
    /** The engine's ribbon, for an app whose menus follow it (see [ribbonMenus]). */
    var ribbon by mutableStateOf<List<RibbonTab>>(emptyList())
    var disabled by mutableStateOf<Map<String, String>>(emptyMap())
    /** Commands that are on, and the `app.*` ids that are checked (the theme). */
    var on by mutableStateOf<Set<String>>(emptySet())
    var runner: ((String) -> Unit)? = null

    private val clock = TimeSource.Monotonic
    private var last: Triple<String, Boolean, TimeSource.Monotonic.ValueTimeMark>? = null

    /**
     * Should a run of [id] go ahead? A key press can reach both the window and the menu's key
     * equivalent; the second of the two within a moment is the same press, so it is dropped. Runs
     * from the same side (a held key repeating) always go ahead.
     */
    fun allow(id: String, fromMenu: Boolean): Boolean {
        val prev = last
        last = Triple(id, fromMenu, clock.markNow())
        return !(prev != null && prev.first == id && prev.second != fromMenu && prev.third.elapsedNow().inWholeMilliseconds < 300)
    }

    /** Runs the menu entry [id] in its window. */
    fun run(id: String) {
        if (allow(id, fromMenu = true)) runner?.invoke(id)
    }
}

/**
 * Menus for an app that has no layout of its own: one menu per ribbon tab, its groups divided by a line, then the system's Window
 * menu and a Help menu that searches every command. Labels and key equivalents are the engine's; a key goes to the first entry
 * that names it, and on a Mac the chords the system keeps are skipped (see [menuKey]).
 */
fun ribbonMenus(tabs: List<RibbonTab>, mac: Boolean): List<BarMenu> {
    val taken = mutableSetOf<MenuKey>()
    val menus = tabs.mapNotNull { tab ->
        val entries = mutableListOf<BarEntry>()
        for (g in tab.groups) {
            if (g.items.isEmpty()) continue
            if (entries.isNotEmpty()) entries.add(MenuSeparator)
            for (item in g.items) entries.add(MenuCommand(item.id, item.menuLabel, menuKey(item.shortcut, mac)?.takeIf { taken.add(it) }))
        }
        entries.takeIf { it.isNotEmpty() }?.let { BarMenu(tab.name, it) }
    }
    return menus + BarMenu("Window", listOf(MenuCommand(MENU_MINIMIZE, "Minimize", MenuKey(Key.M, meta = mac, ctrl = !mac)), MenuCommand(MENU_ZOOM, "Zoom"))) +
        BarMenu("Help", listOf(MenuCommand(MENU_COMMANDS, "Search All Commands…")))
}

/** The Window menu's own entries, which the platform window handles, and the Help entry that opens the command search. */
const val MENU_MINIMIZE = "app.window.minimize"
const val MENU_ZOOM = "app.window.zoom"
const val MENU_COMMANDS = "app.commands"
