package app.loki.text

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.composables.icons.lucide.Menu
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
class CommandState(val disabled: Map<String, String>, val on: Set<String>) {
    companion object {
        val EMPTY = CommandState(emptyMap(), emptySet())
    }
}

fun parseCommandState(state: JsonElement): CommandState {
    val o = state as? JsonObject ?: return CommandState.EMPTY
    val disabled = (o["disabled"] as? JsonObject).orEmpty().mapNotNull { (id, why) -> why.jsonPrimitive.contentOrNull?.let { id to it } }.toMap()
    val on = (o["on"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    return CommandState(disabled, on)
}

/** One ribbon button: a WordCraft command that runs with no parameters. */
class RibbonItem(val id: String, val label: String, val shortcut: String)

class RibbonGroup(val name: String, val items: List<RibbonItem>)

class RibbonTab(val name: String, val groups: List<RibbonGroup>)

/**
 * The main tabs, in ribbon order. Contextual tabs (Table Layout, Picture Format, …) only make
 * sense with something selected, so they are left out until the app knows the selection's kind.
 */
private val TAB_ORDER = listOf("Home", "Insert", "Draw", "Design", "Layout", "References", "Mailings", "Review", "View", "Developer")

/**
 * Can a command run from a plain button? Its params must be empty or all optional. A command that
 * needs a value (a font size, a colour) or has alternative forms needs a control that supplies it.
 */
internal fun runsWithoutParams(params: String): Boolean {
    val p = params.trim()
    if (p.isEmpty() || p == "{}") return true
    if (!p.startsWith("{") || "} |" in p || "| {" in p) return false
    // A required key reads `"name":`; an optional one is `"name"?:`.
    return !Regex("\"\\w+\":").containsMatchIn(p)
}

/** Builds the ribbon from `engine.commands`: `location` is `Tab › Group[ › …]`. */
fun parseRibbon(commands: JsonElement): List<RibbonTab> {
    val byTab = LinkedHashMap<String, LinkedHashMap<String, MutableList<RibbonItem>>>()
    for (c in (commands as? JsonArray).orEmpty()) {
        val o = c as? JsonObject ?: continue
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
        val id = str("id")
        // `ui.*` commands act on a window that this front end does not have yet.
        if (id.isEmpty() || id.startsWith("ui.") || id in NEEDS_INPUT || !runsWithoutParams(str("params"))) continue
        val parts = str("location").split(" › ")
        val tab = parts.firstOrNull()?.takeIf { it in TAB_ORDER } ?: continue
        val group = parts.getOrNull(1) ?: ""
        byTab.getOrPut(tab) { LinkedHashMap() }.getOrPut(group) { mutableListOf() }.add(RibbonItem(id, str("label").ifEmpty { id }, str("shortcut")))
    }
    return TAB_ORDER.mapNotNull { name ->
        byTab[name]?.let { groups -> RibbonTab(name, groups.map { (g, items) -> RibbonGroup(g, items) }) }
    }
}

/**
 * A shortcut as a person reads it. The engine lists every binding (`Mod+O / Mod+F12 / …`); a menu
 * has room for the first, and `Mod` is the platform's command key, shown as Ctrl.
 */
internal fun displayShortcut(raw: String): String = raw.substringBefore(" / ").trim().replace("Mod+", "Ctrl+")

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
private fun StyleChip(style: StylePreview, onClick: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    // A dark style colour would vanish on a dark theme; use the theme's text colour there.
    val color = hexColor(style.colorHex)?.takeIf { abs(it.luminance() - surface.luminance()) > 0.3f } ?: MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color.White,
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

/** The ribbon's tab bar: white labels on [BarBlue], a white bar under the selected tab. */
@Composable
private fun RibbonTabRow(tabs: List<RibbonTab>, selectedIndex: Int, onSelect: (Int) -> Unit) {
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
            Tab(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                selectedContentColor = Color.White,
                unselectedContentColor = Color.White.copy(alpha = 0.78f),
                text = { Text(t.name, style = MaterialTheme.typography.labelLarge, fontWeight = if (i == selectedIndex) FontWeight.Bold else FontWeight.Medium) },
            )
        }
    }
}

/** [CommandIconButton] for a ribbon item, with its state from [state]. */
@Composable
private fun StatefulIconButton(item: RibbonItem, state: CommandState, onRun: (String) -> Unit) =
    CommandIconButton(item.id, item.label, item.shortcut, state.disabled[item.id], item.id in state.on) { onRun(item.id) }

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
private fun CommandMenu(items: List<RibbonItem>, state: CommandState, onRun: (String) -> Unit, button: @Composable (onClick: () -> Unit) -> Unit) {
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
                        (if (item.id in state.on) "✓ " else "") + item.label,
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
) {
    if (tabs.isEmpty()) return
    var selected by remember { mutableStateOf(0) }
    val tab = tabs[selected.coerceIn(0, tabs.lastIndex)]
    if (sizeClass != SizeClass.Expanded) {
        SimpleRibbon(tabs, tab, { selected = it }, styles, onApplyStyle, onRun, state, leading)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        // The row's own divider stops at the last tab; a divider of our own spans the whole width.
        RibbonTabRow(tabs, tabs.indexOf(tab)) { selected = it }
        HorizontalDivider()
        ScrollRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            tab.groups.forEachIndexed { gi, g ->
                // A rule between neighbouring groups, as tall as a group's buttons plus its name.
                if (gi > 0) VerticalDivider(Modifier.height(GROUP_CONTENT_HEIGHT + GROUP_NAME_HEIGHT), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                val featured = g.items.filter { hasRibbonIcon(it.id) }
                val rest = g.items - featured.toSet()
                Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Every group's content gets the same height, so the names below line up.
                    Box(Modifier.height(GROUP_CONTENT_HEIGHT), contentAlignment = Alignment.TopCenter) {
                        if (tab.name == "Home" && g.name == "Styles" && styles.isNotEmpty()) {
                            // The gallery: each style drawn as it looks. The rest of the group is a menu.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // A grid that scrolls vertically, so every style is reachable.
                                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.width(80.dp * 4).height(GROUP_CONTENT_HEIGHT)) {
                                    items(styles) { st -> StyleChip(st) { onApplyStyle(st.id) } }
                                }
                                CommandMenu(g.items, state, onRun) { openMenu -> MoreButton(openMenu) }
                            }
                        } else if (featured.isEmpty() && g.items.size == 1) {
                            // A menu of one is pointless: the group is just that command's button.
                            val only = g.items.single()
                            TextButton(onClick = { onRun(only.id) }, enabled = only.id !in state.disabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text(only.label, style = MaterialTheme.typography.labelLarge)
                            }
                        } else if (featured.isEmpty()) {
                            // Nothing here has an icon yet: one button opens the group's commands by name.
                            CommandMenu(g.items, state, onRun) { openMenu -> MoreButton(openMenu) }
                        } else {
                            // Fill all three rows of the group before widening it.
                            val perRow = ((featured.size + (if (rest.isNotEmpty()) 1 else 0) + 2) / 3).coerceAtLeast(1)
                            FlowRow(Modifier.widthIn(max = 32.dp * perRow + 16.dp), maxItemsInEachRow = perRow) {
                                for (item in featured) StatefulIconButton(item, state, onRun)
                                if (rest.isNotEmpty()) CommandMenu(rest, state, onRun) { openMenu -> MoreButton(openMenu) }
                            }
                        }
                    }
                    Text(g.name.ifEmpty { tab.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                }
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
private fun StylesButton(styles: List<StylePreview>, onApplyStyle: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Styles", style = MaterialTheme.typography.labelLarge) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.width(80.dp * 3 + 8.dp).height(240.dp)) {
                items(styles) { st -> StyleChip(st) { open = false; onApplyStyle(st.id) } }
            }
        }
    }
}

/**
 * The ribbon for small windows: the tabs, and under them one row of icons instead of grouped
 * panels, showing the selected tab's main commands. The row scrolls, and everything else is in the
 * all-commands list, so no feature is out of reach.
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
) {
    var showAll by remember { mutableStateOf(false) }
    val shown = tab
    Column(Modifier.fillMaxWidth()) {
        RibbonTabRow(tabs, tabs.indexOf(tab), onSelect)
        HorizontalDivider()
        ScrollRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            leading()
            VerticalRule()
            IconButton(onClick = { showAll = true }, modifier = Modifier.size(32.dp)) { Icon(Lucide.Menu, contentDescription = "All commands", modifier = Modifier.size(18.dp)) }
            if (shown.name == "Home" && styles.isNotEmpty()) StylesButton(styles, onApplyStyle)
            for (g in shown.groups) {
                val featured = g.items.filter { hasRibbonIcon(it.id) }
                if (featured.isEmpty()) continue
                VerticalRule()
                for (item in featured) StatefulIconButton(item, state, onRun)
            }
        }
        HorizontalDivider()
    }
    if (showAll) AllCommandsDialog(tabs, tabs.indexOf(tab), state, onRun) { showAll = false }
}

/** One line of the all-commands list: a group heading (item null) or a command. */
private class CommandRow(val heading: String?, val item: RibbonItem?)

/** Every ribbon command, by tab and group, with a search box. */
@Composable
private fun AllCommandsDialog(tabs: List<RibbonTab>, initial: Int, state: CommandState, onRun: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var tabIx by remember { mutableStateOf(initial.coerceIn(0, tabs.lastIndex)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f), shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search commands") }, modifier = Modifier.weight(1f).padding(8.dp))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                if (query.isBlank()) {
                    ScrollableTabRow(selectedTabIndex = tabIx, edgePadding = 0.dp, divider = {}) {
                        tabs.forEachIndexed { i, t -> Tab(selected = i == tabIx, onClick = { tabIx = i }, text = { Text(t.name, style = MaterialTheme.typography.labelLarge) }) }
                    }
                }
                HorizontalDivider()
                val rows = remember(query, tabIx, tabs) {
                    val out = ArrayList<CommandRow>()
                    val q = query.trim()
                    for ((ti, t) in tabs.withIndex()) {
                        if (q.isEmpty() && ti != tabIx) continue
                        for (g in t.groups) {
                            val hits = if (q.isEmpty()) g.items else g.items.filter { it.label.contains(q, ignoreCase = true) }
                            if (hits.isEmpty()) continue
                            out += CommandRow(if (q.isEmpty()) g.name.ifEmpty { t.name } else "${t.name} › ${g.name.ifEmpty { t.name }}", null)
                            hits.mapTo(out) { CommandRow(null, it) }
                        }
                    }
                    out
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(rows) { r ->
                        val item = r.item
                        if (item == null) {
                            Text(r.heading.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
                        } else {
                            val why = state.disabled[item.id]
                            Row(Modifier.fillMaxWidth().clickable(enabled = why == null) { onDismiss(); onRun(item.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.CenterStart) { if (hasRibbonIcon(item.id)) RibbonIcon(item.id) }
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, style = MaterialTheme.typography.bodyMedium, color = if (why == null) Color.Unspecified else MaterialTheme.colorScheme.outline)
                                    if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                if (item.shortcut.isNotEmpty()) Text(displayShortcut(item.shortcut), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
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
