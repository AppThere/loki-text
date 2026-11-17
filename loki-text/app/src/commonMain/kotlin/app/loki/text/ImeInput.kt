package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The text the hidden field starts from, and returns to once the IME stops composing. A soft
 * keyboard can only delete what the field holds, so Backspace needs characters behind the caret:
 * zero-width spaces, which no keyboard offers as suggestions or capitalises after.
 */
internal const val IME_FILLER = "​​​​​​​​"

/**
 * What the IME did to the hidden field, as engine commands. The field's caret is always at the end,
 * so [prev] → [next] is described by what was removed before the caret (backspaces, one per code
 * point) and what was typed in its place (text, with each line break as a new paragraph).
 */
internal fun imeCommands(prev: String, next: String): List<KeyCommand> {
    var p = 0
    val max = minOf(prev.length, next.length)
    while (p < max && prev[p] == next[p]) p++
    var s = 0
    while (s < max - p && prev[prev.length - 1 - s] == next[next.length - 1 - s]) s++
    // Never split a surrogate pair: back up so the edit covers both halves.
    if (p > 0 && p < prev.length && prev[p].isLowSurrogate() && prev[p - 1].isHighSurrogate()) p--
    val removed = prev.substring(p, prev.length - s)
    val added = next.substring(p, next.length - s)
    val out = ArrayList<KeyCommand>()
    repeat(codePoints(removed)) { out += KeyCommand("text.backspace") }
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) out += KeyCommand("text.insert", buildJsonObject { put("text", buf.toString()) })
        buf.setLength(0)
    }
    for (c in added) when (c) {
        '\n', '\r' -> { flush(); out += KeyCommand("text.newParagraph") }
        '​' -> {}
        else -> buf.append(c)
    }
    flush()
    return out
}

private fun codePoints(s: String): Int = s.count { !it.isLowSurrogate() }

private fun filler() = TextFieldValue(IME_FILLER, TextRange(IME_FILLER.length))

/** How long typing must pause before the field is cleared back to filler. */
internal const val IME_IDLE_MS = 600L

/**
 * An invisible text field that owns focus while the page is being edited. Touch devices only show
 * the on-screen keyboard (and run autocorrect, predictions and CJK composition) for a real text
 * input, and the page is a bitmap; this field is that input. Everything typed into it is forwarded
 * to [onCommand] as engine commands, live, so composing text appears in the document as it is typed.
 */
@Composable
fun ImeInput(modifier: Modifier = Modifier, onCommand: (KeyCommand) -> Unit) {
    var value by remember { mutableStateOf(filler()) }
    // When typing pauses, go back to filler so the field doesn't grow with everything typed. Never mid-composition.
    LaunchedEffect(value.text, value.composition) {
        if (value.composition == null && value.text != IME_FILLER) {
            delay(IME_IDLE_MS)
            value = filler()
        }
    }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            val cmds = imeCommands(value.text, next.text)
            cmds.forEach(onCommand)
            // Keep exactly what the IME made of the field, and not a reset copy: a keyboard that autocorrects
            // sends several edits in one go (delete the word, commit the fix, commit the space), each built on
            // the one before. The field's own text only catches up with a reset a frame later, so comparing
            // those edits with a reset field read the first edit's deletion twice, deleting text before the word.
            value = next.copy(selection = TextRange(next.text.length))
        },
        modifier = modifier.size(1.dp).alpha(0f),
        cursorBrush = SolidColor(Color.Transparent),
        // No keyboard auto-capitalisation: the field only ever holds filler, so the keyboard would think
        // every keystroke starts a sentence (stuck on capitals). The engine's AutoCorrect capitalises sentences.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Text, autoCorrectEnabled = true),
    )
}
