@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.appthere.loki.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appthere.loki.text.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A template a new document can start from: one the engine carries, or a `.dotx`/`.dotm` file. */
internal sealed interface TemplateChoice {
    val label: String

    /** A built-in template, by the id `file.new` takes. */
    data class Builtin(val id: String, override val label: String) : TemplateChoice

    /** A template file: a personal one from the templates folder, or one the user browsed to. */
    data class File(val path: String, override val label: String, val category: String = "") : TemplateChoice
}

/** The built-in templates in the gallery's order (the same list, labels and ids as the desktop front end's). */
internal val BUILTIN_TEMPLATES: List<TemplateChoice.Builtin> = listOf(
    TemplateChoice.Builtin("blank", "Blank document"),
    TemplateChoice.Builtin("sample", "Studio handbook (sample)"),
    TemplateChoice.Builtin("letter", "Letter"),
    TemplateChoice.Builtin("resume", "Résumé"),
    TemplateChoice.Builtin("report", "Report"),
)

/** The `file.new` / `file.newFromTemplate` call that makes a document from [t]. */
internal fun TemplateChoice.command(): Pair<String, JsonObject> = when (this) {
    is TemplateChoice.Builtin -> "file.new" to buildJsonObject { put("template", id) }
    is TemplateChoice.File -> "file.newFromTemplate" to buildJsonObject { put("path", path); put("replaceBlank", true) }
}

/**
 * A window opened for a template is told so by its "path": this prefix and then a built-in template's id,
 * or `file:` and a template file's path. (A window takes a path to open; this is how it is asked for a template instead.)
 */
internal const val TEMPLATE_REQUEST = "template:"

internal fun TemplateChoice.asRequest(): String = TEMPLATE_REQUEST + when (this) {
    is TemplateChoice.Builtin -> id
    is TemplateChoice.File -> "file:$path"
}

/** The template a window's start request names, or null when [request] is an ordinary path (or nothing). */
internal fun templateOfRequest(request: String?): TemplateChoice? {
    val rest = request?.takeIf { it.startsWith(TEMPLATE_REQUEST) }?.removePrefix(TEMPLATE_REQUEST) ?: return null
    if (rest.startsWith("file:")) {
        val path = rest.removePrefix("file:")
        return TemplateChoice.File(path, path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
    }
    return BUILTIN_TEMPLATES.firstOrNull { it.id == rest }
}

/** The personal templates `file.listTemplates` reports, in its order (by category, then name); entries without a path are left out. */
internal fun parsePersonalTemplates(result: JsonElement?): List<TemplateChoice.File> =
    ((result as? JsonObject)?.get("templates") as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        val path = str("path") ?: return@mapNotNull null
        TemplateChoice.File(path, str("title") ?: str("label") ?: path.substringAfterLast('/'), str("category").orEmpty())
    }

/** The templates whose label or category contains [query], ignoring case; all of them for a blank query. */
internal fun <T : TemplateChoice> filterTemplates(all: List<T>, query: String): List<T> {
    val q = query.trim().lowercase()
    return if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) || ((it as? TemplateChoice.File)?.category?.lowercase()?.contains(q) == true) }
}

/** First-page pictures of the built-in templates, drawn once per run in a session of their own so no open document is touched. */
internal object TemplateThumbnails {
    private val pictures = HashMap<String, ImageBitmap>()
    private val lock = Mutex()

    /** The picture of each built-in template that could be drawn; templates that fail are simply missing. */
    suspend fun load(): Map<String, ImageBitmap> = withContext(Dispatchers.Default) {
        if (pictures.size >= BUILTIN_TEMPLATES.size) return@withContext pictures.toMap()
        runCatching {
            val engine = Engine()
            try {
                installDocumentFonts(engine, lock, force = true)
                for (t in BUILTIN_TEMPLATES) {
                    if (t.id in pictures) continue
                    runCatching {
                        engine.execute("file.new", buildJsonObject { put("template", t.id) })
                        engine.renderPage(0, 0.4f)?.let { pictures[t.id] = rgbaToImageBitmap(it.width, it.height, it.rgba) }
                    }.onFailure { println("template ${t.id} not drawn: ${it.message}") }
                }
            } finally {
                engine.close()
            }
        }.onFailure { println("template thumbnails not drawn: ${it.message}") }
        pictures.toMap()
    }
}

/**
 * The templates to start a document from: the built-in ones with a picture of their first page, the user's
 * personal templates (by category), and a way to browse to any other template file. Used on the start
 * screen (inline) and from File › New from Template (in a dialog).
 */
@Composable
internal fun TemplateGallery(
    personal: List<TemplateChoice.File>,
    onChoose: (TemplateChoice) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
    /** The built-in templates to show; the start screen shows a few and leaves the rest to the full gallery. */
    limit: Int = BUILTIN_TEMPLATES.size,
    onMore: (() -> Unit)? = null,
    query: String = "",
) {
    var thumbs by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    // The first launch draws these while the main session installs the document fonts; a template that missed is tried again.
    LaunchedEffect(Unit) {
        repeat(4) {
            thumbs = TemplateThumbnails.load()
            if (thumbs.size >= BUILTIN_TEMPLATES.size) return@LaunchedEffect
            kotlinx.coroutines.delay(1500)
        }
    }
    val builtin = filterTemplates(BUILTIN_TEMPLATES, query).take(limit)
    val mine = filterTemplates(personal, query)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (t in builtin) TemplateCard(t.label, thumbs[t.id]) { onChoose(t) }
        }
        if (builtin.isEmpty() && mine.isEmpty()) {
            Text("No templates match “${query.trim()}”.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBrowse) { Text("Browse for a template…") }
            if (onMore != null) TextButton(onClick = onMore) { Text("More templates") }
        }
        if (mine.isNotEmpty() && limit >= BUILTIN_TEMPLATES.size) {
            Text("Personal", style = MaterialTheme.typography.titleSmall)
            for ((category, group) in mine.groupBy { it.category }) {
                if (category.isNotEmpty()) Text(category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (t in group) TemplateCard(t.label, null) { onChoose(t) }
                }
            }
        }
    }
}

/** One template: its first page (or a blank sheet while it is drawn, or for a file template), and its name. */
@Composable
private fun TemplateCard(label: String, picture: ImageBitmap?, onClick: () -> Unit) {
    Column(
        Modifier.width(120.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(4.dp)
            .semantics { contentDescription = "New document from the $label template" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(17f / 22f).background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            if (picture != null) Image(picture, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** File › New from Template: the whole gallery with a search box, over the document. */
@Composable
internal fun TemplateGalleryDialog(
    personal: List<TemplateChoice.File>,
    onChoose: (TemplateChoice) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New from template") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search templates") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                TemplateGallery(personal, onChoose, onBrowse, query = query)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
