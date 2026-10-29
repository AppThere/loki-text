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
import androidx.compose.ui.input.key.utf16CodePoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A WordCraft command to run for a key press: its id and params. */
class KeyCommand(val command: String, val params: JsonObject = JsonObject(emptyMap()))

/**
 * Maps a key press on the page to the command the engine runs for it, or null when the key is
 * not an editing key (the event then goes on to the rest of the UI). Shortcuts follow the engine's
 * own (`Mod+B`, `Mod+Z`, …); Ctrl or Meta both act as Mod.
 */
fun keyCommand(e: KeyEvent): KeyCommand? {
    if (e.type != KeyEventType.KeyDown) return null
    val mod = e.isCtrlPressed || e.isMetaPressed
    val extend = buildJsonObject { put("extend", e.isShiftPressed) }

    if (mod) {
        return when (e.key) {
            Key.B -> KeyCommand("format.bold")
            Key.I -> KeyCommand("format.italic")
            Key.U -> KeyCommand("format.underline")
            Key.Z -> KeyCommand(if (e.isShiftPressed) "edit.redo" else "edit.undo")
            Key.Y -> KeyCommand("edit.redo")
            Key.A -> KeyCommand("select.all")
            Key.C -> KeyCommand("edit.copy")
            Key.X -> KeyCommand("edit.cut")
            Key.V -> KeyCommand("edit.paste")
            Key.DirectionLeft -> KeyCommand("caret.wordLeft", extend)
            Key.DirectionRight -> KeyCommand("caret.wordRight", extend)
            Key.Backspace -> KeyCommand("text.deleteWordBack")
            Key.Delete -> KeyCommand("text.deleteWordForward")
            Key.MoveHome -> KeyCommand("caret.docStart", extend)
            Key.MoveEnd -> KeyCommand("caret.docEnd", extend)
            else -> null
        }
    }
    when (e.key) {
        Key.DirectionLeft -> return KeyCommand("caret.left", extend)
        Key.DirectionRight -> return KeyCommand("caret.right", extend)
        Key.DirectionUp -> return KeyCommand("caret.up", extend)
        Key.DirectionDown -> return KeyCommand("caret.down", extend)
        Key.MoveHome -> return KeyCommand("caret.home", extend)
        Key.MoveEnd -> return KeyCommand("caret.end", extend)
        Key.PageUp -> return KeyCommand("caret.pageUp", extend)
        Key.PageDown -> return KeyCommand("caret.pageDown", extend)
        Key.Backspace -> return KeyCommand("text.backspace")
        Key.Delete -> return KeyCommand("text.delete")
        Key.Enter, Key.NumPadEnter -> return KeyCommand("text.newParagraph")
        Key.Tab -> return KeyCommand(if (e.isShiftPressed) "text.backTab" else "text.tab")
    }
    // Modifier and lock keys report an "undefined" character (0xFFFF on the JVM), not text.
    if (e.key in NON_TEXT_KEYS) return null
    // Printable text. AltGr reports as Ctrl+Alt, so only a lone Alt is treated as a shortcut.
    val cp = e.utf16CodePoint
    if (e.isAltPressed && !e.isCtrlPressed || !isPrintable(cp)) return null
    return KeyCommand("text.insert", buildJsonObject { put("text", codePointToString(cp)) })
}

private val NON_TEXT_KEYS = setOf(
    Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight, Key.CapsLock, Key.NumLock, Key.ScrollLock,
    Key.Escape, Key.Insert, Key.Menu,
)

/** Is [cp] a character to insert: not a control, surrogate, or Unicode non-character? */
private fun isPrintable(cp: Int): Boolean =
    cp in 0x20..0x10FFFF && cp != 0x7F && cp !in 0x80..0x9F && cp !in 0xD800..0xDFFF &&
        cp !in 0xFDD0..0xFDEF && (cp and 0xFFFE) != 0xFFFE

/** The string for one Unicode code point (common code has no `Character.toChars`). */
private fun codePointToString(cp: Int): String =
    if (cp < 0x10000) cp.toChar().toString()
    else {
        val v = cp - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    }
