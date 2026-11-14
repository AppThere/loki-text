package app.loki.text

import app.loki.kit.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.SlidersHorizontal
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import com.composables.icons.lucide.ArrowDownRight
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Lucide
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Commands that look parameterless to [runsWithoutParams] but fail without input: their params
 * are optional keys with a rule in prose ("give `tag` or `source`", "`path` or `text`"), or they
 * act on an id a control has to supply. They need a dialog or control, so no plain button.
 */
internal val NEEDS_INPUT = setOf(
    "format.font", "styles.addToGallery", "layout.size", "design.themeColors",
    "references.citation", "references.markCitation", "references.markEntry",
    "select.addObject", "select.objects",
    "review.compare", "review.combine", "review.resolveComment",
    "tools.updateStyles",
)

/**
 * What the engine says about its commands right now: those that can't run (with the reason) and
 * the toggles that are on (bold at the caret, the ruler showing, …). From `loki.commandState`.
 */
class CommandState(
    val disabled: Map<String, String>,
    val on: Set<String>,
    val layout: LayoutState = LayoutState.EMPTY,
    /** The contextual tabs the selection brings up (Table Layout, Picture Format, …), in ribbon order. */
    val contextual: List<String> = emptyList(),
    /** The table at the caret, or null outside a table. */
    val table: TableState? = null,
    /** The selected picture, or null when none is. */
    val picture: PictureState? = null,
    /** The selected picture, shape, text box or group, for the Arrange controls; null when none is. */
    val arrange: ArrangeState? = null,
    /** The paragraph style at the caret (the default one when the paragraph names none). */
    val styleParagraph: String? = null,
    /** The character style of the text at the caret, if it has one. */
    val styleCharacter: String? = null,
) {
    companion object {
        val EMPTY = CommandState(emptyMap(), emptySet())
    }
}

/**
 * The paragraph and page settings at the caret that the Home and Layout menus mark as current:
 * indents and spacing in points, line spacing as a multiple, and the engine's names for the
 * section's margins and columns presets and its paper. A field is null when unknown.
 */
class LayoutState(
    val indentLeft: Float?,
    val indentRight: Float?,
    val spaceBefore: Float?,
    val spaceAfter: Float?,
    val lineSpacing: Float?,
    val landscape: Boolean?,
    val paper: String?,
    val margins: String?,
    val columns: String?,
) {
    companion object {
        val EMPTY = LayoutState(null, null, null, null, null, null, null, null, null)
    }
}

fun parseLayoutState(e: JsonElement?): LayoutState {
    val o = e as? JsonObject ?: return LayoutState.EMPTY
    fun f(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.floatOrNull
    fun t(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    return LayoutState(
        f("indentLeft"), f("indentRight"), f("spaceBefore"), f("spaceAfter"), f("lineSpacing"),
        (o["landscape"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull, t("paper"), t("margins"), t("columns"),
    )
}

/** Is [value] (in points or a multiple) the one the engine reports, give or take rounding? */
fun sameNumber(a: Float?, b: Float): Boolean = a != null && abs(a - b) < 0.01f

fun parseCommandState(state: JsonElement): CommandState {
    val o = state as? JsonObject ?: return CommandState.EMPTY
    val disabled = (o["disabled"] as? JsonObject).orEmpty().mapNotNull { (id, why) -> why.jsonPrimitive.contentOrNull?.let { id to it } }.toMap()
    val on = (o["on"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    val contextual = (o["contextual"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it in CONTEXTUAL_ORDER }
    return CommandState(disabled, on, parseLayoutState(o["layout"]), contextual, parseTableState(o["table"]), parsePictureState(o["picture"]), parseArrangeState(o["arrange"]),
        styleParagraph = ((o["styleAt"] as? JsonObject)?.get("paragraph") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
        styleCharacter = ((o["styleAt"] as? JsonObject)?.get("character") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
    )
}

/**
 * The table at the caret, as the Table Design and Table Layout tabs show it: its style, the Table
 * Style Options (`table.look` keys), the caret row's set height in points (null when its text sets
 * it) and whether it is exact, the caret column's width in points, the cell alignment as
 * `table.cellAlign` names it, and the document's table styles as (id, name).
 */
class TableState(
    val style: String?,
    val look: Map<String, Boolean>,
    val rowHeight: Float?,
    val exact: Boolean,
    val columnWidth: Float?,
    val cellAlign: String?,
    val styles: List<Pair<String, String>>,
    /** Columns in the table's grid. */
    val columns: Int = 0,
    /** `left`, `center` or `right`. */
    val align: String = "left",
    /** Preferred table width in points; null is automatic. */
    val width: Float? = null,
    val indent: Float? = null,
    /** Can the caret row break across pages? */
    val allowBreak: Boolean = true,
    /** Preferred width of the caret cell in points; null is automatic. */
    val cellWidth: Float? = null,
    /** `top`, `center` or `bottom`. */
    val valign: String = "top",
    /** Default cell margins in points: top, left, bottom, right. */
    val cellMargins: List<Float> = listOf(0f, 5.4f, 0f, 5.4f),
    val altTitle: String = "",
    val altText: String = "",
)

fun parseTableState(e: JsonElement?): TableState? {
    val o = e as? JsonObject ?: return null
    fun p(k: String) = o[k] as? kotlinx.serialization.json.JsonPrimitive
    val look = (o["look"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull?.let { k to it } }.toMap()
    val styles = (o["styles"] as? JsonArray).orEmpty().mapNotNull { st ->
        val so = st as? JsonObject ?: return@mapNotNull null
        val id = (so["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        id to ((so["name"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: id)
    }
    return TableState(
        style = p("style")?.contentOrNull,
        look = look,
        rowHeight = p("rowHeight")?.floatOrNull?.takeIf { it.isFinite() },
        exact = p("exact")?.booleanOrNull == true,
        columnWidth = p("columnWidth")?.floatOrNull?.takeIf { it.isFinite() },
        cellAlign = p("cellAlign")?.contentOrNull,
        styles = styles,
        columns = p("columns")?.intOrNull?.coerceAtLeast(0) ?: 0,
        align = p("align")?.contentOrNull ?: "left",
        width = p("width")?.floatOrNull?.takeIf { it.isFinite() },
        indent = p("indent")?.floatOrNull?.takeIf { it.isFinite() },
        allowBreak = p("allowBreak")?.booleanOrNull != false,
        cellWidth = p("cellWidth")?.floatOrNull?.takeIf { it.isFinite() },
        valign = p("valign")?.contentOrNull ?: "top",
        cellMargins = (o["cellMargins"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.floatOrNull?.takeIf { f -> f.isFinite() } }
            ?.takeIf { it.size == 4 } ?: listOf(0f, 5.4f, 0f, 5.4f),
        altTitle = p("altTitle")?.contentOrNull.orEmpty(),
        altText = p("altText")?.contentOrNull.orEmpty(),
    )
}

/** The main tabs, in ribbon order. */
internal val TAB_ORDER = listOf("Home", "Insert", "Draw", "Design", "Layout", "References", "Mailings", "Review", "View", "Developer")

/**
 * Tabs that only make sense with something selected. [parseRibbon] builds them like any other tab;
 * [visibleTabs] shows the ones the engine says the selection brings up (`CommandState.contextual`).
 */
internal val CONTEXTUAL_ORDER = listOf(
    "Table Design", "Table Layout", "Picture Format", "Shape Format", "SmartArt Design", "Chart Design", "Equation", "Outlining", "Header & Footer",
)

/** [tabs] with the contextual tabs the selection doesn't bring up left out. */
fun visibleTabs(tabs: List<RibbonTab>, state: CommandState): List<RibbonTab> =
    tabs.filter { it.name !in CONTEXTUAL_ORDER || it.name in state.contextual }

/**
 * The Home tab's groups in the order people expect (the engine lists them by first command):
 * the clipboard, then text, then paragraphs, styles, and editing last. Groups it does not name keep
 * their place after these.
 */
internal val HOME_GROUP_ORDER = listOf("Clipboard", "Font", "Paragraph", "Styles", "Editing")

/** [groups] of [tab] in the order that tab's layout wants; the sort is stable, so other tabs are unchanged. */
internal fun orderGroups(tab: String, groups: List<RibbonGroup>): List<RibbonGroup> {
    if (tab != "Home") return groups
    return groups.sortedBy { g -> HOME_GROUP_ORDER.indexOf(g.name).let { if (it < 0) HOME_GROUP_ORDER.size else it } }
}

/**
 * The small corner control of a group that opens its dialog or pane, as (tab, group) to command. The
 * app handles each command by opening its own dialog.
 */
internal val GROUP_LAUNCHERS = mapOf(
    ("Home" to "Font") to "format.fontDialog",
    ("Home" to "Paragraph") to "para.dialog",
    ("Home" to "Styles") to "styles.manage",
    ("Layout" to "Page Setup") to "layout.pageSetup",
    ("Layout" to "Paragraph") to "para.dialog",
)

/**
 * Loki Text's ribbon from `engine.commands`: the kit's ribbon (every listed command of each tab),
 * with the groups the app adds and Home's groups in the order people expect.
 */
fun parseRibbon(commands: JsonElement): List<RibbonTab> =
    parseRibbon(commands, LOKI_PROFILE, ::hasCustomGroup).map { t -> RibbonTab(t.name, orderGroups(t.name, withAppGroups(t.name, t.groups))) }

/** What the Styles gallery needs to draw one style's preview (from `styles.manage`). */
class StylePreview(
    val id: String,
    val name: String,
    val sizePt: Float?,
    val colorHex: String?,
    val bold: Boolean,
    val italic: Boolean,
    val smallCaps: Boolean,
)

/**
 * The gallery style to mark for the caret: its character style when the gallery has it (a
 * character style in the text wins, as in Word), else its paragraph style; null when neither is in
 * the gallery.
 */
internal fun currentStyle(styles: List<StylePreview>, state: CommandState): String? =
    listOfNotNull(state.styleCharacter, state.styleParagraph).firstOrNull { id -> styles.any { it.id == id } }

/** The styles shown in the gallery, in the engine's recommended order. */
fun parseStyles(manage: JsonElement): List<StylePreview> = (manage as? JsonArray).orEmpty().mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    if (o["inGallery"]?.jsonPrimitive?.booleanOrNull != true || o["hidden"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
    val f = o["format"] as? JsonObject ?: JsonObject(emptyMap())
    StylePreview(
        id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
        name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
        sizePt = f["size"]?.jsonPrimitive?.floatOrNull,
        colorHex = f["color"]?.jsonPrimitive?.contentOrNull,
        bold = f["bold"]?.jsonPrimitive?.booleanOrNull == true,
        italic = f["italic"]?.jsonPrimitive?.booleanOrNull == true,
        smallCaps = f["smallCaps"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

/** `RRGGBB` as a colour, or null when it isn't one. */
private fun hexColor(hex: String?): Color? = hex?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { Color(0xFF000000.toInt() or it) }

/** One style, drawn the way it looks, with its name; click applies it. */
@Composable
private fun StyleChip(style: StylePreview, current: Boolean = false, onClick: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surfaceContainerLowest
    // A dark style colour would vanish on a dark theme; use the theme's text colour there.
    val color = hexColor(style.colorHex)?.takeIf { abs(it.luminance() - surface.luminance()) > 0.3f } ?: MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        // The style at the caret is outlined in the accent colour, as Word marks it.
        border = if (current) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = surface,
        modifier = Modifier.padding(end = 4.dp, bottom = 4.dp).width(76.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
                Text(
                    "AaBb",
                    color = color,
                    // Document points on a ribbon-sized preview: keep the relative sizes, bounded.
                    fontSize = ((style.sizePt ?: 11f) * 1.1f).coerceIn(12f, 22f).sp,
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                    letterSpacing = if (style.smallCaps) 1.sp else 0.sp,
                    maxLines = 1,
                )
            }
            Text(style.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An icon button with a tooltip naming the command and its shortcut. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandIconButton(
    id: String,
    label: String,
    shortcut: String,
    /** Why the command can't run now (greys the button and is shown in the tooltip); null when it can. */
    disabledReason: String? = null,
    /** A toggle that is on, drawn pressed. */
    checked: Boolean = false,
    onClick: () -> Unit,
) {
    val name = if (shortcut.isEmpty()) label else "$label  (${displayShortcut(shortcut)})"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(if (disabledReason == null) name else "$name — $disabledReason") } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = disabledReason == null,
            colors = IconButtonDefaults.iconButtonColors(containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
            modifier = Modifier.size(32.dp),
        ) { RibbonIcon(id) }
    }
}

/** Height of the tab strip with a mouse or trackpad (a desktop window), close to a desktop app's. */
internal val TAB_HEIGHT_POINTER = 32.dp

/**
 * Height of the tab strip in phone and tablet windows, where fingers hit it. Below the 48 dp that
 * Material recommends, but above the 24 px floor of WCAG 2.2; the strip scrolls sideways, so a
 * miss costs a second tap, not a wrong command.
 */
internal val TAB_HEIGHT_TOUCH = 40.dp

/** The tab strip's height for a window of [sizeClass]. */
internal fun tabStripHeight(sizeClass: SizeClass) = if (sizeClass == SizeClass.Expanded) TAB_HEIGHT_POINTER else TAB_HEIGHT_TOUCH

/**
 * A group: its [content] with the [footer] row (the name, and the corner control) under it. The
 * footer is as wide as the group, whichever is wider, content or name, so the corner control sits
 * at the group's right edge. Content is centred over the footer when the name is the wider.
 */
@Composable
private fun GroupFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit, footer: @Composable () -> Unit) {
    Layout(content = { Box { content() }; Box { footer() } }, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val body = measurables[0].measure(loose)
        val foot = measurables[1]
        val width = maxOf(body.width, foot.maxIntrinsicWidth(Int.MAX_VALUE))
        val footPlaceable = foot.measure(Constraints.fixedWidth(width))
        layout(width, body.height + footPlaceable.height) {
            body.placeRelative((width - body.width) / 2, 0)
            footPlaceable.placeRelative(0, body.height)
        }
    }
}

/** The ribbon's tab bar: white labels on [BarBlue], a white bar under the selected tab. */
@Composable
private fun RibbonTabRow(tabs: List<RibbonTab>, selectedIndex: Int, height: Dp, onSelect: (Int) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 0.dp,
        containerColor = BarBlue,
        contentColor = Color.White,
        indicator = { positions ->
            positions.getOrNull(selectedIndex)?.let { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(it), height = 3.dp, color = Color.White) }
        },
        divider = {},
    ) {
        tabs.forEachIndexed { i, t ->
            // The content form of Tab has no built-in 48 dp minimum, so the height is ours to set.
            Tab(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                selectedContentColor = Color.White,
                unselectedContentColor = Color.White.copy(alpha = 0.78f),
            ) {
                Box(Modifier.height(height).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(t.name, style = MaterialTheme.typography.labelLarge, fontWeight = if (i == selectedIndex) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

/** [CommandIconButton] for a ribbon item, with its state from [state]. */
@Composable
internal fun StatefulIconButton(item: RibbonItem, state: CommandState, onRun: (String) -> Unit) =
    CommandIconButton(item.id, item.label, item.shortcut, state.disabled[item.id], item.id in state.on) { onRun(item.id) }

/** A large button with the command's name under its icon, with its state from [state]. */
@Composable
internal fun StatefulBigButton(item: RibbonItem, state: CommandState, onRun: (String) -> Unit) =
    RibbonButton(item.id, item.label, ButtonSize.Big, item.shortcut, state.disabled[item.id], item.id in state.on) { onRun(item.id) }

/** A small button with the command's name beside its icon. */
@Composable
internal fun StatefulLabeledButton(item: RibbonItem, state: CommandState, onRun: (String) -> Unit) =
    RibbonButton(item.id, item.label, ButtonSize.Labeled, item.shortcut, state.disabled[item.id], item.id in state.on) { onRun(item.id) }

/** The "more commands" entry of a group, as a labelled button. */
@Composable
internal fun MoreLabeledButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(28.dp)) {
        Icon(Lucide.Ellipsis, contentDescription = null, modifier = Modifier.size(16.dp))
        Text("More", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Height of a group's content area: three rows of icon buttons. */
private val GROUP_CONTENT_HEIGHT = 96.dp
/** Room for a group's name under its buttons. */
private val GROUP_NAME_HEIGHT = 16.dp

/** The "more commands" button that opens a group's menu. */
@Composable
private fun MoreButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(Lucide.Ellipsis, contentDescription = "More commands", modifier = Modifier.size(18.dp))
    }
}

/** A button that opens a menu of commands. */
@Composable
internal fun CommandMenu(items: List<RibbonItem>, state: CommandState, onRun: (String) -> Unit, button: @Composable (onClick: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        button { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 240.dp)) {
            // Own rows rather than DropdownMenuItem, which caps an item at 280 dp and cuts long names.
            for (item in items) {
                val off = item.id in state.disabled
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !off) { open = false; onRun(item.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (item.id in state.on) "✓ " else "") + item.menuLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (off) MaterialTheme.colorScheme.outline else Color.Unspecified,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.shortcut.isNotEmpty()) {
                        Text(
                            displayShortcut(item.shortcut),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Ribbon(
    tabs: List<RibbonTab>,
    styles: List<StylePreview>,
    onApplyStyle: (String) -> Unit,
    onRun: (String) -> Unit,
    state: CommandState = CommandState.EMPTY,
    sizeClass: SizeClass = SizeClass.Expanded,
    /** Quick-access buttons; the full ribbon leaves them to the caller, the smaller ones put them in their row. */
    leading: @Composable () -> Unit = {},
    /** The formatting at the selection, shown in the font and size boxes. */
    format: FormatState = FormatState.EMPTY,
    /** Font families for the font box. */
    fonts: List<String> = emptyList(),
    /** Runs a command with parameters (a font name, a colour). */
    onRunWith: (String, JsonObject) -> Unit = { _, _ -> },
    /** The page tool that is on (Draw Table, Eraser), and how to switch it. */
    tableTool: String? = null,
    onTableTool: (String?) -> Unit = {},
    /** The structures and symbols the Equation tab offers. */
    equation: EquationGallery = EquationGallery.EMPTY,
) {
    val shown = remember(tabs, state.contextual) { visibleTabs(tabs, state) }
    if (shown.isEmpty()) return
    // Selected by name: a contextual tab can vanish with the selection, which falls back to the first tab.
    var selectedName by remember { mutableStateOf(shown.first().name) }
    // Entering a header or footer brings up its tab, as Word does; the other contextual tabs wait to be chosen.
    val inHeaderFooter = "Header & Footer" in state.contextual
    LaunchedEffect(inHeaderFooter) { if (inHeaderFooter) selectedName = "Header & Footer" }
    val tab = shown.firstOrNull { it.name == selectedName } ?: shown.first()
    val previews = rememberPreviewFonts()
    val env = remember(tabs, state, format, fonts, previews, onRun, onRunWith, tableTool, onTableTool, equation) {
        RibbonEnv(tabs.flatMap { t -> t.groups.flatMap { it.items } }.associateBy { it.id }, state, format, fonts, previews, onRun, onRunWith, tableTool, onTableTool, equation)
    }
    if (sizeClass != SizeClass.Expanded) {
        SimpleRibbon(shown, tab, { selectedName = shown.getOrNull(it)?.name ?: selectedName }, styles, onApplyStyle, onRun, state, leading, env, hideFont = sizeClass == SizeClass.Compact)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        // The row's own divider stops at the last tab; a divider of our own spans the whole width.
        RibbonTabRow(shown, shown.indexOf(tab), tabStripHeight(sizeClass)) { selectedName = shown.getOrNull(it)?.name ?: selectedName }
        HorizontalDivider()
        ScrollRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            tab.groups.forEachIndexed { gi, g ->
                // A rule between neighbouring groups, as tall as a group's buttons plus its name.
                if (gi > 0) VerticalDivider(Modifier.height(GROUP_CONTENT_HEIGHT + GROUP_NAME_HEIGHT), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                val featured = g.items.filter { hasRibbonIcon(it.id) && !it.needsInput }
                val rest = g.items - featured.toSet()
                GroupFrame(Modifier.padding(horizontal = 6.dp), content = {
                    // Every group's content gets the same height, so the names below line up.
                    Box(Modifier.height(GROUP_CONTENT_HEIGHT), contentAlignment = if (hasCustomGroup(tab.name, g.name)) Alignment.Center else Alignment.TopCenter) {
                      // Controls that take a value sit beside the group's ordinary buttons.
                      val extras = hasGroupExtras(tab.name, g.name)
                      @Composable
                      fun Body() {
                        if (hasCustomGroup(tab.name, g.name)) {
                            // Font and size boxes, colour buttons, a large Paste: controls that take a value.
                            CustomGroup(tab.name, g.name, env, g.items)
                        } else if (tab.name == "Home" && g.name == "Styles" && styles.isNotEmpty()) {
                            // The gallery: each style drawn as it looks. The rest of the group is a menu.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // A grid that scrolls vertically, so every style is reachable. The style at
                                // the caret is marked, and scrolled into view when the caret moves to another.
                                val current = currentStyle(styles, state)
                                val grid = rememberLazyGridState()
                                LaunchedEffect(current, styles) {
                                    val at = styles.indexOfFirst { it.id == current }
                                    if (at >= 0 && grid.layoutInfo.visibleItemsInfo.none { it.index == at }) grid.animateScrollToItem(at)
                                }
                                LazyVerticalGrid(columns = GridCells.Fixed(4), state = grid, modifier = Modifier.width(80.dp * 4).height(GROUP_CONTENT_HEIGHT)) {
                                    items(styles) { st -> StyleChip(st, st.id == current) { onApplyStyle(st.id) } }
                                }
                                CommandMenu(g.items, state, onRun) { openMenu -> MoreButton(openMenu) }
                            }
                        } else if (featured.isEmpty() && g.items.size == 1) {
                            // A menu of one is pointless: the group is just that command's button.
                            val only = g.items.single()
                            TextButton(onClick = { onRun(only.id) }, enabled = only.id !in state.disabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(only.menuLabel, style = MaterialTheme.typography.labelLarge)
                            }
                        } else if (featured.isEmpty()) {
                            // Nothing here has an icon yet: one button opens the group's commands by name.
                            CommandMenu(g.items, state, onRun) { openMenu -> MoreButton(openMenu) }
                        } else if (featured.size <= 3 && rest.isEmpty()) {
                            // A few commands: large buttons, each with its name under the icon.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                for (item in featured) StatefulBigButton(item, state, onRun)
                            }
                        } else {
                            // Many commands: columns of three, each with its name beside the icon.
                            Row(verticalAlignment = Alignment.Top) {
                                val cells = featured.size + (if (rest.isNotEmpty()) 1 else 0)
                                for (c in 0 until (cells + 2) / 3) {
                                    Column {
                                        for (i in c * 3 until minOf(c * 3 + 3, cells)) {
                                            if (i < featured.size) StatefulLabeledButton(featured[i], state, onRun)
                                            else CommandMenu(rest, state, onRun) { openMenu -> MoreLabeledButton(openMenu) }
                                        }
                                    }
                                }
                            }
                        }
                      }
                      if (extras) Row(verticalAlignment = Alignment.CenterVertically) { Body(); GroupExtras(tab.name, g.name, env) } else Body()
                    }
                }, footer = {
                    val launcher = GROUP_LAUNCHERS[tab.name to g.name]
                    Box(Modifier.height(GROUP_NAME_HEIGHT)) {
                        // The room either side keeps the name clear of the corner control, so the group is never narrower than both.
                        Text(
                            g.name.ifEmpty { tab.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = if (launcher != null || hasCustomGroup(tab.name, g.name)) GROUP_NAME_HEIGHT + 2.dp else 0.dp),
                        )
                        // A group the app draws shows its chosen controls; this lists every command of the group by name.
                        if (hasCustomGroup(tab.name, g.name) && g.items.isNotEmpty()) {
                            Box(Modifier.align(Alignment.CenterStart)) {
                                CommandMenu(g.items, state, onRun) { openMenu ->
                                    Icon(
                                        Lucide.Ellipsis, contentDescription = "All ${g.name.ifEmpty { tab.name }} commands", tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(GROUP_NAME_HEIGHT).clickable(onClick = openMenu),
                                    )
                                }
                            }
                        }
                        // The corner control opens the group's own dialog or pane.
                        if (launcher != null) {
                            val why = state.disabled[launcher]
                            Icon(
                                Lucide.ArrowDownRight,
                                contentDescription = "${g.name} options",
                                tint = MaterialTheme.colorScheme.outline.copy(alpha = if (why == null) 1f else 0.4f),
                                modifier = Modifier.align(Alignment.CenterEnd).size(GROUP_NAME_HEIGHT).clickable(enabled = why == null) { onRun(launcher) },
                            )
                        }
                    }
                })
            }
        }
        HorizontalDivider()
    }
}

/** How much room the window gives the ribbon: a phone, a tablet, a desktop. */
enum class SizeClass { Compact, Medium, Expanded }

@Composable
private fun VerticalRule() = VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))

/** The Styles gallery as a button that opens a grid of every style. */
@Composable
private fun StylesButton(styles: List<StylePreview>, current: String?, onApplyStyle: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Names the style at the caret, as the full gallery's marked chip does.
    val label = styles.firstOrNull { it.id == current }?.name ?: "Styles"
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.width(80.dp * 3 + 8.dp).height(240.dp)) {
                items(styles) { st -> StyleChip(st, st.id == current) { open = false; onApplyStyle(st.id) } }
            }
        }
    }
}

/**
 * A group's own controls (font boxes, colours, spacing, table sizes…) behind a button named after
 * the group, for windows too narrow to lay the group out: the same controls as the full ribbon,
 * in a panel that scrolls sideways.
 */
@Composable
private fun GroupPanelButton(tab: String, group: RibbonGroup, env: RibbonEnv) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(start = 8.dp, end = 2.dp), modifier = Modifier.height(32.dp)) {
            Text(group.name.ifEmpty { tab }, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(Lucide.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).height(GROUP_CONTENT_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                if (hasCustomGroup(tab, group.name)) CustomGroup(tab, group.name, env, group.items)
                if (hasGroupExtras(tab, group.name)) GroupExtras(tab, group.name, env)
            }
        }
    }
}

/**
 * The ribbon for small windows: the tabs, and under them one row instead of grouped panels. Each
 * group shows its icon commands, and a button named after the group opens its value controls
 * ([GroupPanelButton]). The row scrolls, and the all-commands list ([leading] has its button)
 * holds every command by name, so no feature is out of reach on a phone.
 */
@Composable
private fun SimpleRibbon(
    tabs: List<RibbonTab>,
    tab: RibbonTab,
    onSelect: (Int) -> Unit,
    styles: List<StylePreview>,
    onApplyStyle: (String) -> Unit,
    onRun: (String) -> Unit,
    state: CommandState,
    leading: @Composable () -> Unit,
    env: RibbonEnv,
    /** The Font group's boxes stay out of the row (small screens): its panel and the floating toolbar have them. */
    hideFont: Boolean = false,
) {
    val shown = tab
    Column(Modifier.fillMaxWidth()) {
        RibbonTabRow(tabs, tabs.indexOf(tab), TAB_HEIGHT_TOUCH, onSelect)
        HorizontalDivider()
        ScrollRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            leading()
            if (shown.name == "Home" && !hideFont) {
                VerticalRule()
                // Font and size, as on the full ribbon.
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    DropdownField(env.format.font.orEmpty(), 130.dp, env.fonts, "Font", env.previews) { f -> env.onRunWith("format.font", buildJsonObject { put("name", f) }) }
                    Spacer(Modifier.width(4.dp))
                    SizeField(env.format.size) { sz -> env.onRunWith("format.size", buildJsonObject { put("size", sz) }) }
                }
            }
            if (shown.name == "Home" && styles.isNotEmpty()) StylesButton(styles, currentStyle(styles, state), onApplyStyle)
            for (g in shown.groups) {
                val featured = g.items.filter { hasRibbonIcon(it.id) && !it.needsInput }
                val panel = hasCustomGroup(shown.name, g.name) || hasGroupExtras(shown.name, g.name)
                val rest = g.items - featured.toSet()
                if (featured.isEmpty() && !panel && rest.isEmpty()) continue
                VerticalRule()
                for (item in featured) StatefulIconButton(item, state, onRun)
                if (panel) GroupPanelButton(shown.name, g, env)
                // What has no icon is a menu under the group's name, so the row holds the whole tab.
                else if (rest.isNotEmpty()) CommandMenu(rest, state, onRun) { openMenu ->
                    TextButton(onClick = openMenu, contentPadding = PaddingValues(start = 8.dp, end = 2.dp), modifier = Modifier.height(32.dp)) {
                        Text(if (featured.isEmpty()) g.name.ifEmpty { shown.name } else "More", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Icon(Lucide.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * A row that scrolls sideways. Touch can drag it; a mouse can't, so arrow buttons appear at an
 * end that has more to show.
 */
@Composable
private fun ScrollRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val state = rememberScrollState()
    val scope = rememberCoroutineScope()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (state.canScrollBackward) {
            IconButton(onClick = { scope.launch { state.animateScrollBy(-320f) } }, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.ChevronLeft, contentDescription = "Scroll left", modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.weight(1f).horizontalScroll(state), verticalAlignment = Alignment.CenterVertically, content = content)
        if (state.canScrollForward) {
            IconButton(onClick = { scope.launch { state.animateScrollBy(320f) } }, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.ChevronRight, contentDescription = "Scroll right", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The selected picture, as Picture Format shows it: display size in points, crop as fractions of
 * the source (left, top, right, bottom), and its alt text, title and decorative mark.
 */
class PictureState(
    val width: Float?,
    val height: Float?,
    val crop: List<Float>,
    val alt: String,
    val title: String,
    val decorative: Boolean,
)

fun parsePictureState(e: JsonElement?): PictureState? {
    val o = e as? JsonObject ?: return null
    fun p(k: String) = o[k] as? kotlinx.serialization.json.JsonPrimitive
    val crop = (o["crop"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.floatOrNull?.takeIf { f -> f.isFinite() } }
    return PictureState(
        width = p("width")?.floatOrNull?.takeIf { it.isFinite() && it > 0f },
        height = p("height")?.floatOrNull?.takeIf { it.isFinite() && it > 0f },
        crop = crop?.takeIf { it.size == 4 } ?: listOf(0f, 0f, 0f, 0f),
        alt = p("alt")?.contentOrNull.orEmpty(),
        title = p("title")?.contentOrNull.orEmpty(),
        decorative = p("decorative")?.booleanOrNull == true,
    )
}

/**
 * Groups the app adds to a tab the engine lists elsewhere, as (tab, group, the group it goes
 * before): Picture Format gets Layout's Arrange controls, as Word shows them there too.
 */
internal val APP_GROUPS = listOf(Triple("Picture Format", "Arrange", "Size"), Triple("Shape Format", "Arrange", "Accessibility"))

/** [groups] of [tab] with its [APP_GROUPS] added (empty: the app draws them), each before its neighbour or last. */
internal fun withAppGroups(tab: String, groups: List<RibbonGroup>): List<RibbonGroup> {
    var out = groups
    for ((t, name, before) in APP_GROUPS) {
        if (t != tab || out.any { it.name == name }) continue
        val at = out.indexOfFirst { it.name == before }.takeIf { it >= 0 } ?: out.size
        out = out.take(at) + RibbonGroup(name, emptyList()) + out.drop(at)
    }
    return out
}

/** The selected object's wrapping (as `arrange.wrap` names it) and whether it moves with its paragraph. */
class ArrangeState(val wrap: String?, val moveWithText: Boolean)

fun parseArrangeState(e: JsonElement?): ArrangeState? {
    val o = e as? JsonObject ?: return null
    return ArrangeState(
        wrap = (o["wrap"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull,
        moveWithText = (o["moveWithText"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true,
    )
}
