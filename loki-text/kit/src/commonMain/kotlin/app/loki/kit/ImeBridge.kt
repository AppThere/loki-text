package app.loki.kit

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** What the soft keyboard did, as an engine that edits text itself wants to hear it. */
sealed interface ImeAction {
    /** Delete [count] characters before the caret. */
    data class Backspace(val count: Int) : ImeAction
    /** Insert [text] at the caret: it is final. */
    data class Commit(val text: String) : ImeAction
    /** Show [text] at the caret as a composition still being typed ("" cancels it); [cursor] is where the keyboard's caret is in it. */
    data class Preedit(val text: String, val cursor: Int?) : ImeAction
}

/** What to do about one change of the hidden field: the [actions] to send, and the [next] value to put in it (null: leave it as it is). */
class ImeStep(val actions: List<ImeAction>, val next: TextFieldValue?)

/** The two zero-width characters the hidden field always starts with, so a Backspace has something to delete. */
const val IME_ANCHOR = "​​"

private fun anchored() = TextFieldValue(IME_ANCHOR, TextRange(IME_ANCHOR.length))

/**
 * Reads one change of the hidden field. What lies after [IME_ANCHOR] is what the keyboard added: while
 * it is composing (its composition range is set) that text is a preedit, the text before it is final;
 * once it is not composing, whatever is there is final and the field is put back. A change that took
 * characters from the anchor is that many Backspaces. [wasComposing] says a composition was showing, so
 * cancelling one is heard.
 */
fun imeStep(value: TextFieldValue, wasComposing: Boolean): ImeStep {
    val t = value.text
    val a = IME_ANCHOR.length
    if (t.length < a) return ImeStep(listOf(ImeAction.Backspace(a - t.length)), anchored())
    val comp = value.composition
    if (comp != null && comp.start >= a && comp.end <= t.length && !comp.collapsed) {
        val prefix = t.substring(a, comp.start)
        val composing = t.substring(comp.start, comp.end)
        val cursor = (value.selection.end - comp.start).coerceIn(0, composing.length)
        val actions = buildList {
            if (prefix.isNotEmpty()) add(ImeAction.Commit(prefix))
            add(ImeAction.Preedit(composing, cursor))
        }
        // The text already made final leaves the field, with the composition kept where it is.
        val next = if (prefix.isEmpty()) null else TextFieldValue(IME_ANCHOR + composing, TextRange(a + cursor), TextRange(a, a + composing.length))
        return ImeStep(actions, next)
    }
    val typed = (if (t.startsWith(IME_ANCHOR)) t.substring(a) else t.replace("​", ""))
    return when {
        typed.isNotEmpty() -> ImeStep(listOf(ImeAction.Commit(typed)), anchored())
        wasComposing -> ImeStep(listOf(ImeAction.Preedit("", null)), anchored())
        else -> ImeStep(emptyList(), if (t == IME_ANCHOR) null else anchored())
    }
}

/**
 * A text field nobody sees, which takes the soft keyboard (a touch screen has no key events for it) and
 * says what was typed through [onAction]. Give it the focus ([focus]) while text is being edited. A
 * hardware key still arrives as a key event: handle those on a parent.
 */
@Composable
fun ImeBridge(focus: FocusRequester, onAction: (ImeAction) -> Unit, modifier: Modifier = Modifier) {
    var value by remember { mutableStateOf(anchored()) }
    var composing by remember { mutableStateOf(false) }
    BasicTextField(
        value,
        { next ->
            val step = imeStep(next, composing)
            composing = step.actions.any { it is ImeAction.Preedit && it.text.isNotEmpty() }
            step.actions.forEach(onAction)
            value = step.next ?: next
        },
        modifier.size(1.dp).alpha(0f).focusRequester(focus),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
}
