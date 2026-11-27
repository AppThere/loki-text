package com.appthere.loki.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu

// The art workspace's menus on a phone or tablet. A row of text menus is a desktop's: on a touch
// screen the menus open from one button as a sheet (from the bottom on a phone, from the side on a
// tablet), a list of the top-level menus that opens into each menu's commands, with rows a finger
// can hit and the reason a command can't run written out, since there is no hover to show it.

/** Which menu the sheet shows: null for the list of menus. */
class MenuSheetState {
    var open by mutableStateOf(false)
    var menu by mutableStateOf<String?>(null)
    fun show(menu: String? = null) { this.menu = menu; open = true }
    fun hide() { open = false; menu = null }
}

/**
 * The touch top bar: the menu button, the document's title (a tap opens File, where its actions
 * are), and the app's own buttons. It stands in for [MenuBar] on a touch platform.
 */
@Composable
fun TouchTopBar(sheet: MenuSheetState, title: String, dirty: Boolean, hasFileMenu: Boolean, leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { sheet.show() }, modifier = Modifier.size(44.dp)) { Icon(Lucide.Menu, contentDescription = "Menus", modifier = Modifier.size(20.dp)) }
        leading()
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp).clickable(enabled = hasFileMenu) { sheet.show("File") }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title + if (dirty) " •" else "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (hasFileMenu) Icon(Lucide.ChevronDown, contentDescription = "File actions", modifier = Modifier.padding(start = 2.dp).size(16.dp))
        }
        trailing()
    }
}

/**
 * The menus as a sheet: [tabs] as `parseRibbon` makes them. On a phone ([compact]) it slides up
 * from the bottom; on a tablet it is a panel at the left side over a scrim. A command run from it
 * closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    sheet: MenuSheetState,
    tabs: List<RibbonTab>,
    disabled: Map<String, String>,
    on: Set<String>,
    hasIcon: (String) -> Boolean,
    icon: @Composable (String) -> Unit,
    onRun: (String) -> Unit,
    compact: Boolean,
) {
    val content: @Composable () -> Unit = {
        MenuSheetContent(sheet, tabs, disabled, on, hasIcon, icon) { id -> sheet.hide(); onRun(id) }
    }
    if (compact) {
        if (sheet.open) {
            val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            ModalBottomSheet(onDismissRequest = { sheet.hide() }, sheetState = state) { content() }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(sheet.open, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sheet.hide() })
            }
            AnimatedVisibility(sheet.open, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
                Surface(Modifier.width(360.dp).fillMaxHeight(), tonalElevation = 3.dp, shadowElevation = 8.dp) { content() }
            }
        }
    }
}

@Composable
private fun MenuSheetContent(
    sheet: MenuSheetState,
    tabs: List<RibbonTab>,
    disabled: Map<String, String>,
    on: Set<String>,
    hasIcon: (String) -> Boolean,
    icon: @Composable (String) -> Unit,
    onRun: (String) -> Unit,
) {
    val tab = tabs.firstOrNull { it.name == sheet.menu }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab != null) IconButton(onClick = { sheet.menu = null }, modifier = Modifier.size(44.dp)) { Icon(Lucide.ArrowLeft, contentDescription = "All menus", modifier = Modifier.size(20.dp)) }
            Text(tab?.name ?: "Menus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
        }
        HorizontalDivider()
        if (tab == null) {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(tabs) { t ->
                    val count = t.groups.sumOf { it.items.size }
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { sheet.menu = t.name }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                tab.groups.forEachIndexed { gi, g ->
                    if (g.name.isNotEmpty() && g.name != tab.name && tab.groups.size > 1) {
                        item(key = "g$gi") {
                            Text(g.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                        }
                    }
                    items(g.items, key = { "$gi:${it.id}" }) { item ->
                        val why = disabled[item.id]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = why == null) { onRun(item.id) }.padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(32.dp), contentAlignment = Alignment.CenterStart) {
                                if (item.id in on) Text("✓", style = MaterialTheme.typography.bodyLarge) else if (hasIcon(item.id)) icon(item.id)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(item.menuLabel, style = MaterialTheme.typography.bodyLarge, color = if (why == null) Color.Unspecified else MaterialTheme.colorScheme.outline)
                                // No hover on a touch screen: why a command can't run is written under it.
                                if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (gi < tab.groups.lastIndex) item(key = "d$gi") { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                }
            }
        }
    }
}
