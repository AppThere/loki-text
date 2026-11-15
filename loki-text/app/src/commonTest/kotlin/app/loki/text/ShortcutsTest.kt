package app.loki.text

import app.loki.kit.*

import androidx.compose.ui.input.key.Key
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutsTest {
    @Test fun chordsReadAsTheEngineWritesThem() {
        assertEquals(Chord(Key.O, mod = true), parseChord("Mod+O"))
        assertEquals(Chord(Key.F12, mod = true, shift = true), parseChord("Mod+Shift+F12"))
        assertEquals(Chord(Key.Equals, alt = true), parseChord("Alt+="))
        assertEquals(Chord(Key.Spacebar, mod = true), parseChord("Ctrl+Space"))
        assertEquals(Chord(Key.F7), parseChord("F7"))
        assertEquals(Chord(Key.Enter, shift = true), parseChord("Shift+Enter"))
        // Unknown keys and modifiers, and a Mac's own Mod+Ctrl chords, are left alone.
        assertNull(parseChord("Mod+Ctrl+F"))
        assertNull(parseChord("Hyper+X"))
        assertNull(parseChord("Mod+Pause"))
        assertNull(parseChord("Mod++"))
    }

    @Test fun everyAlternativeOfAShortcutCounts() {
        val map = parseShortcuts(Json.parseToJsonElement(
            """[{"id":"file.open","shortcut":"Mod+O / Mod+F12 / Mod+Alt+F2"},
               {"id":"edit.undo","shortcut":"Mod+Z"},
               {"id":"other.undo","shortcut":"Mod+Z"},
               {"id":"ui.zoom","shortcut":"Mod+="},
               {"id":"text.insert","shortcut":""}]""",
        ))
        assertEquals("file.open", map[Chord(Key.O, mod = true)])
        assertEquals("file.open", map[Chord(Key.F12, mod = true)])
        assertEquals("file.open", map[Chord(Key.F2, mod = true, alt = true)])
        // The first command to claim a chord keeps it; ui.* commands have no window here.
        assertEquals("edit.undo", map[Chord(Key.Z, mod = true)])
        assertNull(map[Chord(Key.Equals, mod = true)])
        assertEquals(4, map.size)
    }

    @Test fun typingIsNotAShortcut() {
        assertTrue(isShortcutChord(Chord(Key.O, mod = true)))
        assertTrue(isShortcutChord(Chord(Key.F7)))
        assertTrue(isShortcutChord(Chord(Key.Enter, shift = true)))
        assertTrue(isShortcutChord(Chord(Key.DirectionLeft, alt = true)))
        assertFalse(isShortcutChord(Chord(Key.A)))
        assertFalse(isShortcutChord(Chord(Key.A, shift = true)))
        assertFalse(isShortcutChord(Chord(Key.Enter)))
        assertFalse(isShortcutChord(Chord(Key.DirectionLeft, shift = true)))
        // Option+E types an accent on a Mac.
        assertFalse(isShortcutChord(Chord(Key.E, alt = true)))
    }

    @Test fun fileCommandsThatNeedAWindowGoThroughTheMenu() {
        assertTrue("file.open" in FILE_MENU_COMMANDS)
        assertFalse("file.close" in FILE_MENU_COMMANDS)
    }
}
