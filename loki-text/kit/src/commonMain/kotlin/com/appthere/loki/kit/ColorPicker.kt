package com.appthere.loki.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// Choosing a colour in an art app: a square of saturation and brightness, a strip of hues, the
// colour's hex to type or paste, a palette to start from and the colours picked lately. The same on
// a phone, a tablet and a desktop; everything is drawn in code.

/** [c] as hue (0..360), saturation and value (0..1). A grey keeps hue 0. */
fun hsvOf(c: Color): FloatArray {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return floatArrayOf(if (h < 0f) h + 360f else h, if (max == 0f) 0f else d / max, max)
}

/** The opaque colour of hue [h] (degrees), saturation [s] and value [v] (0..1). */
fun colorOfHsv(h: Float, s: Float, v: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    val sc = s.coerceIn(0f, 1f); val vc = v.coerceIn(0f, 1f)
    val c = vc * sc; val x = c * (1f - abs((hh / 60f) % 2f - 1f)); val m = vc - c
    val (r, g, b) = when {
        hh < 60f -> Triple(c, x, 0f); hh < 120f -> Triple(x, c, 0f); hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c); hh < 300f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

/** `#rrggbb` of [c] (its alpha is left out). */
fun hexOf(c: Color): String {
    fun h(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt().toString(16).padStart(2, '0')
    return "#" + h(c.red) + h(c.green) + h(c.blue)
}

/** A colour from `rrggbb` or `rgb`, with or without the `#`; null for anything else. */
fun parseHex(text: String): Color? {
    val h = text.trim().removePrefix("#")
    val full = when (h.length) { 6 -> h; 3 -> h.map { "$it$it" }.joinToString(""); else -> return null }
    if (!full.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return Color(0xFF000000 or full.toLong(16))
}

/** The starting palette: a row of greys, then twelve hues in five tones, light to dark. */
val PALETTE: List<List<Color>> = buildList {
    add(List(12) { i -> val v = 1f - i / 11f; Color(v, v, v) })
    for ((s, v) in listOf(0.3f to 1f, 0.6f to 1f, 1f to 1f, 1f to 0.7f, 1f to 0.4f)) add(List(12) { i -> colorOfHsv(i * 30f, s, v) })
}

/** The colours picked lately, newest first, for this run of the app. */
object RecentColors {
    val colors = mutableStateListOf<Color>()
    fun add(c: Color) {
        val opaque = c.copy(alpha = 1f)
        colors.removeAll { hexOf(it) == hexOf(opaque) }
        colors.add(0, opaque)
        while (colors.size > 12) colors.removeAt(colors.lastIndex)
    }
}

/**
 * A dialog to choose a colour, starting from [initial] (null: none). OK gives [onPick] the colour;
 * with [allowNone] there is also "None" (a vector fill or stroke can be none), which gives null.
 */
@Composable
fun ColorPickerDialog(title: String, initial: Color?, onPick: (Color?) -> Unit, onDismiss: () -> Unit, allowNone: Boolean = false) {
    val start = remember { hsvOf(initial ?: Color.Black) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val colour = colorOfHsv(hue, sat, value)
    var hex by remember { mutableStateOf(hexOf(colour)) }
    fun set(c: Color, keepHex: Boolean = false) {
        val hsv = hsvOf(c)
        // A grey has no hue of its own: keep the strip where it is.
        if (hsv[1] > 0f) hue = hsv[0]
        sat = hsv[1]; value = hsv[2]
        if (!keepHex) hex = hexOf(c)
    }
    fun done(c: Color?) { if (c != null) RecentColors.add(c); onPick(c) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { done(colour) }) { Text(kt("OK")) } },
        dismissButton = {
            Row {
                if (allowNone) TextButton(onClick = { onPick(null) }) { Text(kt("None")) }
                TextButton(onClick = onDismiss) { Text(kt("Cancel")) }
            }
        },
        text = {
            Column(Modifier.widthIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SaturationValueSquare(hue, sat, value, Modifier.fillMaxWidth().height(170.dp)) { s, v -> sat = s; value = v; hex = hexOf(colorOfHsv(hue, s, v)) }
                HueStrip(hue, Modifier.fillMaxWidth().height(28.dp)) { h -> hue = h; hex = hexOf(colorOfHsv(h, sat, value)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // What it was beside what it will be.
                    Row(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))) {
                        Box(Modifier.size(32.dp, 40.dp).background(initial ?: Color.Transparent).clickable(enabled = initial != null) { initial?.let { set(it) } }.semantics { contentDescription = "The colour before" })
                        Box(Modifier.size(32.dp, 40.dp).background(colour).semantics { contentDescription = "The new colour" })
                    }
                    OutlinedTextField(
                        hex, { t -> hex = t; parseHex(t)?.let { set(it, keepHex = true) } },
                        label = { Text("Hex") }, singleLine = true, isError = parseHex(hex) == null, modifier = Modifier.width(140.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (row in PALETTE) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (c in row) Box(Modifier.weight(1f).height(22.dp).background(c).clickable { set(c) }.semantics { contentDescription = hexOf(c) })
                    }
                }
                if (RecentColors.colors.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (c in RecentColors.colors) Box(Modifier.size(22.dp).background(c).border(1.dp, MaterialTheme.colorScheme.outlineVariant).clickable { set(c) }.semantics { contentDescription = hexOf(c) })
                    }
                }
            }
        },
    )
}

/** A press or drag anywhere in the box, as fractions (0..1) of its width and height. */
private fun Modifier.pick(onPick: (x: Float, y: Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        fun at(p: Offset) = onPick((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
        at(down.position); down.consume()
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            at(c.position); c.consume()
        }
    }
}

@Composable
private fun SaturationValueSquare(hue: Float, sat: Float, value: Float, modifier: Modifier, onChange: (s: Float, v: Float) -> Unit) {
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).pick { x, y -> onChange(x, 1f - y) }.semantics { contentDescription = "Saturation and brightness" }) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, colorOfHsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(Color.Black, 8.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun HueStrip(hue: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).pick { x, _ -> onChange(x * 359.9f) }.semantics { contentDescription = "Hue" }) {
        drawRect(Brush.horizontalGradient(List(13) { colorOfHsv(it * 30f, 1f, 1f) }))
        val x = hue / 360f * size.width; val w = 6.dp.toPx()
        drawRoundRect(Color.Black, Offset(x - w / 2, 0f), androidx.compose.ui.geometry.Size(w, size.height), CornerRadius(2.dp.toPx()), style = Stroke(3.dp.toPx()))
        drawRoundRect(Color.White, Offset(x - w / 2, 0f), androidx.compose.ui.geometry.Size(w, size.height), CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
    }
}

/** A colour well: a square of [color] to tap (null draws "none": white with a red slash). */
@Composable
fun ColorWell(color: Color?, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Canvas(modifier.background(color ?: Color.White).border(1.dp, MaterialTheme.colorScheme.outline).clickable(onClick = onClick).semantics { contentDescription = description }) {
        if (color == null) drawLine(Color(0xFFE5484D), Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
    }
}
