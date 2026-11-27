package com.appthere.loki.text

import com.appthere.loki.kit.*

import androidx.compose.ui.input.key.KeyEvent
import com.appthere.loki.kit.Chord
import com.appthere.loki.kit.chordOf
import com.appthere.loki.kit.isShortcutChord

/** The engine command [e] is a shortcut for, if it is one and the engine has one for it. */
fun shortcutCommand(e: KeyEvent, shortcuts: Map<Chord, String>): KeyCommand? {
    val c = chordOf(e)?.takeIf(::isShortcutChord) ?: return null
    return shortcuts[c]?.let { KeyCommand(it) }
}

/** File commands that need a picker, a print dialog or a new window, so they go through the File menu's path. */
internal val FILE_MENU_COMMANDS = setOf("file.new", "file.open", "file.save", "file.saveAs", "file.print")
