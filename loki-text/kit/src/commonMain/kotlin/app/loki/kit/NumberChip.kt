package app.loki.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

// The options bar's controls: a number to set with a slider, a step either way, or by typing it
// (a brush's size, a stroke's weight, an opacity), and a choice from a short list. Each is a chip
// that says its value and opens under itself, so a row of them fits a phone.

/** [v] with at most [decimals] places and no trailing zeros. */
fun formatNumber(v: Double, decimals: Int = 0): String {
    if (!v.isFinite()) return "?"
    val k = 10.0.pow(decimals)
    val r = (v * k).roundToLong() / k
    return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString()
}

/** Where [v] sits in [min]..[max] as 0..1, on a straight or (for [log], with min > 0) a logarithmic scale. */
fun sliderPosition(v: Double, min: Double, max: Double, log: Boolean): Float {
    if (max <= min) return 0f
    val c = v.coerceIn(min, max)
    return (if (log && min > 0) ln(c / min) / ln(max / min) else (c - min) / (max - min)).toFloat()
}

/** The value at slider position [t] (0..1): the inverse of [sliderPosition]. */
fun sliderValue(t: Float, min: Double, max: Double, log: Boolean): Double {
    val c = t.coerceIn(0f, 1f).toDouble()
    return if (log && min > 0) min * exp(c * ln(max / min)) else min + c * (max - min)
}

@Composable
private fun ChipBox(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Row(
        Modifier.heightIn(min = if (isTouchPlatform) 36.dp else 28.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * A number on the options bar: "[label] [value][unit]", which opens a slider, a step down and up
 * and a field to type the value. [onChange] gets a value in [min]..[max] when the slider is let go,
 * a step is tapped or a typed value is entered: never once per pixel of a drag.
 */
@Composable
fun NumberChip(
    label: String, value: Double?, min: Double, max: Double, onChange: (Double) -> Unit,
    unit: String = "", step: Double = 1.0, decimals: Int = 0, log: Boolean = false, enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChipBox({ open = true }, enabled) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" " + (value?.let { formatNumber(it, decimals) + unit } ?: "—"), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val shown = value ?: min
            // While the slider is held, the number follows the thumb; the engine hears only the end.
            var held by remember { mutableStateOf<Double?>(null) }
            var typed by remember(shown) { mutableStateOf(formatNumber(shown, decimals)) }
            val now = held ?: shown
            fun commit(v: Double) { held = null; onChange(v.coerceIn(min, max)) }
            Column(Modifier.width(260.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(formatNumber(now, decimals) + unit, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    sliderPosition(now, min, max, log), { t -> held = sliderValue(t, min, max, log).let { v -> if (decimals == 0) kotlin.math.round(v) else v } },
                    onValueChangeFinished = { held?.let(::commit) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { commit(shown - step) }, modifier = Modifier.size(40.dp)) { Icon(Lucide.Minus, contentDescription = "Decrease $label", modifier = Modifier.size(18.dp)) }
                    OutlinedTextField(
                        typed, { typed = it }, singleLine = true, modifier = Modifier.weight(1f),
                        suffix = if (unit.isNotBlank()) ({ Text(unit.trim()) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { typed.replace(',', '.').toDoubleOrNull()?.let(::commit) }),
                        isError = typed.replace(',', '.').toDoubleOrNull() == null,
                    )
                    IconButton(onClick = { commit(shown + step) }, modifier = Modifier.size(40.dp)) { Icon(Lucide.Plus, contentDescription = "Increase $label", modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

/** A choice on the options bar: "[label] [value]", which opens the list of [choices] (id to name). */
@Composable
fun ChoiceChip(label: String, value: String?, choices: List<Pair<String, String>>, onChange: (String) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChipBox({ open = true }, enabled) {
            // With no value to show (a list to pick from, like brush presets) the label alone is the chip.
            val shown = choices.firstOrNull { it.first == value }?.second ?: value
            if (label.isNotEmpty()) Text(if (shown == null) label else "$label ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (shown != null) Text(shown, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Icon(Lucide.ChevronDown, contentDescription = null, modifier = Modifier.padding(start = 2.dp).size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((id, name) in choices) DropdownMenuItem(text = { Text((if (id == value) "✓ " else "") + name) }, onClick = { open = false; onChange(id) })
        }
    }
}

/** A switch on the options bar: [label], filled while [on]. */
@Composable
fun ToggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.heightIn(min = if (isTouchPlatform) 36.dp else 28.dp)
            .then(if (on) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)))
            .clickable { onChange(!on) }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text((if (on) "✓ " else "") + label, style = MaterialTheme.typography.labelMedium, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
