package app.loki.text

import app.loki.kit.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The command whose answer fills [kind]'s pane; null for a pane drawn from state the app already has. */
internal fun paneSource(kind: PaneKind): String? = when (kind) {
    PaneKind.Styles -> "styles.list"
    PaneKind.Clipboard -> "edit.clipboardItems"
    PaneKind.Reviewing -> "review.changes"
    PaneKind.Accessibility -> "file.accessibility"
    PaneKind.StyleInspector -> "styles.inspect"
    PaneKind.Selection -> "arrange.selectionPane"
    PaneKind.Comments, PaneKind.AltText -> null
}

private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
private fun objects(e: JsonElement?): List<JsonObject> = (e as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

/** The most rows a pane lists; a long document can have thousands of changes. */
private const val MAX_PANE_ROWS = 500

private fun changeVerb(kind: String) = when (kind) {
    "insert" -> "Inserted"
    "delete" -> "Deleted"
    "format" -> "Formatted"
    "move" -> "Moved"
    else -> fieldLabel(kind.ifEmpty { "change" })
}

/** [kind]'s pane from the answer of its [paneSource]; a malformed answer is an empty pane. */
fun parsePane(kind: PaneKind, result: JsonElement?): PaneContent = when (kind) {
    PaneKind.Styles -> PaneContent(
        objects(result).take(MAX_PANE_ROWS).mapNotNull { o ->
            val id = o.text("id").ifEmpty { return@mapNotNull null }
            PaneRow(o.text("name").ifEmpty { id }, fieldLabel(o.text("kind")), tap = PaneAction("Apply", listOf(PaneStep("styles.apply", buildJsonObject { put("style", id) }))))
        },
        "This document has no styles.",
        listOf(PaneAction("New Style…", ui = "styles.create"), PaneAction("Manage Styles…", ui = "styles.manage")),
    )
    PaneKind.Clipboard -> PaneContent(
        objects(result).take(MAX_PANE_ROWS).mapNotNull { o ->
            val index = o.int("index") ?: return@mapNotNull null
            val at = buildJsonObject { put("index", index) }
            val objectsIn = o.int("objects") ?: 0
            val detail = listOfNotNull("${o.int("chars") ?: 0} characters", if (objectsIn > 0) "$objectsIn objects" else null).joinToString(" · ")
            PaneRow(
                o.text("preview").ifBlank { "(no text)" }, detail,
                tap = PaneAction("Paste", listOf(PaneStep("edit.pasteClipboardItem", at))),
                actions = listOf(PaneAction("Delete", listOf(PaneStep("edit.deleteClipboardItem", at)))),
            )
        },
        "Nothing has been copied yet.",
        listOf(PaneAction("Clear All", listOf(PaneStep("edit.clearClipboard")))),
    )
    PaneKind.Reviewing -> PaneContent(
        objects(result).take(MAX_PANE_ROWS).mapNotNull { o ->
            val start = o["start"] as? JsonObject ?: return@mapNotNull null
            val end = o["end"] as? JsonObject ?: return@mapNotNull null
            // Accept and Reject act on the change the selection is in, so each goes there first.
            val go = PaneStep("select.range", buildJsonObject { put("anchor", start); put("focus", end) })
            PaneRow(
                "${changeVerb(o.text("kind"))}: ${o.text("text").ifBlank { "(formatting)" }}",
                listOf(o.text("author"), o.text("date").take(10)).filter { it.isNotEmpty() }.joinToString(" · "),
                tap = PaneAction("Go to", listOf(go)),
                actions = listOf(PaneAction("Accept", listOf(go, PaneStep("review.accept"))), PaneAction("Reject", listOf(go, PaneStep("review.reject")))),
            )
        },
        "There are no tracked changes.",
        listOf(PaneAction("Accept All", listOf(PaneStep("review.acceptAll"))), PaneAction("Reject All", listOf(PaneStep("review.rejectAll")))),
    )
    PaneKind.Accessibility -> PaneContent(
        objects((result as? JsonObject)?.get("issues")).take(MAX_PANE_ROWS).map { o ->
            PaneRow(o.text("issue").ifEmpty { "Issue" }, listOf(fieldLabel(o.text("kind")), o.text("fix")).filter { it.isNotEmpty() }.joinToString(" · "))
        },
        "No accessibility issues found.",
    )
    PaneKind.StyleInspector -> {
        val o = result as? JsonObject
        fun level(key: String, name: String): List<PaneRow> {
            val part = o?.get(key) as? JsonObject ?: return emptyList()
            val direct = (part["direct"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            fun clear(what: String) = listOf(PaneAction("Clear", listOf(PaneStep("styles.inspectorClear", buildJsonObject { put("level", what) }))))
            return listOf(
                PaneRow("$name style", part.text("styleName").ifEmpty { "None" }, actions = clear("${key}Style")),
                PaneRow("$name formatting", direct.joinToString(", ").ifEmpty { "None" }, actions = clear("${key}Formatting")),
            )
        }
        PaneContent(level("paragraph", "Paragraph") + level("character", "Character"), "Nothing to inspect here.")
    }
    PaneKind.Selection -> PaneContent(
        objects(result).take(MAX_PANE_ROWS).mapNotNull { o ->
            val index = o.int("index") ?: return@mapNotNull null
            val hidden = (o["hidden"] as? JsonPrimitive)?.booleanOrNull == true
            PaneRow(
                o.text("name").ifEmpty { "Object ${index + 1}" }, fieldLabel(o.text("kind")) + if (hidden) " · hidden" else "",
                tap = PaneAction("Select", listOf(PaneStep("select.objects", buildJsonObject { put("index", index) }))),
            )
        },
        "This document has no pictures, shapes or text boxes.",
    )
    PaneKind.Comments, PaneKind.AltText -> PaneContent(emptyList(), "")
}

/** The Alt Text pane: what the selected object says, and the way to change it. */
internal fun altTextPane(picture: PictureState?, disabled: String?): PaneContent {
    if (disabled != null && picture == null) return PaneContent(emptyList(), "Select a picture, shape or text box to describe it.")
    val rows = listOf(
        PaneRow("Description", picture?.alt?.ifBlank { null } ?: "None"),
        PaneRow("Title", picture?.title?.ifBlank { null } ?: "None"),
        PaneRow("Decorative", if (picture?.decorative == true) "Yes" else "No"),
    )
    return PaneContent(rows, "", listOf(PaneAction("Edit…", ui = "object.altText")))
}

/**
 * The open task panes share one place: beside the page in a wide window, above it in a narrow
 * one. With several open, chips along the top switch between them, so a phone never gives the
 * whole screen to panes. [run] calls the engine; [onUi] runs a command the app's own way.
 */
@Composable
internal fun PaneHost(
    open: List<PaneKind>,
    revision: Int,
    state: CommandState,
    run: suspend (String, JsonObject) -> Result<JsonElement?>,
    onUi: (String) -> Unit,
    onClose: (PaneKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (open.isEmpty()) return
    var chosen by remember { mutableStateOf(open.last()) }
    // A pane just opened comes to the front; one just closed gives way to its neighbour.
    LaunchedEffect(open) { if (chosen !in open) chosen = open.last() }
    var known by remember { mutableStateOf(open) }
    LaunchedEffect(open) { (open - known.toSet()).lastOrNull()?.let { chosen = it }; known = open }
    val kind = if (chosen in open) chosen else open.last()
    Surface(tonalElevation = 2.dp, modifier = modifier) {
        Column {
            if (open.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in open) FilterChip(k == kind, { chosen = k }, label = { Text(k.title) })
                }
                HorizontalDivider()
            }
            if (kind == PaneKind.Comments) CommentsPane(revision, run, { onClose(kind) }, Modifier.fillMaxWidth().weight(1f))
            else DataPane(kind, revision, state, run, onUi, { onClose(kind) }, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** A pane that lists what its [paneSource] answers. */
@Composable
private fun DataPane(
    kind: PaneKind,
    revision: Int,
    state: CommandState,
    run: suspend (String, JsonObject) -> Result<JsonElement?>,
    onUi: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var fetched by remember(kind) { mutableStateOf(PaneContent(emptyList(), "")) }
    var error by remember(kind) { mutableStateOf("") }
    val source = paneSource(kind)
    suspend fun reload() {
        if (source != null) run(source, JsonObject(emptyMap())).onSuccess { fetched = parsePane(kind, it) }
    }
    // Follows the document: a short wait so a burst of typing reloads once.
    LaunchedEffect(kind, revision) { delay(150); reload() }
    val content = if (kind == PaneKind.AltText) altTextPane(state.picture, state.disabled["object.altText"]) else fetched
    fun perform(a: PaneAction) {
        a.ui?.let { onUi(it); return }
        scope.launch {
            error = ""
            for (step in a.steps) {
                val failed = run(step.command, step.params).exceptionOrNull()
                if (failed != null) { error = failed.message ?: "Failed"; break }
            }
            reload()
        }
    }
    PaneList(kind.title, content, error, ::perform, onClose, modifier)
}
