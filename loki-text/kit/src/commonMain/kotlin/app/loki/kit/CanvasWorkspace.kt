package app.loki.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronsRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

// The window of an art app (a paint, photo or vector editor): a text menu bar, an options bar, a
// column of tool icons, the canvas under document tabs, a dock of panels, and a status bar. The
// ribbon (CommandBar) suits document apps; this is the other chrome the Craft front ends use. Like
// everything in the kit it knows no engine: an app hands it the menus, tools, panels and canvas.

/** Below this width the window is a phone's: the dock opens over the canvas and tools go below it. */
val COMPACT_WIDTH: Dp = 600.dp

/** Width of the dock's panel column on a wide window. */
val DOCK_WIDTH: Dp = 300.dp

/**
 * A menu bar from the engine's top-level menus ([tabs], as `parseRibbon` makes them): each name opens
 * its commands, grouped by the menu's sections, with icons, shortcuts, ticks and the reason a command
 * can't run. [leading] and [trailing] hold the app's own buttons. Scrolls sideways in a narrow window.
 */
@Composable
fun MenuBar(
    tabs: List<RibbonTab>,
    disabled: Map<String, String>,
    on: Set<String>,
    hasIcon: (id: String) -> Boolean,
    icon: @Composable (id: String) -> Unit,
    onRun: (String) -> Unit,
    leading: @Composable () -> Unit = {},
    title: String = "",
    trailing: @Composable () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= COMPACT_WIDTH
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                for (t in tabs) TopMenu(t, disabled, on, hasIcon, icon, onRun)
                if (wide && title.isNotEmpty()) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            trailing()
        }
    }
}

@Composable
private fun TopMenu(tab: RibbonTab, disabled: Map<String, String>, on: Set<String>, hasIcon: (String) -> Boolean, icon: @Composable (String) -> Unit, onRun: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            tab.name, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.background(if (open) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.widthIn(min = 260.dp, max = 420.dp)) {
            tab.groups.forEachIndexed { gi, g ->
                if (gi > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                if (g.name.isNotEmpty() && g.name != tab.name && tab.groups.size > 1) {
                    Text(g.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
                for (item in g.items) MenuRow(item, disabled[item.id], item.id in on, hasIcon(item.id), icon) { open = false; onRun(item.id) }
            }
        }
    }
}

@Composable
private fun MenuRow(item: RibbonItem, disabledReason: String?, checked: Boolean, hasIcon: Boolean, icon: @Composable (String) -> Unit, onClick: () -> Unit) {
    val off = disabledReason != null
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !off, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.CenterStart) {
            if (checked) Text("✓", style = MaterialTheme.typography.bodyMedium) else if (hasIcon) icon(item.id)
        }
        Text(item.menuLabel, style = MaterialTheme.typography.bodyMedium, color = if (off) MaterialTheme.colorScheme.outline else Color.Unspecified,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (item.shortcut.isNotEmpty()) {
            Text(displayShortcut(item.shortcut), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 24.dp))
        }
    }
}

/** A tool of the rail; [alternates] share its slot (a group, as in the desktop's tool palette). */
class RailTool(val id: String, val label: String, val shortcut: String = "", val icon: ImageVector? = null, val alternates: List<RailTool> = emptyList())

/**
 * The tool palette: an icon per tool group, the tool in hand drawn on. A tap picks the group's
 * shown tool; a long press or a right click opens the group's other tools. A column on a wide
 * window, a scrolling row on a narrow one. [footer] goes at the end (colour swatches, say).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ToolRail(tools: List<RailTool>, current: String, vertical: Boolean, onPick: (RailTool) -> Unit, modifier: Modifier = Modifier, footer: @Composable () -> Unit = {}) {
    // Which alternate each group shows: the one picked last.
    var shown by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val content: @Composable () -> Unit = {
        for (group in tools) {
            val members = listOf(group) + group.alternates
            val tool = members.firstOrNull { it.id == current } ?: members.firstOrNull { it.id == shown[group.id] } ?: group
            var menu by remember { mutableStateOf(false) }
            val inHand = members.any { it.id == current }
            Box {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(if (tool.shortcut.isEmpty()) tool.label else "${tool.label}  (${tool.shortcut})") } },
                    state = rememberTooltipState(),
                ) {
                    Box(
                        Modifier.padding(2.dp).size(if (isTouchPlatform) 44.dp else 36.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (inHand) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .combinedClickable(onClick = { onPick(tool) }, onLongClick = { if (group.alternates.isNotEmpty()) menu = true })
                            .onSecondaryClick { if (group.alternates.isNotEmpty()) menu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (tool.icon != null) Icon(tool.icon, contentDescription = tool.label, modifier = Modifier.size(20.dp))
                        else Text(tool.label.take(2), style = MaterialTheme.typography.labelMedium)
                        // A group with other tools has a mark in its corner, as on the desktop.
                        if (group.alternates.isNotEmpty()) Text("◢", style = MaterialTheme.typography.labelSmall.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.6f),
                            color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (m in members) Row(
                        Modifier.clickable { menu = false; shown = shown + (group.id to m.id); onPick(m) }.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(min = 220.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.CenterStart) { m.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } }
                        Text(m.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (m.id == current) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (m.shortcut.isNotEmpty()) Text(m.shortcut, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
    // The footer (an art app's colours) stays put while the tools scroll: it is never scrolled out of reach.
    if (vertical) Column(modifier.width(if (isTouchPlatform) 56.dp else 48.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        HorizontalDivider()
        footer()
    } else Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        footer()
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** A right click, where there is a mouse; nothing on touch, where the long press does the same. */
fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) action()
        }
    }
}

/** A panel of the dock: its name, its icon on the strip, and what it draws. */
class DockPanel(val id: String, val title: String, val icon: ImageVector?, val content: @Composable (Modifier) -> Unit)

/**
 * The dock: a strip of panel icons and the open panel, under tabs for the panels in its group. A
 * tap on an icon opens that panel, a tap on the open one closes the dock. [groups] lists which
 * panels share a column (Properties with Adjustments, Layers with Channels…), each group's tabs
 * shown together; a panel in no group is its own.
 */
@Composable
fun PanelDock(panels: List<DockPanel>, open: String?, onOpen: (String?) -> Unit, modifier: Modifier = Modifier, groups: List<List<String>> = emptyList(), showStrip: Boolean = true) {
    val byId = panels.associateBy { it.id }
    val active = open?.let { byId[it] }
    Row(modifier) {
        if (showStrip) {
            Column(Modifier.width(44.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (p in panels) StripButton(p, p.id == open) { onOpen(if (p.id == open) null else p.id) }
            }
            VerticalDivider()
        }
        if (active != null) {
            val group = groups.firstOrNull { active.id in it }?.mapNotNull { byId[it] } ?: listOf(active)
            Column(Modifier.width(DOCK_WIDTH).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        for (p in group) Text(
                            p.title, style = MaterialTheme.typography.labelLarge, fontWeight = if (p.id == active.id) FontWeight.Bold else FontWeight.Normal,
                            color = if (p.id == active.id) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clickable { onOpen(p.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    IconButton(onClick = { onOpen(null) }, modifier = Modifier.size(32.dp)) { Icon(Lucide.ChevronsRight, contentDescription = "Collapse the panels", modifier = Modifier.size(16.dp)) }
                }
                HorizontalDivider()
                active.content(Modifier.fillMaxSize())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StripButton(p: DockPanel, selected: Boolean, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(p.title) } }, state = rememberTooltipState()) {
        Box(
            Modifier.padding(2.dp).size(36.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (p.icon != null) Icon(p.icon, contentDescription = p.title, modifier = Modifier.size(18.dp)) else Text(p.title.take(2), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The tab of the open document over the canvas: its name and whether it has unsaved changes. */
@Composable
fun DocumentTab(title: String, dirty: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(32.dp).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.fillMaxHeight().border(width = 0.dp, color = Color.Transparent).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title + if (dirty) " •" else "", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The status bar: short facts about the document ([segments]) and the last message, an error in the error colour. */
@Composable
fun StatusBar(segments: List<String>, message: String, isError: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (s in segments) Text(s, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (message.isNotEmpty()) Text(message, modifier = Modifier.announces(), style = MaterialTheme.typography.labelMedium, maxLines = 1, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The art app's window. Wide: menu bar, options bar, then tools | document tab over the canvas |
 * dock, then the status bar. Compact (a phone): the same pieces, moved, never removed: the open
 * panel slides over the lower half of the canvas, only once a panel is tapped (the canvas comes
 * first on a phone), and the tools and the dock's strip share a scrolling row under it.
 */
@Composable
fun CanvasWorkspace(
    menuBar: @Composable () -> Unit,
    optionsBar: @Composable () -> Unit,
    tools: @Composable (vertical: Boolean) -> Unit,
    document: @Composable () -> Unit,
    canvas: @Composable (Modifier) -> Unit,
    panels: List<DockPanel>,
    openPanel: String?,
    onOpenPanel: (String?) -> Unit,
    statusBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    panelGroups: List<List<String>> = emptyList(),
    /** Drawn over everything: a menu sheet, say. Gets whether the window is a phone's. */
    overlay: @Composable (compact: Boolean) -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        val compact = maxWidth < COMPACT_WIDTH
        // On a phone the panel the app opens at the start stays closed until one is tapped.
        var sheetShown by remember { mutableStateOf(false) }
        val sheetMax = maxHeight * 0.55f
        val bars = MaterialTheme.colorScheme.surface
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.background(bars)) { menuBar() }
            HorizontalDivider()
            Box(Modifier.background(bars)) { optionsBar() }
            HorizontalDivider()
            if (!compact) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.background(bars)) { tools(true) }
                    VerticalDivider()
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        document()
                        canvas(Modifier.weight(1f).fillMaxWidth())
                    }
                    VerticalDivider()
                    PanelDock(panels, openPanel, onOpenPanel, Modifier.fillMaxHeight().background(bars), panelGroups)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize()) {
                        document()
                        canvas(Modifier.weight(1f).fillMaxWidth())
                    }
                    val active = panels.firstOrNull { it.id == openPanel }?.takeIf { sheetShown }
                    if (active != null) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = sheetMax).background(bars)) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(active.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                                IconButton(onClick = { sheetShown = false; onOpenPanel(null) }, modifier = Modifier.size(32.dp)) { Icon(Lucide.X, contentDescription = "Close ${active.title}", modifier = Modifier.size(16.dp)) }
                            }
                            active.content(Modifier.fillMaxWidth().heightIn(min = 160.dp))
                        }
                    }
                }
                HorizontalDivider()
                // Two rows, not one: a panel strip of nine buttons is as wide as a phone, and beside it the
                // tools had no room at all (a phone showed no tools). Tools first, they are used most.
                Box(Modifier.fillMaxWidth().background(bars)) { tools(false) }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().background(bars).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    for (p in panels) StripButton(p, sheetShown && p.id == openPanel) {
                        val closing = sheetShown && p.id == openPanel
                        sheetShown = !closing
                        onOpenPanel(if (closing) null else p.id)
                    }
                }
            }
            HorizontalDivider()
            Box(Modifier.background(bars)) { statusBar() }
        }
        overlay(compact)
    }
}
