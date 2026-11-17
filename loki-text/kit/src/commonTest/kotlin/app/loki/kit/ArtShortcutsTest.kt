package app.loki.kit

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtShortcutsTest {
    /** The art engines write the platform modifier as `Cmd`; a tool's shortcut is a bare letter. */
    @Test
    fun cmdIsTheModifierAndALetterIsAChord() {
        assertEquals(Chord(Key.Z, mod = true), parseChord("Cmd+Z"))
        assertEquals(Chord(Key.Z, mod = true, shift = true), parseChord("Cmd+Shift+Z"))
        assertEquals(Chord(Key.RightBracket, mod = true), parseChord("Cmd+]"))
        assertEquals(Chord(Key.B), parseChord("B"))
        assertEquals(Chord(Key.Delete), parseChord("Delete"))
        assertNull(parseChord("Cmd+Ctrl+F"))
        assertNull(parseChord(""))
    }
}
