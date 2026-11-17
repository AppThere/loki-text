package app.loki.text

import app.loki.kit.*

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

data class AppMenu(val title: String, val entries: List<BarEntry>)

/** A menu key equivalent. On a Mac the engine's `Mod` is Command ([meta]); elsewhere it is Ctrl. */
data class MenuKey(val key: Key, val meta: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false)

/**
 * The first of an engine shortcut's chords (`Mod+H / Ctrl+H`) a menu can show, or null. On a Mac,
 * chords the system keeps for itself (Hide, Hide Others, Quit, Minimize, switching apps and windows,
 * Spotlight) are skipped, so the next binding shows, or none.
 */
internal fun menuKey(shortcut: String, mac: Boolean): MenuKey? {
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

/** An entry of [MENU_SPEC]: an id with an optional label of this app's, a divider, or a submenu. */
private sealed interface Spec
private class Cmd(val id: String, val label: String? = null, val check: Boolean = false, val key: String = "") : Spec
private data object Sep : Spec
private class Sub(val label: String, val entries: List<Spec>) : Spec

private fun cmd(id: String, label: String? = null, key: String = "") = Cmd(id, label, key = key)
private fun check(id: String, label: String? = null) = Cmd(id, label, check = true)

/** The Window menu's own entries, which the platform window handles. */
const val MENU_MINIMIZE = "app.window.minimize"
const val MENU_ZOOM = "app.window.zoom"

/**
 * The menu bar, laid out as Mac word processors lay theirs out. About, Settings and Quit live in
 * the application menu, which the system builds.
 */
private val MENU_SPEC: List<Pair<String, List<Spec>>> = listOf(
    "File" to listOf(
        cmd("file.new", key = "Mod+N"), cmd("file.open", "Open…", key = "Mod+O"), Sep,
        cmd("file.close", key = "Mod+W"), cmd("file.save", key = "Mod+S"), cmd("file.saveAs", "Save As…", key = "Mod+Shift+S"),
        cmd("file.saveTemplate", "Save as Template…"), Sep,
        cmd("file.exportPdf", "Export PDF…"), cmd("file.exportPng", "Export Page as PNG…"), Sep,
        cmd("layout.pageSetup", "Page Setup…"), cmd("file.print", "Print…", key = "Mod+P"), Sep,
        cmd("file.properties", "Properties…"), cmd("file.info", "Info"),
    ),
    "Edit" to listOf(
        cmd("edit.undo"), cmd("edit.redo"), Sep,
        cmd("edit.cut"), cmd("edit.copy"), cmd("edit.paste"), cmd("edit.pasteText", "Paste and Match Style"),
        cmd("edit.pasteSpecial", "Paste Special…"), cmd("select.all"), Sep,
        cmd("edit.copyFormat"), cmd("edit.pasteFormat"), Sep,
        Sub(
            "Find",
            listOf(cmd("edit.find", "Find…"), cmd("edit.findReplace", "Find and Replace…"), cmd("edit.findNext"), cmd("edit.findPrevious"), Sep, cmd("edit.goto", "Go To…")),
        ),
        Sub("Spelling and Grammar", listOf(cmd("review.spelling", "Check Document Now"), check("review.proofing"))),
    ),
    "View" to listOf(
        check("view.printLayout"), check("view.webLayout"), check("view.outline"), check("view.draft"), check("view.readMode"), Sep,
        check("view.ruler"), check("view.marks", "Formatting Marks"), check("view.navigationPane"), check("view.commentsPane"), check("view.stylesPane"), Sep,
        cmd("view.zoomIn"), cmd("view.zoomOut"), cmd("view.zoom100", "Actual Size"), Sep,
        Sub("Appearance", listOf(check("app.theme.system", "System"), check("app.theme.light", "Light"), check("app.theme.dark", "Dark"))),
        check("view.darkMode", "Dark Page"),
    ),
    "Insert" to listOf(
        cmd("insert.pageBreak"), cmd("insert.blankPage"), Sep,
        cmd("insert.table", "Table…"), cmd("insert.picture", "Picture…"), cmd("insert.textBox"), cmd("insert.equation"), Sep,
        cmd("insert.link", "Link…"), cmd("insert.bookmark", "Bookmark…"), cmd("insert.crossReference", "Cross-reference…"), Sep,
        cmd("review.newComment"), cmd("references.footnote"), cmd("references.endnote"), Sep,
        cmd("insert.header"), cmd("insert.footer"), cmd("insert.pageNumber"), Sep,
        cmd("insert.dateTime", "Date & Time…"), cmd("insert.symbol", "Symbol…"), cmd("insert.field", "Field…"),
    ),
    "Format" to listOf(
        cmd("format.fontDialog", "Font…"), cmd("para.dialog", "Paragraph…"), Sep,
        Sub(
            "Style",
            listOf(
                check("format.bold"), check("format.italic"), check("format.underline"), check("format.strikethrough"), Sep,
                check("format.superscript"), check("format.subscript"),
            ),
        ),
        Sub("Alignment", listOf(check("para.alignLeft"), check("para.alignCenter"), check("para.alignRight"), check("para.justify"))),
        check("para.bullets"), check("para.numbering"), Sep,
        cmd("styles.manage", "Styles…"), cmd("format.clear"), Sep,
        cmd("layout.columns", "Columns…"), cmd("layout.margins", "Margins…"),
    ),
    "Tools" to listOf(
        cmd("review.spelling", "Spelling and Grammar…"), cmd("review.thesaurus"), cmd("review.wordCount"), cmd("review.language", "Language…"), Sep,
        check("review.trackChanges"), cmd("review.acceptAll"), cmd("review.rejectAll"), Sep,
        cmd("tools.autocorrect", "AutoCorrect Options…"), cmd("file.protect", "Protect Document…"), cmd("file.accessibility"),
    ),
    "Table" to listOf(
        cmd("insert.table", "Insert Table…"), cmd("table.fromText", "Convert Text to Table…"), Sep,
        cmd("table.insertRowAbove", "Insert Row Above"), cmd("table.insertRowBelow", "Insert Row Below"),
        cmd("table.insertColumnLeft", "Insert Column Left"), cmd("table.insertColumnRight", "Insert Column Right"), Sep,
        cmd("table.deleteRow"), cmd("table.deleteColumn"), cmd("table.deleteTable"), Sep,
        cmd("table.merge"), cmd("table.split", "Split Cells…"), cmd("table.splitTable"), Sep,
        cmd("table.selectTable"), cmd("table.sort", "Sort…"), cmd("table.formula", "Formula…"), cmd("table.toText", "Convert to Text…"),
    ),
    "Window" to listOf(cmd(MENU_MINIMIZE, "Minimize", key = "Mod+M"), cmd(MENU_ZOOM, "Zoom")),
    "Help" to listOf(cmd("app.commands", "Search All Commands…"), cmd("app.about", "About Loki Text")),
)

/** Ids this app handles without the engine, so they are listed whatever the engine has. */
private fun isAppId(id: String) = id.startsWith("app.")

/**
 * The menu bar for [catalog] (the engine's commands by id). Entries for commands the engine doesn't
 * have are left out, and dividers and submenus left empty by that go too. A label is the engine's
 * unless the layout gives one; a command that asks for values ends in "…". Shortcuts are the
 * engine's ([menuKey]); `key` in the layout only stands in for a command the app runs itself.
 */
fun appMenus(catalog: Map<String, CommandInfo>, mac: Boolean): List<AppMenu> {
    // A key equivalent may belong to one entry only: the first one in the bar keeps it.
    val taken = mutableSetOf<MenuKey>()
    fun build(specs: List<Spec>): List<BarEntry> {
        val out = mutableListOf<BarEntry>()
        for (s in specs) when (s) {
            is Sep -> if (out.isNotEmpty() && out.last() != MenuSeparator) out.add(MenuSeparator)
            is Sub -> build(s.entries).takeIf { it.isNotEmpty() }?.let { out.add(SubMenu(s.label, it)) }
            is Cmd -> {
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
    return MENU_SPEC.mapNotNull { (title, specs) -> build(specs).takeIf { it.isNotEmpty() }?.let { AppMenu(title, it) } }
}

/**
 * What a window shares with its platform menu bar: the engine's commands and their state, for the
 * menus to show, and the way back in to run one. [App] fills it; the platform draws the bar.
 */
class MenuBridge {
    var catalog by mutableStateOf<Map<String, CommandInfo>>(emptyMap())
    var disabled by mutableStateOf<Map<String, String>>(emptyMap())
    /** Commands that are on, and the `app.*` ids that are checked (the theme). */
    var on by mutableStateOf<Set<String>>(emptySet())
    internal var runner: ((String) -> Unit)? = null

    private val clock = TimeSource.Monotonic
    private var last: Triple<String, Boolean, TimeSource.Monotonic.ValueTimeMark>? = null

    /**
     * Should a run of [id] go ahead? A key press can reach both the window and the menu's key
     * equivalent; the second of the two within a moment is the same press, so it is dropped. Runs
     * from the same side (a held key repeating) always go ahead.
     */
    internal fun allow(id: String, fromMenu: Boolean): Boolean {
        val prev = last
        last = Triple(id, fromMenu, clock.markNow())
        return !(prev != null && prev.first == id && prev.second != fromMenu && prev.third.elapsedNow().inWholeMilliseconds < 300)
    }

    /** Runs the menu entry [id] in its window. */
    fun run(id: String) {
        if (allow(id, fromMenu = true)) runner?.invoke(id)
    }
}
