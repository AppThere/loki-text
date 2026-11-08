package app.loki.text

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Compose has no stable way to build a key event outside its own modules; tests only.
@OptIn(InternalComposeUiApi::class)
class KeysTest {
    private fun press(key: Key, char: Char = '￿', meta: Boolean = false) =
        KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = char.code, isMetaPressed = meta)

    // Typed text goes through ImeInput only; a key command for it as well doubled every character.
    @Test fun printableKeyIsNotAKeyCommand() {
        assertNull(keyCommand(press(Key.A, 'a')))
        assertNull(keyCommand(press(Key.Spacebar, ' ')))
    }

    @Test fun editingKeysStillMapToCommands() {
        assertEquals("text.backspace", keyCommand(press(Key.Backspace, '\b'))?.command)
        assertEquals("caret.left", keyCommand(press(Key.DirectionLeft))?.command)
        assertEquals("format.bold", keyCommand(press(Key.B, 'b', meta = true))?.command)
    }
}
