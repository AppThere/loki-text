package app.loki.text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import kotlinx.serialization.json.JsonObject
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CONTROL_HEIGHT = 28.dp
private val CONTROL_SHAPE = RoundedCornerShape(4.dp)

/** How a [RibbonButton] is laid out. */
enum class ButtonSize {
    /** An icon over a one- or two-line caption; the ribbon's large button. */
    Big,
    /** An icon beside its caption. */
    Labeled,
    /** An icon alone; the caption is in the tooltip. */
    Icon,
}

/**
 * A ribbon button: the command's icon, large with a small caption under it ([ButtonSize.Big]),
 * small with the caption beside it ([ButtonSize.Labeled]) or alone ([ButtonSize.Icon]). The tooltip
 * names the command and its shortcut, and says why the button is off when it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RibbonButton(
    iconId: String,
    label: String,
    size: ButtonSize,
    shortcut: String = "",
    disabledReason: String? = null,
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    val tip = if (shortcut.isEmpty()) label else "$label  (${displayShortcut(shortcut)})"
    val bg = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(if (disabledReason == null) tip else "$tip — $disabledReason") } },
        state = rememberTooltipState(),
    ) {
        // Material pads a clickable Surface out to a 48 dp touch target; the ribbon's own sizes apply here.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(onClick = onClick, enabled = disabledReason == null, shape = CONTROL_SHAPE, color = bg, modifier = Modifier.alpha(if (disabledReason == null) 1f else 0.45f)) {
            when (size) {
                ButtonSize.Big -> Column(
                    Modifier.widthIn(min = 52.dp, max = 72.dp).padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RibbonIcon(iconId, 28.dp)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ButtonSize.Labeled -> Row(Modifier.height(CONTROL_HEIGHT).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RibbonIcon(iconId, 16.dp)
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp))
                }
                ButtonSize.Icon -> Box(Modifier.size(CONTROL_HEIGHT + 4.dp), contentAlignment = Alignment.Center) { RibbonIcon(iconId) }
            }
        }
        }
    }
}

/** The thin outlined frame behind the font and size boxes. */
@Composable
private fun FieldFrame(width: Dp, content: @Composable () -> Unit) {
    Surface(
        shape = CONTROL_SHAPE,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        modifier = Modifier.width(width).height(CONTROL_HEIGHT),
        content = content,
    )
}

/**
 * A box showing [text] with a chevron that opens a scrolling list of [options]; picking one calls
 * [onPick]. The option equal to [text] is drawn bold. Used for the font name.
 */
@Composable
fun DropdownField(text: String, width: Dp, options: List<String>, placeholder: String, previews: Map<String, FontFamily> = emptyMap(), onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FieldFrame(width) {
            Row(Modifier.clickable { open = true }.padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text.ifEmpty { placeholder },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (text.isEmpty()) MaterialTheme.colorScheme.outline else Color.Unspecified,
                    fontFamily = previews[text],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RibbonIcon("ui.dropdown", 16.dp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // A list of hundreds of families: lazy, and a fixed height so the menu doesn't grow past the window.
            // A fixed size: a menu measures its content's intrinsic width, which a lazy list can't answer.
            LazyColumn(Modifier.width(width.coerceAtLeast(200.dp)).height(320.dp)) {
                items(options) { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (name == text) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = previews[name],
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { open = false; onPick(name) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The Font Size box: type a size and press Enter/Done, or pick from the chevron's list. A value
 * that isn't a size (see [parseFontSize]) is dropped and the box goes back to the current size.
 */
@Composable
fun SizeField(size: Float?, onPick: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = sizeText(size)
    // Starts over when the selection's size changes.
    var typed by remember(shown) { mutableStateOf(shown) }
    Box {
        FieldFrame(60.dp) {
            Row(Modifier.padding(start = 6.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = typed,
                    onValueChange = { v -> typed = v.filter { it.isDigit() || it == '.' }.take(6) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parseFontSize(typed)?.let(onPick) ?: run { typed = shown } }),
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(24.dp).clickable { open = true }, contentAlignment = Alignment.Center) { RibbonIcon("ui.dropdown", 16.dp) }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(80.dp)) {
                for (s in FONT_SIZES) {
                    Text(
                        s.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (size == s.toFloat()) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { open = false; onPick(s.toFloat()) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** An icon button that opens a list of text choices (Line Spacing). [checked] marks the current one. */
@Composable
fun IconMenuButton(
    iconId: String,
    label: String,
    choices: List<String>,
    checked: String?,
    disabledReason: String?,
    size: ButtonSize = ButtonSize.Icon,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RibbonButton(iconId, label, size, disabledReason = disabledReason) { open = true }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            choices.forEachIndexed { i, c ->
                Text(
                    (if (c == checked) "✓ " else "    ") + c,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { open = false; onPick(i) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * A split colour button: the icon with a bar of the last colour under it, which applies that colour
 * again; and a chevron that opens the palette. [onApply] gets a [Swatch.param], or null for the
 * "no colour" row ([noneLabel]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSplitButton(
    iconId: String,
    label: String,
    palette: List<Swatch>,
    initial: Swatch,
    noneLabel: String,
    disabledReason: String?,
    /** Adds More Colors…, for a colour that isn't in [palette]. */
    custom: Boolean = false,
    onApply: (String?) -> Unit,
) {
    var last by remember { mutableStateOf(initial) }
    var open by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ColorDialog(last.color, onPick = { hex ->
            picking = false
            parseHexColor(hex)?.let { (r, g, b) -> last = Swatch("Custom", hex, Color(r, g, b)) }
            onApply(hex)
        }, onDismiss = { picking = false })
    }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.BottomCenter) {
                RibbonButton(iconId, label, ButtonSize.Icon, disabledReason = disabledReason) { onApply(last.param) }
                Box(Modifier.padding(bottom = 4.dp).width(16.dp).height(3.dp).background(last.color))
            }
            Box(Modifier.height(CONTROL_HEIGHT + 4.dp).width(14.dp).clickable(enabled = disabledReason == null) { open = true }, contentAlignment = Alignment.Center) {
                RibbonIcon("ui.dropdown", 12.dp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            FlowRow(Modifier.widthIn(max = 36.dp * 4 + 16.dp).padding(horizontal = 8.dp), maxItemsInEachRow = 4) {
                for (sw in palette) {
                    Box(
                        Modifier.padding(2.dp).size(32.dp).background(sw.color, RoundedCornerShape(4.dp))
                            .clickable(onClickLabel = sw.name) { last = sw; open = false; onApply(sw.param) },
                    )
                }
            }
            Text(
                noneLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable { open = false; onApply(null) }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            if (custom) {
                Text(
                    "More Colors…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable { open = false; picking = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** One row of a [SplitMenuButton]: [command] runs with [params]. A blank [command] makes it a heading. */
class MenuChoice(val label: String, val command: String = "", val params: JsonObject = JsonObject(emptyMap()), val checked: Boolean = false)

/** An icon button that runs its command when pressed, with a chevron that opens a list of variants. */
@Composable
fun SplitMenuButton(
    iconId: String,
    label: String,
    checked: Boolean,
    disabledReason: String?,
    choices: List<MenuChoice>,
    onClick: () -> Unit,
    onPick: (MenuChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RibbonButton(iconId, label, ButtonSize.Icon, disabledReason = disabledReason, checked = checked, onClick = onClick)
            Box(Modifier.height(CONTROL_HEIGHT + 4.dp).width(14.dp).clickable(enabled = disabledReason == null) { open = true }, contentAlignment = Alignment.Center) {
                RibbonIcon("ui.dropdown", 12.dp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in choices) {
                if (c.command.isEmpty()) {
                    Text(c.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                } else {
                    Text(
                        (if (c.checked) "✓ " else "    ") + c.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { open = false; onPick(c) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** More Colors: red, green and blue sliders and a hex box, with a preview. [onPick] gets `RRGGBB`. */
@Composable
fun ColorDialog(initial: Color, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var r by remember { mutableStateOf((initial.red * 255).toInt()) }
    var g by remember { mutableStateOf((initial.green * 255).toInt()) }
    var b by remember { mutableStateOf((initial.blue * 255).toInt()) }
    var hex by remember { mutableStateOf(hexOf(r, g, b)) }
    fun channels(nr: Int, ng: Int, nb: Int) { r = nr; g = ng; b = nb; hex = hexOf(nr, ng, nb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("More Colors") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color(r, g, b), RoundedCornerShape(6.dp)))
                for ((name, v, set) in listOf<Triple<String, Int, (Int) -> Unit>>(
                    Triple("Red", r) { channels(it, g, b) }, Triple("Green", g) { channels(r, it, b) }, Triple("Blue", b) { channels(r, g, it) },
                )) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                        Slider(value = v.toFloat(), onValueChange = { set(it.toInt()) }, valueRange = 0f..255f, modifier = Modifier.weight(1f))
                    }
                }
                OutlinedTextField(
                    hex,
                    { t ->
                        hex = t.take(7)
                        parseHexColor(hex)?.let { (nr, ng, nb) -> r = nr; g = ng; b = nb }
                    },
                    label = { Text("Hex") },
                    singleLine = true,
                    isError = parseHexColor(hex) == null,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(hexOf(r, g, b)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
