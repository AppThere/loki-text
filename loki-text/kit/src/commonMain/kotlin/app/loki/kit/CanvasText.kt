package app.loki.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlin.math.roundToInt

/** Text being typed on the canvas: where (document units), what it says so far, and what it is for (the app's to read back). */
class CanvasTextEdit(val x: Double, val y: Double, val initial: String, val target: String = "")

/**
 * A text field floating at [at] (screen pixels) over the canvas, for typing text into the document:
 * it takes the keyboard (the on-screen one on a touch screen) at once; Enter or the tick commits,
 * Shift+Enter starts a new line, Escape or the cross gives up. It stays inside the canvas.
 */
@Composable
fun CanvasTextEditor(edit: CanvasTextEdit, at: Offset, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember(edit) { mutableStateOf(TextFieldValue(edit.initial, TextRange(0, edit.initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(edit) { focus.requestFocus() }
    BoxWithConstraints {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }; val h = with(density) { maxHeight.toPx() }
        val boxW = with(density) { 300.dp.toPx() }; val boxH = with(density) { 120.dp.toPx() }
        val x = at.x.coerceIn(0f, (w - boxW).coerceAtLeast(0f)); val y = (at.y - boxH / 2).coerceIn(0f, (h - boxH).coerceAtLeast(0f))
        Surface(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.widthIn(max = 300.dp), shape = RoundedCornerShape(8.dp), tonalElevation = 4.dp, shadowElevation = 6.dp) {
            Column(Modifier.padding(8.dp)) {
                Text("Type the text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    OutlinedTextField(
                        value, { value = it }, modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                            when {
                                e.type != KeyEventType.KeyDown -> false
                                e.key == Key.Escape -> { onCancel(); true }
                                (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed -> { onDone(value.text); true }
                                else -> false
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        minLines = 1, maxLines = 4,
                    )
                    Column {
                        IconButton(onClick = { onDone(value.text) }, modifier = Modifier.size(40.dp)) { Icon(Lucide.Check, contentDescription = "Place the text", modifier = Modifier.size(18.dp)) }
                        IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) { Icon(Lucide.X, contentDescription = "Cancel", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}
