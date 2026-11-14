package app.loki.text

import app.loki.kit.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A heading from `nav.headings`: where it is (a `Pos` to hand back to `caret.set`), its level (1 for Heading 1) and text. */
internal class NavHeading(val pos: JsonObject, val level: Int, val text: String)

/** The document's headings and the index of the one the caret is under. */
internal class NavHeadings(val items: List<NavHeading>, val current: Int?) {
    companion object { val EMPTY = NavHeadings(emptyList(), null) }
}

private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

internal fun parseNavHeadings(result: JsonElement?): NavHeadings {
    val o = result as? JsonObject ?: return NavHeadings.EMPTY
    val items = (o["headings"] as? JsonArray).orEmpty().mapNotNull { e ->
        val h = e as? JsonObject ?: return@mapNotNull null
        NavHeading(
            pos = h["pos"] as? JsonObject ?: return@mapNotNull null,
            level = (h["level"].int() ?: 1).coerceIn(1, 9),
            text = (h["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
        )
    }
    return NavHeadings(items, o["current"].int()?.takeIf { it in items.indices })
}

/** A heading has children when the next heading is deeper. */
internal fun hasChildren(items: List<NavHeading>): List<Boolean> = items.mapIndexed { i, h -> items.getOrNull(i + 1)?.let { it.level > h.level } == true }

/**
 * Which headings show: those under a collapsed heading (every following heading of a deeper
 * level, up to the next one at the same level or higher) are hidden.
 */
internal fun visibleHeadings(levels: List<Int>, collapsed: List<Boolean>): List<Boolean> {
    val shown = MutableList(levels.size) { true }
    var hideBelow: Int? = null
    for (i in levels.indices) {
        val limit = hideBelow
        if (limit != null && levels[i] > limit) { shown[i] = false; continue }
        hideBelow = null
        if (collapsed.getOrElse(i) { false }) hideBelow = levels[i]
    }
    return shown
}

/** A search result with the text around the match. */
internal class NavResult(val start: JsonObject, val end: JsonObject, val before: String, val hit: String, val after: String)

internal class NavResults(val count: Int, val items: List<NavResult>)

internal fun parseNavResults(result: JsonElement?): NavResults {
    val o = result as? JsonObject ?: return NavResults(0, emptyList())
    val items = (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
        val r = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (r[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        NavResult(r["start"] as? JsonObject ?: return@mapNotNull null, r["end"] as? JsonObject ?: return@mapNotNull null, s("before"), s("hit"), s("after"))
    }
    return NavResults(o["count"].int() ?: items.size, items)
}

private enum class NavTab(val label: String) { Headings("Headings"), Pages("Pages"), Results("Results") }

/**
 * The Navigation pane: the heading outline (collapsible, current heading marked), the page list
 * and search results with context. [run] calls an engine command; [revision] changes with every
 * edit so the outline stays current. [pages] and [currentPage] (1-based, or null) come from the app.
 */
@Composable
internal fun NavPane(
    revision: Int,
    pages: Int,
    currentPage: Int?,
    run: suspend (String, JsonObject) -> Result<JsonElement?>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(NavTab.Headings) }
    var query by remember { mutableStateOf("") }
    var headings by remember { mutableStateOf(NavHeadings.EMPTY) }
    var results by remember { mutableStateOf(NavResults(0, emptyList())) }
    // Collapsed headings are remembered by their text and level, which survive edits that move them.
    var collapsed by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(revision) {
        delay(200)
        run("nav.headings", JsonObject(emptyMap())).onSuccess { headings = parseNavHeadings(it) }
    }
    LaunchedEffect(query, revision) {
        if (query.isEmpty()) { results = NavResults(0, emptyList()); return@LaunchedEffect }
        delay(250)
        run("nav.search", buildJsonObject { put("text", query) }).onSuccess { results = parseNavResults(it) }.onFailure { results = NavResults(0, emptyList()) }
    }

    Surface(tonalElevation = 2.dp, modifier = modifier) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it; if (it.isNotEmpty()) tab = NavTab.Results }, singleLine = true,
                    placeholder = { Text("Search document") }, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Lucide.X, contentDescription = "Close navigation pane", modifier = Modifier.size(18.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in NavTab.entries) FilterChip(tab == t, { tab = t }, label = { Text(t.label) })
            }
            HorizontalDivider()
            when (tab) {
                NavTab.Headings -> {
                    val items = headings.items
                    if (items.isEmpty()) {
                        Text("Apply Heading styles to see an outline of your document here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else {
                        val children = hasChildren(items)
                        val keys = items.map { "${it.level}:${it.text}" }
                        val flags = items.indices.map { children[it] && keys[it] in collapsed }
                        val shown = visibleHeadings(items.map { it.level }, flags)
                        LazyColumn {
                            itemsIndexed(items) { i, h ->
                                if (!shown[i]) return@itemsIndexed
                                val on = headings.current == i
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable { scope.launch { run("caret.set", buildJsonObject { put("pos", h.pos) }) } }
                                        .padding(start = ((h.level - 1) * 14).dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (children[i]) {
                                        IconButton(onClick = { collapsed = if (keys[i] in collapsed) collapsed - keys[i] else collapsed + keys[i] }, modifier = Modifier.size(24.dp)) {
                                            Icon(if (flags[i]) Lucide.ChevronRight else Lucide.ChevronDown, contentDescription = if (flags[i]) "Expand" else "Collapse", modifier = Modifier.size(16.dp))
                                        }
                                    } else {
                                        Box(Modifier.size(24.dp))
                                    }
                                    Text(h.text, style = MaterialTheme.typography.bodyMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                NavTab.Pages -> LazyColumn {
                    itemsIndexed(List(pages.coerceIn(0, 5000)) { it + 1 }) { _, n ->
                        Text(
                            "Page $n",
                            fontWeight = if (n == currentPage) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth()
                                .background(if (n == currentPage) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { scope.launch { run("edit.goto", buildJsonObject { put("page", n) }) } }.padding(8.dp),
                        )
                    }
                }
                NavTab.Results -> {
                    Text(
                        when { query.isEmpty() -> "Type to search"; results.count == 1 -> "1 result"; else -> "${results.count} results" },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    )
                    LazyColumn {
                        itemsIndexed(results.items) { _, r ->
                            Text(
                                buildAnnotatedString {
                                    append(r.before)
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = androidx.compose.ui.graphics.Color(0x66FFD54F))) { append(r.hit) }
                                    append(r.after)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { scope.launch { run("select.range", buildJsonObject { put("anchor", r.start); put("focus", r.end) }) } }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
