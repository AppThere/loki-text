package app.loki.text

import app.loki.kit.*

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
            Key.S -> KeyCommand(if (e.isShiftPressed) "file.saveAs" else "file.save")
            Key.N -> KeyCommand("file.new")
            Key.D -> KeyCommand("format.fontDialog")
            Key.F -> KeyCommand("edit.find")
            Key.H -> KeyCommand("edit.findReplace")
            Key.G -> KeyCommand(if (e.isShiftPressed) "edit.findPrevious" else "edit.goto")
            Key.O -> KeyCommand("file.open")
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
    // Printable text is not a key command: it reaches the document through the focused ImeInput
    // field, the one path for hardware keys, soft keyboards and IME composition. Inserting it here
    // as well types every character twice (desktop delivers the key press and the typed character
    // as separate events, and consuming the press does not stop the field getting the character).
    return null
}
