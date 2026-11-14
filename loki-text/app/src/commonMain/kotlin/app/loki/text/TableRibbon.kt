package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** The Table Design groups that [TableGroup] draws itself. */
val TABLE_DESIGN_GROUPS = setOf("Table Style Options")

/**
 * The Table Layout groups that [TableLayoutGroup] lays out itself, compactly (menus for Select and
 * Delete, stacked small buttons), so the tab fits a laptop screen.
 */
val TABLE_LAYOUT_GROUPS = setOf("Table", "Draw", "Rows & Columns", "Merge", "Alignment", "Cell Size", "Data")

/** Table groups that keep their generic buttons and add [TableExtras] beside them, as (tab, group). */
internal val TABLE_EXTRA_GROUPS = setOf("Table Design" to "Table Styles", "Table Design" to "Borders")

/** Table Style Options: label, and the `table.look` key, in the order they are laid out (two per row). */
internal val LOOK_OPTIONS = listOf(
    "Header Row" to "headerRow", "First Column" to "firstColumn",
    "Total Row" to "totalRow", "Last Column" to "lastColumn",
    "Banded Rows" to "bandedRows", "Banded Columns" to "bandedColumns",
)

/** The cell alignment grid, top row first, as `table.cellAlign` names each position. */
internal val CELL_ALIGNS = listOf(
    listOf("topLeft", "topCenter", "topRight"),
    listOf("centerLeft", "center", "centerRight"),
    listOf("bottomLeft", "bottomCenter", "bottomRight"),
)

/** The tooltip for a [CELL_ALIGNS] position: `bottomRight` reads "Align Bottom Right", `center` "Align Center". */
internal fun cellAlignLabel(value: String): String =
    "Align " + value.replace(Regex("([A-Z])"), " $1").split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

private val AUTOFIT = listOf("AutoFit Contents" to "contents", "AutoFit Window" to "window", "Fixed Column Width" to "fixed")

private val TABLE_BORDER_KINDS = listOf(
    "All Borders" to "all", "Outside Borders" to "outside", "Inside Borders" to "inside",
    "Top Border" to "top", "Bottom Border" to "bottom", "Left Border" to "left", "Right Border" to "right", "No Border" to "none",
)

/** Border widths in points, as the borders panel offers them. */
private val BORDER_WIDTHS = listOf(0.5f, 1f, 1.5f, 2.25f, 3f)

/** The largest row height or column width the Cell Size boxes take, in inches (Word's limit is 22). */
private const val MAX_INCHES = 22f

/** [pt] in inches as the Cell Size boxes show it: up to two decimals, no trailing zeros; blank when unknown. */
internal fun inchText(pt: Float?): String {
    if (pt == null || !pt.isFinite()) return ""
    val hundredths = (pt / 72f * 100f).roundToInt()
    val whole = hundredths / 100
    val frac = hundredths % 100
    return when {
        frac == 0 -> "$whole\""
        frac % 10 == 0 -> "$whole.${frac / 10}\""
        else -> "$whole.${frac.toString().padStart(2, '0')}\""
    }
}

/** A typed size in inches (a trailing `"` or `in` is allowed) as points, or null when it isn't a usable size. */
internal fun parseInches(text: String): Float? {
    val t = text.trim().removeSuffix("\"").removeSuffix("in").trim()
    val inches = t.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f && it <= MAX_INCHES } ?: return null
    return inches * 72f
}

/** The Table Design group called [name], drawn by the app. */
@Composable
fun TableGroup(name: String, env: RibbonEnv) {
    when (name) {
        "Table Style Options" -> StyleOptions(env)
    }
}

/** Checkboxes for the table look, two per row; each runs `table.look` with its key. */
@Composable
private fun StyleOptions(env: RibbonEnv) {
    val look = env.state.table?.look.orEmpty()
    val reason = env.reason("table.look")
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Column(Modifier.alpha(if (reason == null) 1f else 0.45f)) {
            for (pair in LOOK_OPTIONS.chunked(2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for ((label, key) in pair) {
                        val on = look[key] == true
                        Row(
                            Modifier.width(118.dp).clickable(enabled = reason == null) { env.onRunWith("table.look", buildJsonObject { put(key, !on) }) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, onCheckedChange = null, enabled = reason == null, modifier = Modifier.size(24.dp).padding(4.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The controls that take a value, beside the generic buttons of a [TABLE_EXTRA_GROUPS] group. */
@Composable
fun TableExtras(tab: String, group: String, env: RibbonEnv) {
    val table = env.state.table
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (tab to group) {
            "Table Design" to "Table Styles" -> {
                val styles = table?.styles.orEmpty()
                IconMenuButton(
                    "table.style", env.label("table.style", "Table Styles"), styles.map { it.second },
                    styles.firstOrNull { it.first == table?.style }?.second, env.reason("table.style"), ButtonSize.Big,
                ) { i ->
                    val id = styles.getOrNull(i)?.first ?: return@IconMenuButton
                    env.onRunWith("table.style", buildJsonObject { put("style", id) })
                }
                ColorSplitButton("table.shading", env.label("table.shading", "Shading"), SHADINGS, SHADINGS.first(), "No Color", env.reason("table.shading"), custom = true) { c ->
                    env.onRunWith("table.shading", buildJsonObject { if (c == null) put("color", JsonNull) else put("color", c) })
                }
            }
            "Table Design" to "Borders" -> TableBordersButton(env)
        }
    }
}

/**
 * The commands of [group] placed in a [TableLayoutGroup] layout, by id. A command the engine adds to
 * the group later goes in the group's More menu, so it is never out of reach.
 */
internal val TABLE_LAYOUT_PLACED = mapOf(
    "Table" to listOf("table.selectCell", "table.selectColumn", "table.selectRow", "table.selectTable", "table.viewGridlines", "table.properties"),
    "Rows & Columns" to listOf(
        "table.insertRowAbove", "table.insertRowBelow", "table.insertColumnLeft", "table.insertColumnRight",
        "table.deleteCells", "table.deleteColumn", "table.deleteRow", "table.deleteTable",
    ),
    "Draw" to listOf("table.eraser"),
    "Merge" to listOf("table.merge", "table.split", "table.splitTable"),
    "Alignment" to listOf("table.textDirection", "table.cellMargins"),
    "Cell Size" to listOf("table.distributeRows", "table.distributeColumns"),
    "Data" to listOf("table.sort", "table.repeatHeader", "table.toText", "table.formula"),
)

/** A Table Layout group laid out compactly; [items] are the group's ribbon entries. */
@Composable
fun TableLayoutGroup(group: String, items: List<RibbonItem>, env: RibbonEnv) {
    val byId = items.associateBy { it.id }
    val placed = TABLE_LAYOUT_PLACED[group].orEmpty()
    val rest = items.filter { it.id !in placed }
    val state = env.state
    // Commands that need values open their dialog; the rest run at once.
    var dialog by remember { mutableStateOf<TableDialog?>(null) }
    dialog?.let { d -> TableDialogView(d, state.table, state.on, onSubmit = { p -> dialog = null; env.onRunWith(d.command, p) }, onDismiss = { dialog = null }) }
    val run: (String) -> Unit = { id -> tableDialogFor(id)?.let { dialog = it } ?: env.onRun(id) }
    @Composable fun big(id: String) = byId[id]?.let { StatefulBigButton(it, state, run) }
    @Composable fun small(id: String) = byId[id]?.let { StatefulLabeledButton(it, state, run) }
    @Composable fun more() { if (rest.isNotEmpty()) CommandMenu(rest, state, run) { open -> MoreLabeledButton(open) } }
    /** A large button that opens a menu of [ids]: Select, Delete. */
    @Composable fun menu(iconId: String, label: String, ids: List<String>) {
        val entries = ids.mapNotNull { byId[it] }
        if (entries.isEmpty()) return
        CommandMenu(entries, state, run) { open -> RibbonButton(iconId, label, ButtonSize.Big, disabledReason = if (entries.all { it.id in state.disabled }) state.disabled[entries.first().id] else null) { open() } }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        when (group) {
            "Table" -> {
                menu("table.selectTable", "Select", listOf("table.selectCell", "table.selectColumn", "table.selectRow", "table.selectTable"))
                Column { small("table.viewGridlines"); small("table.properties"); more() }
            }
            "Draw" -> {
                TableToolButton(env, "table.draw", "Draw Table")
                TableToolButton(env, "table.eraser", "Eraser")
            }
            "Rows & Columns" -> {
                big("table.insertRowAbove")
                Column { small("table.insertRowBelow"); small("table.insertColumnLeft"); small("table.insertColumnRight") }
                menu("table.deleteTable", "Delete", listOf("table.deleteCells", "table.deleteColumn", "table.deleteRow", "table.deleteTable"))
                more()
            }
            "Merge" -> Column { small("table.merge"); small("table.split"); small("table.splitTable"); more() }
            "Alignment" -> {
                CellAlignGrid(state.table?.cellAlign, env.reason("table.cellAlign")) { v -> env.onRunWith("table.cellAlign", buildJsonObject { put("value", v) }) }
                Column { small("table.textDirection"); small("table.cellMargins"); more() }
            }
            "Cell Size" -> {
                val table = state.table
                IconMenuButton("table.autofit", env.label("table.autofit", "AutoFit"), AUTOFIT.map { it.first }, null, env.reason("table.autofit"), ButtonSize.Big) { i ->
                    val mode = AUTOFIT.getOrNull(i)?.second ?: return@IconMenuButton
                    env.onRunWith("table.autofit", buildJsonObject { put("mode", mode) })
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InchField("app.rowHeight", "Height", table?.rowHeight, env.reason("table.rowHeight")) { pt ->
                            env.onRunWith("table.rowHeight", buildJsonObject { put("height", pt); put("rule", if (table?.exact == true) "exact" else "atLeast") })
                        }
                        byId["table.distributeRows"]?.let { StatefulIconButton(it, state, env.onRun) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InchField("app.columnWidth", "Width", table?.columnWidth, env.reason("table.columnWidth")) { pt ->
                            env.onRunWith("table.columnWidth", buildJsonObject { put("width", pt) })
                        }
                        byId["table.distributeColumns"]?.let { StatefulIconButton(it, state, env.onRun) }
                    }
                    more()
                }
            }
            "Data" -> {
                big("table.sort")
                Column { small("table.repeatHeader"); small("table.toText"); small("table.formula"); more() }
            }
        }
    }
}

/**
 * A size box in inches with its icon and name in the tooltip. Typing a size and pressing Enter
 * applies it; anything else goes back to the current size.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun InchField(iconId: String, label: String, pt: Float?, disabledReason: String?, onPick: (Float) -> Unit) {
    val shown = inchText(pt)
    // Starts over when the caret's size changes.
    var typed by remember(shown) { mutableStateOf(shown) }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(if (disabledReason == null) label else "$label — $disabledReason") } },
        state = rememberTooltipState(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (disabledReason == null) 1f else 0.45f)) {
            RibbonIcon(iconId, 16.dp)
            FieldFrame(64.dp) {
                BasicTextField(
                    value = typed,
                    onValueChange = { v -> typed = v.filter { it.isDigit() || it == '.' || it == '"' }.take(6) },
                    enabled = disabledReason == null,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { parseInches(typed)?.let(onPick) ?: run { typed = shown } }),
                    modifier = Modifier.padding(horizontal = 6.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** Nine buttons for the cell alignment, each drawn as lines of text in that corner; [current] is marked. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CellAlignGrid(current: String?, disabledReason: String?, onPick: (String) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val marked = MaterialTheme.colorScheme.secondaryContainer
    Column(Modifier.alpha(if (disabledReason == null) 1f else 0.45f)) {
        CELL_ALIGNS.forEachIndexed { row, values ->
            Row {
                values.forEachIndexed { col, v ->
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(cellAlignLabel(v)) } },
                        state = rememberTooltipState(),
                    ) {
                        Box(
                            Modifier.size(28.dp).padding(1.dp)
                                .background(if (v == current) marked else Color.Transparent, RoundedCornerShape(3.dp))
                                .clickable(enabled = disabledReason == null) { onPick(v) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.size(16.dp)) {
                                val stroke = 1.5.dp.toPx()
                                val lines = listOf(size.width * 0.9f, size.width * 0.55f)
                                val gap = size.height * 0.28f
                                val block = gap * (lines.size - 1)
                                val top = when (row) {
                                    0 -> stroke
                                    1 -> (size.height - block) / 2f
                                    else -> size.height - block - stroke
                                }
                                lines.forEachIndexed { i, w ->
                                    val x0 = when (col) {
                                        0 -> 0f
                                        1 -> (size.width - w) / 2f
                                        else -> size.width - w
                                    }
                                    val y = top + gap * i
                                    drawLine(ink, Offset(x0, y), Offset(x0 + w, y), stroke)
                                }
                                // The cell's outline, so the lines read as sitting in a corner of it.
                                drawRect(ink.copy(alpha = 0.35f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Table borders: choose the line width and colour (they stay chosen), then the edges to draw them
 * on. Picking edges applies the border and closes the panel.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TableBordersButton(env: RibbonEnv) {
    var open by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0.5f) }
    var color by remember { mutableStateOf<Swatch?>(null) }
    val label = env.label("table.borders", "Borders")
    Box {
        RibbonButton("table.borders", label, ButtonSize.Big, disabledReason = env.reason("table.borders")) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val head = MaterialTheme.typography.labelSmall
            val dim = MaterialTheme.colorScheme.outline
            Text("Line width", style = head, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 3) {
                for (w in BORDER_WIDTHS) {
                    FilterChip(
                        selected = width == w, onClick = { width = w },
                        label = { Text("${spacingText(w)} pt", style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
            Text("Line color", style = head, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 6) {
                for (sw in FONT_COLORS) {
                    val picked = color?.param == sw.param
                    Box(
                        Modifier.padding(2.dp).size(26.dp)
                            .background(sw.color, RoundedCornerShape(4.dp))
                            .border(if (picked) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .clickable { color = sw },
                    )
                }
            }
            Text("Apply to", style = head, color = dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            for ((name, kind) in TABLE_BORDER_KINDS) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable {
                        open = false
                        env.onRunWith("table.borders", buildJsonObject {
                            put("kind", kind)
                            if (kind != "none") {
                                put("width", width)
                                color?.let { put("color", it.param) }
                            }
                        })
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** A page tool's button: on while the tool is, and pressing it again turns the tool off. */
@Composable
internal fun TableToolButton(env: RibbonEnv, id: String, fallback: String, size: ButtonSize = ButtonSize.Big) {
    val on = env.tableTool == id
    RibbonButton(id, env.label(id, fallback), size, disabledReason = env.reason(id).takeUnless { on }, checked = on) {
        env.onTableTool(if (on) null else id)
    }
}
