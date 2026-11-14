package app.loki.text

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A key with its modifiers. [mod] is the engine's `Mod`: Ctrl, or Command on a Mac (either one
 * counts, as everywhere in this app). The engine's few `Ctrl+` shortcuts read the same way.
 */
data class Chord(val key: Key, val mod: Boolean = false, val alt: Boolean = false, val shift: Boolean = false)

/** Key names as the engine writes them in a shortcut (`Mod+Shift+F12`, `Alt+=`). */
private val KEY_NAMES: Map<String, Key> = buildMap {
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { i, k -> put(('A' + i).toString(), k) }
    val digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    digits.forEachIndexed { i, k -> put(i.toString(), k) }
    val f = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)
    f.forEachIndexed { i, k -> put("F${i + 1}", k) }
    put("Enter", Key.Enter); put("Space", Key.Spacebar); put("Tab", Key.Tab); put("Escape", Key.Escape); put("Esc", Key.Escape)
    put("Up", Key.DirectionUp); put("Down", Key.DirectionDown); put("Left", Key.DirectionLeft); put("Right", Key.DirectionRight)
    put("Home", Key.MoveHome); put("End", Key.MoveEnd); put("PageUp", Key.PageUp); put("PageDown", Key.PageDown)
    put("Delete", Key.Delete); put("Backspace", Key.Backspace); put("Insert", Key.Insert)
    put("=", Key.Equals); put("-", Key.Minus); put(".", Key.Period); put(",", Key.Comma)
    put("[", Key.LeftBracket); put("]", Key.RightBracket); put("/", Key.Slash); put(";", Key.Semicolon)
}

private val F_KEYS = (1..12).mapNotNull { KEY_NAMES["F$it"] }.toSet()

/** One chord of an engine shortcut, or null when it names a key this app doesn't know. */
internal fun parseChord(text: String): Chord? {
    val parts = text.trim().split('+').map { it.trim() }
    // An empty part (`Mod++`) names no key this app maps.
    if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
    val mods = parts.dropLast(1)
    if (mods.any { it !in setOf("Mod", "Ctrl", "Alt", "Shift") }) return null
    // `Mod+Ctrl+F` (a Mac's full screen) would read as `Mod+F`: leave it to the platform.
    if ("Mod" in mods && "Ctrl" in mods) return null
    val key = KEY_NAMES[parts.last()] ?: KEY_NAMES[parts.last().uppercase()] ?: return null
    return Chord(key, mod = "Mod" in mods || "Ctrl" in mods, alt = "Alt" in mods, shift = "Shift" in mods)
}

/**
 * Every engine command's shortcuts, from `engine.commands`: a shortcut field may list several
 * (`Mod+O / Mod+F12`). The first command to claim a chord keeps it. `ui.*` commands act on a
 * window this front end doesn't have, so they are left out.
 */
fun parseShortcuts(commands: JsonElement): Map<Chord, String> {
    val out = LinkedHashMap<Chord, String>()
    for (c in (commands as? JsonArray).orEmpty()) {
        val o = c as? JsonObject ?: continue
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
        if (id.isEmpty() || id.startsWith("ui.")) continue
        val shortcut = o["shortcut"]?.jsonPrimitive?.contentOrNull ?: continue
        for (part in shortcut.split(" / ")) parseChord(part)?.let { out.getOrPut(it) { id } }
    }
    return out
}

/** The chord of a key press, or null for anything but a press. */
fun chordOf(e: KeyEvent): Chord? {
    if (e.type != KeyEventType.KeyDown) return null
    return Chord(e.key, mod = e.isCtrlPressed || e.isMetaPressed, alt = e.isAltPressed, shift = e.isShiftPressed)
}

/**
 * Is [c] a shortcut rather than typing or moving the caret? Anything with Ctrl or Command, a
 * function key, Shift+Enter (a line break), or Alt with a non-text key. Alt with a letter, digit
 * or symbol is typing on a Mac (Option+E, Option+5), so it stays text.
 */
internal fun isShortcutChord(c: Chord): Boolean = when {
    c.mod -> true
    c.key in F_KEYS -> true
    c.shift && c.key == Key.Enter -> true
    c.alt -> c.key in ALT_KEYS
    else -> false
}

/** Keys that are never text, so Alt with them is a shortcut even on a Mac. */
private val ALT_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.MoveHome, Key.MoveEnd,
    Key.PageUp, Key.PageDown, Key.Tab, Key.Enter, Key.Backspace, Key.Delete, Key.Escape,
)

/** The engine command [e] is a shortcut for, if it is one and the engine has one for it. */
fun shortcutCommand(e: KeyEvent, shortcuts: Map<Chord, String>): KeyCommand? {
    val c = chordOf(e)?.takeIf(::isShortcutChord) ?: return null
    return shortcuts[c]?.let { KeyCommand(it) }
}

/** File commands that need a picker, a print dialog or a new window, so they go through the File menu's path. */
internal val FILE_MENU_COMMANDS = setOf("file.new", "file.open", "file.save", "file.saveAs", "file.print")
