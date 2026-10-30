package app.loki.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import app.loki.text.engine.SelectionGeometry
import androidx.compose.ui.unit.dp
import app.loki.text.engine.Engine
import app.loki.text.engine.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Turns straight-alpha RGBA into a Compose image; per platform (Skia). */
expect fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap

private const val MAX_PAGES = 50

/** Pixels per point the pages are rendered at; the displayed factor can be smaller. */
private const val PAGE_SCALE = 1.5f

/** Moves the caret to a pixel position on a rendered page; the engine takes points. */
private fun Engine.setCaret(page: Int, o: Offset, pxPerPt: Float, extend: Boolean) {
    execute("caret.set", buildJsonObject {
        put("page", page)
        put("x", o.x / pxPerPt)
        put("y", o.y / pxPerPt)
        if (extend) put("extend", true)
    })
}

private fun DrawScope.drawSelection(sel: SelectionGeometry, page: Int, scale: Float) {
    for (r in sel.rects) if (r.page == page) {
        drawRect(Color(0x553390FF), Offset(r.x * scale, r.y * scale), Size(r.w * scale, r.h * scale))
    }
    sel.caret?.takeIf { it.page == page }?.let { c ->
        drawRect(Color.Black, Offset(c.x * scale, c.y * scale), Size(maxOf(c.w * scale, 1.5f), c.h * scale))
    }
}

/**
 * Asks the user for a file path: an existing file to open, or (when [save]) a destination.
 * Null when cancelled or when the platform has no picker yet. Blocks; call off the UI thread.
 */
expect fun pickFile(save: Boolean): String?

/** Clipboards round-trip text with different line endings and trailing newlines; ignore those. */
private fun sameClipboardText(a: String, b: String) =
    a.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') == b.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')

/** The system clipboard's text, or null when it has none (or the platform has no binding yet). */
expect fun clipboardText(): String?

/** Puts [text] on the system clipboard. */
expect fun setClipboardText(text: String)

/** Quick-access buttons above the ribbon: command ids with no parameters. */
private val QUICK_ACCESS = listOf(Triple("Undo", "edit.undo", "Ctrl+Z"), Triple("Redo", "edit.redo", "Ctrl+Y"))

/**
 * Walking skeleton: a text box feeds `text.insert`, Bold runs `format.bold`, and the page the Rust
 * engine laid out and rasterised is shown below. The real UI (ribbon, pane, caret, selection)
 * builds on the same three calls: execute, inspect, renderPage.
 */
@Composable
fun App() = MaterialTheme {
    val engine = remember { Engine() }
    DisposableEffect(Unit) { onDispose { engine.close() } }

    var revision by remember { mutableStateOf(0) }
    var pages by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var selection by remember { mutableStateOf(SelectionGeometry(null, emptyList())) }
    var ribbon by remember { mutableStateOf<List<RibbonTab>>(emptyList()) }
    var styles by remember { mutableStateOf<List<StylePreview>>(emptyList()) }
    // Bumped when a command may have changed the styles (editing them, opening another file).
    var stylesRevision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }

    // Engine calls block, so they run off the UI thread. The native session is not thread-safe,
    // so every call (edits, geometry, rendering) goes through this lock, in the order issued.
    val engineLock = remember { Mutex() }
    suspend fun perform(block: Engine.() -> Unit) {
        status = try {
            engineLock.withLock { withContext(Dispatchers.Default) { engine.block() } }
            revision++
            ""
        } catch (e: EngineException) {
            e.message ?: "error"
        }
    }

    // Runs a command, bridging the system clipboard: Copy and Cut hand the engine's text to it,
    // and Paste passes its text in (the engine pastes its own richer copy when the text matches).
    var lastCopied by remember { mutableStateOf<String?>(null) }
    suspend fun runCommand(command: String, params: JsonObject = JsonObject(emptyMap())) {
        var p = params
        if (command == "edit.paste") {
            val text = withContext(Dispatchers.Default) { clipboardText() }
            val ours = lastCopied
            if (text != null && ours != null && sameClipboardText(text, ours)) {
                // Still our own copy: no `text`, so the engine pastes its formatted fragment.
                p = JsonObject(emptyMap())
            } else if (!text.isNullOrEmpty()) {
                if (ours != null) println("paste: clipboard text differs from last copy: [$text] vs [$ours]")
                p = buildJsonObject { put("text", text) }
            }
        }
        perform {
            val r = execute(command, p)
            if (command == "edit.copy" || command == "edit.cut") {
                val text = (r as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    setClipboardText(text)
                    lastCopied = text
                }
            }
        }
        if (command.startsWith("styles.") || command.startsWith("file.")) stylesRevision++
    }

    LaunchedEffect(Unit) {
        ribbon = engineLock.withLock { withContext(Dispatchers.Default) { runCatching { parseRibbon(engine.commands()) }.getOrDefault(emptyList()) } }
    }

    LaunchedEffect(stylesRevision) {
        styles = engineLock.withLock {
            withContext(Dispatchers.Default) {
                runCatching { parseStyles(engine.execute("styles.manage", buildJsonObject { put("sort", "recommended") })) }.getOrDefault(emptyList())
            }
        }
    }

    LaunchedEffect(revision) {
        // Geometry and pixels come from the same engine state and are published together, so the
        // caret is never drawn over a page image from before the edit.
        val (sel, bitmaps) = engineLock.withLock {
            withContext(Dispatchers.Default) {
                val sel = runCatching { engine.selection() }.getOrDefault(SelectionGeometry(null, emptyList()))
                val first = engine.renderPage(0, PAGE_SCALE) ?: return@withContext sel to emptyList()
                // Cap the page count so a huge document can't exhaust memory with bitmaps.
                val rest = (1 until minOf(first.pages, MAX_PAGES)).mapNotNull { engine.renderPage(it, PAGE_SCALE) }
                sel to (listOf(first) + rest).map { rgbaToImageBitmap(it.width, it.height, it.rgba) }
            }
        }
        selection = sel
        pages = bitmaps
    }

    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            for ((label, command, shortcut) in QUICK_ACCESS) {
                CommandIconButton(command, label, shortcut) { scope.launch { runCommand(command) } }
            }
            CommandIconButton("file.open", "Open", "") {
                scope.launch {
                    val path = withContext(Dispatchers.Default) { pickFile(save = false) } ?: return@launch
                    perform { execute("file.open", buildJsonObject { put("path", path) }) }
                    stylesRevision++
                }
            }
            CommandIconButton("file.saveAs", "Save As", "") {
                scope.launch {
                    val path = withContext(Dispatchers.Default) { pickFile(save = true) } ?: return@launch
                    perform { execute("file.saveAs", buildJsonObject { put("path", path) }) }
                }
            }
        }
        Ribbon(
            tabs = ribbon,
            styles = styles,
            onApplyStyle = { id -> scope.launch { runCommand("styles.apply", buildJsonObject { put("style", id) }) } },
            onRun = { id -> scope.launch { runCommand(id) } },
        )
        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val pageFocus = remember { FocusRequester() }
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .focusRequester(pageFocus)
                .onPreviewKeyEvent { e ->
                    val k = keyCommand(e) ?: return@onPreviewKeyEvent false
                    scope.launch { runCommand(k.command, k.params) }
                    true
                }
                .focusable()
                .padding(16.dp),
        ) {
            pages.forEachIndexed { i, bitmap ->
                // Image scales the bitmap down to fit the window width, so the points-to-pixels
                // factor is measured from the displayed size rather than assumed to be PAGE_SCALE.
                // Kept per page index, not per bitmap, so a new image doesn't reset it for a frame.
                var shown by remember(i) { mutableStateOf(IntSize.Zero) }
                val pxPerPt = if (shown.width > 0) shown.width * PAGE_SCALE / bitmap.width else PAGE_SCALE
                Image(
                    bitmap,
                    contentDescription = "Page ${i + 1}",
                    modifier = Modifier.padding(bottom = 12.dp)
                        .onSizeChanged { shown = it }
                        .drawWithContent {
                            drawContent()
                            drawSelection(selection, i, pxPerPt)
                        }
                        .pointerInput(i, pxPerPt) {
                            detectTapGestures { o -> pageFocus.requestFocus(); scope.launch { perform { setCaret(i, o, pxPerPt, false) } } }
                        }
                        .pointerInput(i, pxPerPt) {
                            detectDragGestures(
                                onDragStart = { o -> scope.launch { perform { setCaret(i, o, pxPerPt, false) } } },
                                onDrag = { change, _ -> scope.launch { perform { setCaret(i, change.position, pxPerPt, true) } } },
                            )
                        },
                )
            }
        }
    }
}
