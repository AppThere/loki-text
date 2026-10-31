package app.loki.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.ZoomOut
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.PointerType
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Turns straight-alpha RGBA into a Compose image; per platform (Skia). */
expect fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap

/** The desk the pages sit on. */
private val DESK_COLOR = Color(0xFFCCD6E3)

/** Narrower than this, a page (8.5 in) doesn't fit with its margins, and the text reflows instead. */
private val NARROW_WINDOW = 640.dp

/** Widest line of reflowed text, in points: about 90 characters at body size. */
private const val MAX_WEB_WIDTH_PT = 640f

/** Everything one render publishes together. */
private class Snapshot(val selection: SelectionGeometry, val pages: List<ImageBitmap>, val flat: Boolean, val counts: Counts?, val commands: CommandState)

/** What `review.wordCount` reports: for the selection when text is selected, else the document. [characters] includes spaces. */
internal class Counts(val words: Int, val pages: Int, val characters: Int)

private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0

internal fun parseCounts(o: JsonObject) = Counts(o.int("words"), o.int("pages"), o.int("charactersWithSpaces"))

/** The last failure reported by [warnOnce]; a snapshot runs on every keystroke, so a repeat would flood the log. */
private var lastWarning: String? = null

/** Logs [what] failed with [e] (once per distinct message) and returns [fallback], so a broken engine call is visible, not silent. */
private fun <T> warnOnce(what: String, e: Throwable, fallback: T): T {
    val msg = "$what failed: ${e.message ?: e::class.simpleName}"
    if (msg != lastWarning) {
        lastWarning = msg
        println(msg)
    }
    return fallback
}

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 2f

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

/**
 * Called after the engine has written [path] for Save As. Android copies it to the place the user
 * picked; the desktop picked a real path, so there is nothing to do.
 */
expect fun publishFile(path: String)

/** A fresh temporary path for the PDF that a print job sends, writable by the engine. Call off the UI thread. */
expect fun printJobPath(): String

/**
 * Hands the PDF at [path] to the platform's print service (Android's print dialog, the desktop's
 * print handler). Returns why it couldn't, or null once the job has been handed over. Call off the UI thread.
 */
expect fun printPdf(path: String, jobName: String): String?

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
fun App() = MaterialTheme(colorScheme = LokiColors) {
    val engine = remember { Engine() }
    DisposableEffect(Unit) { onDispose { engine.close() } }

    var revision by remember { mutableStateOf(0) }
    var pages by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var selection by remember { mutableStateOf(SelectionGeometry(null, emptyList())) }
    // Which commands can't run now and which toggles are on; refreshed with every render.
    var commandState by remember { mutableStateOf(CommandState.EMPTY) }
    var ribbon by remember { mutableStateOf<List<RibbonTab>>(emptyList()) }
    var styles by remember { mutableStateOf<List<StylePreview>>(emptyList()) }
    // Bumped when a command may have changed the styles (editing them, opening another file).
    var stylesRevision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var flat by remember { mutableStateOf(false) }
    // Client-side zoom: the pages are simply rendered at a larger or smaller scale.
    var zoom by remember { mutableStateOf(1f) }
    val scale = PAGE_SCALE * zoom
    // From the engine's word count; of the selection when there is one.
    var counts by remember { mutableStateOf<Counts?>(null) }

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

    LaunchedEffect(revision, zoom) {
        // Geometry and pixels come from the same engine state and are published together, so the
        // caret is never drawn over a page image from before the edit.
        val snap = engineLock.withLock {
            withContext(Dispatchers.Default) {
                val sel = runCatching { engine.selection() }.getOrDefault(SelectionGeometry(null, emptyList()))
                // Web Layout, Draft and Outline are one continuous column, not sheets of paper.
                val flat = runCatching {
                    val v = engine.execute("view.state") as JsonObject
                    v["mode"]?.jsonPrimitive?.contentOrNull != "print" && v["readMode"]?.jsonPrimitive?.booleanOrNull != true
                }.getOrDefault(false)
                val counts = runCatching { parseCounts(engine.execute("review.wordCount") as JsonObject) }.getOrElse { warnOnce("review.wordCount", it, null) }
                val cmdState = runCatching { parseCommandState(engine.commandState()) }.getOrElse { warnOnce("loki.commandState", it, CommandState.EMPTY) }
                val first = engine.renderPage(0, scale) ?: return@withContext Snapshot(sel, emptyList(), flat, counts, cmdState)
                // Cap the page count so a huge document can't exhaust memory with bitmaps.
                // Bitmap size grows with the square of the zoom, so the page cap shrinks accordingly.
                val cap = (MAX_PAGES / (zoom * zoom)).toInt().coerceAtLeast(5)
                val rest = (1 until minOf(first.pages, cap)).mapNotNull { engine.renderPage(it, scale) }
                Snapshot(sel, (listOf(first) + rest).map { rgbaToImageBitmap(it.width, it.height, it.rgba) }, flat, counts, cmdState)
            }
        }
        selection = snap.selection
        flat = snap.flat
        counts = snap.counts
        commandState = snap.commands
        pages = snap.pages
    }

    var showAbout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val quickAccess: @Composable () -> Unit = {
        for ((label, command, shortcut) in QUICK_ACCESS) {
            CommandIconButton(command, label, shortcut, commandState.disabled[command], command in commandState.on) { scope.launch { runCommand(command) } }
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
                    if (status.isEmpty()) withContext(Dispatchers.Default) { runCatching { publishFile(path) } }
            }
        }
        CommandIconButton("file.print", "Print", "") {
            scope.launch {
                // The engine lays the pages out and writes the PDF; the platform prints it.
                val path = withContext(Dispatchers.Default) { printJobPath() }
                perform { execute("file.printPdf", buildJsonObject { put("path", path) }) }
                if (status.isEmpty()) status = withContext(Dispatchers.Default) { printPdf(path, "$APP_NAME document") } ?: ""
            }
        }
        CommandIconButton("app.about", "About $APP_NAME", "") { showAbout = true }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    if (showAbout) AboutDialog { showAbout = false }
    val sizeClass = when {
        maxWidth < 600.dp -> SizeClass.Compact
        maxWidth < 900.dp -> SizeClass.Medium
        else -> SizeClass.Expanded
    }
    Column(Modifier.fillMaxSize()) {
        if (sizeClass == SizeClass.Expanded) Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { quickAccess() }
        Ribbon(
            tabs = ribbon,
            styles = styles,
            state = commandState,
            sizeClass = sizeClass,
            leading = quickAccess,
            onApplyStyle = { id -> scope.launch { runCommand("styles.apply", buildJsonObject { put("style", id) }) } },
            onRun = { id -> scope.launch { runCommand(id) } },
        )
        val pageFocus = remember { FocusRequester() }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(if (flat) Color.White else DESK_COLOR)) {
        val viewport = maxWidth
        val density = LocalDensity.current
        val gutterPx = with(density) { 32.dp.toPx() }
        val widthPt = ((constraints.maxWidth - gutterPx) / scale).coerceAtMost(MAX_WEB_WIDTH_PT)
        val heightPt = constraints.maxHeight / scale
        // Reflowing views wrap to the window. Debounced so dragging a window edge doesn't re-lay-out
        // and re-render every frame.
        LaunchedEffect(widthPt, heightPt) {
            delay(150)
            engineLock.withLock { withContext(Dispatchers.Default) { runCatching { engine.setViewport(widthPt, heightPt) } } }
            revision++
        }
        // A window narrower than a page (a phone) reads as Web Layout; widening it restores the pages.
        var autoWeb by remember { mutableStateOf(false) }
        val narrow = viewport < NARROW_WINDOW
        LaunchedEffect(narrow) {
            if (narrow) { autoWeb = true; runCommand("view.webLayout") }
            else if (autoWeb) { autoWeb = false; runCommand("view.printLayout") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .widthIn(min = viewport)
                .focusRequester(pageFocus)
                .onPreviewKeyEvent { e ->
                    val k = keyCommand(e) ?: return@onPreviewKeyEvent false
                    scope.launch { runCommand(k.command, k.params) }
                    true
                }
                .focusable()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            pages.forEachIndexed { i, bitmap ->
                // Image scales the bitmap down to fit the window width, so the points-to-pixels
                // factor is measured from the displayed size rather than assumed to be PAGE_SCALE.
                // Kept per page index, not per bitmap, so a new image doesn't reset it for a frame.
                var shown by remember(i) { mutableStateOf(IntSize.Zero) }
                val pxPerPt = if (shown.width > 0) shown.width * scale / bitmap.width else scale
                Image(
                    bitmap,
                    contentDescription = "Page ${i + 1}",
                    // Full size: a window narrower than the page scrolls sideways instead of shrinking it.
                    modifier = Modifier.padding(bottom = 16.dp)
                        .size(with(density) { bitmap.width.toDp() }, with(density) { bitmap.height.toDp() })
                        .then(if (flat) Modifier else Modifier.shadow(4.dp, RectangleShape, clip = false))
                        .onSizeChanged { shown = it }
                        .drawWithContent {
                            drawContent()
                            drawSelection(selection, i, pxPerPt)
                        }
                        .pointerInput(i, pxPerPt) {
                            detectTapGestures { o -> pageFocus.requestFocus(); scope.launch { perform { setCaret(i, o, pxPerPt, false) } } }
                        }
                        .pointerInput(i, pxPerPt) {
                            // Dragging selects with a mouse or pen. A finger's drag is left alone so it scrolls.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (down.type == PointerType.Touch) return@awaitEachGesture
                                var started = false
                                drag(down.id) { change ->
                                    if (!started) {
                                        started = true
                                        scope.launch { perform { setCaret(i, down.position, pxPerPt, false) } }
                                    }
                                    change.consume()
                                    scope.launch { perform { setCaret(i, change.position, pxPerPt, true) } }
                                }
                            }
                        },
                )
            }
        }
        }
        StatusBar(
            status = status,
            page = selection.caret?.page?.plus(1),
            counts = counts,
            selected = selection.rects.isNotEmpty(),
            flat = flat,
            sizeClass = sizeClass,
            zoom = zoom,
            onZoom = { zoom = it.coerceIn(MIN_ZOOM, MAX_ZOOM) },
            onView = { cmd -> scope.launch { runCommand(cmd) } },
        )
    }
    }
}

/**
 * The bar along the bottom: where the caret is, how long the document is, zoom, and the view.
 * Narrow windows drop the parts they have no room for: the character count first, then zoom.
 */
@Composable
private fun StatusBar(
    status: String,
    page: Int?,
    counts: Counts?,
    selected: Boolean,
    flat: Boolean,
    sizeClass: SizeClass,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onView: (String) -> Unit,
) {
    MaterialTheme(colorScheme = OnBarColors) { CompositionLocalProvider(LocalContentColor provides Color.White) {
    Row(Modifier.fillMaxWidth().background(BarBlue).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val small = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        if (status.isNotEmpty()) {
            Text(status, style = small, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            val compact = sizeClass == SizeClass.Compact
            val text = buildList {
                // Web Layout has no pages to count.
                if (!flat && page != null && counts != null) add(if (compact) "$page/${counts.pages}" else "Page $page of ${counts.pages}")
                if (counts != null) {
                    val w = if (counts.words == 1) "1 word" else "${counts.words} words"
                    add(if (selected) "$w selected" else w)
                    if (sizeClass == SizeClass.Expanded) add("${counts.characters} characters")
                }
            }.joinToString("   ·   ")
            Text(text, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (sizeClass != SizeClass.Compact) {
            val step = 0.1f
            IconButton(onClick = { onZoom(((zoom - step) * 10).roundToInt() / 10f) }, enabled = zoom > MIN_ZOOM, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.ZoomOut, contentDescription = "Zoom out", modifier = Modifier.size(16.dp))
            }
            // Clicking the percentage returns to 100%.
            TextButton(onClick = { onZoom(1f) }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(28.dp)) {
                Text("${(zoom * 100).roundToInt()}%", style = small)
            }
            IconButton(onClick = { onZoom(((zoom + step) * 10).roundToInt() / 10f) }, enabled = zoom < MAX_ZOOM, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.ZoomIn, contentDescription = "Zoom in", modifier = Modifier.size(16.dp))
            }
        }
        for ((label, cmd, active) in listOf(Triple("Print", "view.printLayout", !flat), Triple("Web", "view.webLayout", flat))) {
            TextButton(
                onClick = { onView(cmd) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(label, style = small, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Normal)
            }
        }
    }
    } }
}
