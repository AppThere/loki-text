package app.loki.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.ZoomOut
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
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
private class Snapshot(val selection: SelectionGeometry, val pages: List<ImageBitmap>, val flat: Boolean, val counts: Counts?, val commands: CommandState, val dirty: Boolean = false, val format: FormatState = FormatState.EMPTY, val path: String? = null)

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

/** The most memory the rendered page bitmaps may use together (RGBA, 4 bytes a pixel). */
private const val PAGE_BYTES_BUDGET = 160L * 1024 * 1024

/**
 * Pixels per point to render at. A point is [PAGE_SCALE] *density-independent* pixels at 100%
 * zoom, so text is the same physical size on a phone (density 2.6) as on a desktop (density 1).
 */
internal fun pagePxPerPt(zoom: Float, density: Float): Float = (PAGE_SCALE * zoom * density.coerceIn(0.5f, 4f)).coerceAtMost(MAX_PAGE_PX_PER_PT)

/**
 * The highest zoom that still renders within the engine's pixel limit at this screen [density]
 * (a dense phone screen reaches it sooner than a desktop), between 1 and [MAX_ZOOM].
 */
internal fun maxZoomFor(density: Float): Float =
    (MAX_PAGE_PX_PER_PT / (PAGE_SCALE * density.coerceIn(0.5f, 4f))).coerceIn(1f, MAX_ZOOM)

/**
 * The zoom after a pinch that scaled the view by [pinch]: in steps of 5%, within the limits. A pinch
 * that is not a usable number leaves [zoom] as it was.
 */
internal fun settleZoom(zoom: Float, pinch: Float, maxZoom: Float): Float {
    val z = zoom * pinch
    if (!z.isFinite() || z <= 0f) return zoom
    return ((z * 20f).roundToInt() / 20f).coerceIn(MIN_ZOOM, maxZoom.coerceAtLeast(MIN_ZOOM))
}

/** The most pixels per point the engine renders (`loki_render_page` clamps to the same). */
private const val MAX_PAGE_PX_PER_PT = 6f

/**
 * How many pages to render: at most [MAX_PAGES] (fewer when zoomed in, as a bitmap grows with the
 * square of the zoom), and no more than fit in [PAGE_BYTES_BUDGET] given the first page's size.
 * Always at least one, so the document is never blank.
 */
internal fun pageRenderCap(zoom: Float, firstWidth: Int, firstHeight: Int): Int {
    val byZoom = (MAX_PAGES / (zoom * zoom)).toInt().coerceAtLeast(5)
    val bytes = firstWidth.toLong().coerceAtLeast(1) * firstHeight.toLong().coerceAtLeast(1) * 4
    val byMemory = (PAGE_BYTES_BUDGET / bytes).toInt().coerceAtLeast(1)
    return minOf(byZoom, byMemory)
}

/** Moves the caret to a pixel position on a rendered page; the engine takes points. */
private fun Engine.setCaret(page: Int, o: Offset, pxPerPt: Float, extend: Boolean) {
    execute("caret.set", buildJsonObject {
        put("page", page)
        put("x", o.x / pxPerPt)
        put("y", o.y / pxPerPt)
        if (extend) put("extend", true)
    })
}

/** The stroke a page tool is drawing: a dashed rectangle (a new table) or a line (a cell border). */
private fun DrawScope.drawPenStroke(st: PenStroke, pxPerPt: Float, ink: Color) {
    val a = Offset(st.x0 * pxPerPt, st.y0 * pxPerPt)
    val b = Offset(st.x1 * pxPerPt, st.y1 * pxPerPt)
    val dash = Stroke(width = 1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * density, 4f * density)))
    if (penPreviewIsLine(st)) drawLine(ink, a, b, strokeWidth = dash.width, pathEffect = dash.pathEffect)
    else drawRect(ink, topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y)), size = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)), style = dash)
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
fun App(
    closeRequest: Int = 0,
    onClose: () -> Unit = {},
    /** A document to open at start (a new window opened for a file). */
    initialPath: String? = null,
    /** Opens a document (a path, or null for a new one) in a new window; null where the platform has only one. */
    onNewWindow: ((String?) -> Unit)? = null,
    /** Set when the app draws the window's title bar (desktop on a JetBrains Runtime); null for a native one. */
    titleBar: TitleBarConfig? = null,
) = MaterialTheme(colorScheme = LokiColors, typography = typographyWith(uiFontFamily())) {
    val engine = remember { Engine() }
    DisposableEffect(Unit) { onDispose { engine.close() } }

    var revision by remember { mutableStateOf(0) }
    var pages by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var selection by remember { mutableStateOf(SelectionGeometry(null, emptyList())) }
    // Which commands can't run now and which toggles are on; refreshed with every render.
    var commandState by remember { mutableStateOf(CommandState.EMPTY) }
    var ribbon by remember { mutableStateOf<List<RibbonTab>>(emptyList()) }
    // The selection's font, size and colours, for the Home tab's boxes; and the families to pick from.
    var formatState by remember { mutableStateOf(FormatState.EMPTY) }
    var fontFamilies by remember { mutableStateOf<List<String>>(emptyList()) }
    var styles by remember { mutableStateOf<List<StylePreview>>(emptyList()) }
    // Bumped when a command may have changed the styles (editing them, opening another file).
    var stylesRevision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    // Draw Table or Eraser while one is on: presses on the page draw instead of moving the caret.
    var tableTool by remember { mutableStateOf<String?>(null) }
    var penStroke by remember { mutableStateOf<PenStroke?>(null) }
    fun setTableTool(tool: String?) {
        tableTool = tool?.takeIf { it in TABLE_TOOLS }
        penStroke = null
        status = tableTool?.let(::tableToolHint).orEmpty()
    }
    // The Font dialog's starting values while it is open.
    var fontDialog by remember { mutableStateOf<FontForm?>(null) }
    var paraDialog by remember { mutableStateOf<ParaForm?>(null) }
    var pageDialog by remember { mutableStateOf<PageForm?>(null) }
    // Manage Styles: open or not, its sort order, the list it shows, and the Modify / New dialogs over it.
    var manageStyles by remember { mutableStateOf(false) }
    // List dialogs: the multilevel definition (levels as read), and the three small ones.
    var listLevels by remember { mutableStateOf<List<LevelForm>?>(null) }
    var listDialog by remember { mutableStateOf<String?>(null) }
    var stylesAlphabetical by remember { mutableStateOf(false) }
    var styleEntries by remember { mutableStateOf<List<StyleEntry>>(emptyList()) }
    var modifyStyle by remember { mutableStateOf<StyleEntry?>(null) }
    var newStyleBase by remember { mutableStateOf<StyleEntry?>(null) }
    var newStyleOpen by remember { mutableStateOf(false) }
    // The Find / Replace / Go To bar; null while it is closed.
    var findBar by remember { mutableStateOf<FindState?>(null) }
    // Unsaved changes, from the engine; and the action (Open, closing the window) waiting on the user's answer.
    var unsaved by remember { mutableStateOf(false) }
    // The file the document is saved to, or null for one that has none yet.
    var docPath by remember { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var flat by remember { mutableStateOf(false) }
    // Client-side zoom: the pages are simply rendered at a larger or smaller scale.
    var zoom by remember { mutableStateOf(1f) }
    // While two fingers are down the pages are only scaled on screen; the zoom is applied (and the
    // pages re-rendered sharply) when they lift.
    var pinch by remember { mutableStateOf(1f) }
    // The last tap on a page, to tell a double tap from two single ones.
    var lastTapMark by remember { mutableStateOf<TimeSource.Monotonic.ValueTimeMark?>(null) }
    var lastTapAt by remember { mutableStateOf(Offset.Zero) }
    val tapSlopPx = with(LocalDensity.current) { 40.dp.toPx() }
    // The context menu (right-click, or long-press on a selection or a misspelled word), over a page.
    var ctxMenu by remember { mutableStateOf<ContextMenuData?>(null) }
    // The floating toolbar hides while a handle or the mouse is moving the selection.
    var handleDragging by remember { mutableStateOf(false) }
    var mouseSelecting by remember { mutableStateOf(false) }
    // Where each page and the page area sit on screen, to place the floating toolbar over the selection.
    val pageOrigin = remember { mutableStateMapOf<Int, Offset>() }
    var viewportOrigin by remember { mutableStateOf(Offset.Zero) }
    val screenDensity = LocalDensity.current.density
    val scale = pagePxPerPt(zoom, screenDensity)
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

    // Picks the picture, shape or text box under a press, if any (a press elsewhere is a caret placement).
    suspend fun selectObjectAt(page: Int, at: Offset, pxPerPt: Float): Boolean {
        val picked = engineLock.withLock {
            withContext(Dispatchers.Default) {
                runCatching {
                    val r = engine.execute("select.objectAt", buildJsonObject { put("page", page); put("x", at.x / pxPerPt); put("y", at.y / pxPerPt) }) as? JsonObject
                    r?.get("selected")?.jsonPrimitive?.booleanOrNull == true
                }.getOrDefault(false)
            }
        }
        if (picked) revision++
        return picked
    }

    // Enters the header or footer under a double tap (Print Layout only), and leaves it again from the body.
    suspend fun enterHeaderFooterAt(page: Int, at: Offset, pxPerPt: Float): Boolean {
        val entered = engineLock.withLock {
            withContext(Dispatchers.Default) {
                runCatching {
                    val r = engine.execute("hf.enterAt", buildJsonObject { put("page", page); put("x", at.x / pxPerPt); put("y", at.y / pxPerPt) }) as? JsonObject
                    r?.get("entered")?.jsonPrimitive?.contentOrNull != null
                }.getOrDefault(false)
            }
        }
        if (entered) revision++
        return entered
    }

    // What the engine says about the word at the caret, for the context menu.
    suspend fun hasIssueAtCaret(): Boolean =
        engineLock.withLock { withContext(Dispatchers.Default) { runCatching { parseIssue(engine.execute("review.suggestions")) != null }.getOrDefault(false) } }

    suspend fun openContextMenu(page: Int, at: Offset) {
        val (issue, synonyms) = engineLock.withLock {
            withContext(Dispatchers.Default) {
                parseIssue(runCatching { engine.execute("review.suggestions") }.getOrNull()) to parseSynonyms(runCatching { engine.execute("review.thesaurus") }.getOrNull())
            }
        }
        ctxMenu = ContextMenuData(page, at.x, at.y, issue, synonyms)
    }

    // Runs a command, bridging the system clipboard: Copy and Cut hand the engine's text to it,
    // and Paste passes its text in (the engine pastes its own richer copy when the text matches).
    var lastCopied by remember { mutableStateOf<String?>(null) }
    suspend fun runCommand(command: String, params: JsonObject = JsonObject(emptyMap())) {
        if (command == "format.fontDialog") {
            // The engine only asks a UI to open it; this UI shows its own dialog from the selection's formatting.
            val state = engineLock.withLock { withContext(Dispatchers.Default) { runCatching { engine.execute("format.state") as? JsonObject }.getOrNull() } }
            fontDialog = fontFormFrom(state ?: JsonObject(emptyMap()))
            return
        }
        // Find, Replace and Go To live in a bar above the page. Commands with text go straight to the engine.
        val bare = params.isEmpty()
        val open: FindMode? = when {
            command == "edit.find" && bare || command == "edit.advancedFind" && bare -> FindMode.Find
            command == "edit.findReplace" -> if (params["tab"]?.jsonPrimitive?.contentOrNull == "find") FindMode.Find
                else if (params["tab"]?.jsonPrimitive?.contentOrNull == "goto") FindMode.GoTo else FindMode.Replace
            command == "edit.goto" && bare -> FindMode.GoTo
            (command == "edit.findNext" || command == "edit.findPrevious") && bare && findBar?.query.isNullOrEmpty() -> FindMode.Find
            else -> null
        }
        if (open != null) {
            findBar = (findBar ?: FindState()).copy(mode = open)
            return
        }
        if ((command == "edit.findNext" || command == "edit.findPrevious") && bare) {
            findBar?.let { b -> runCommand(command, findParams(b)) }
            return
        }
        if (command == "list.define" && params["levels"] == null) {
            val got = engineLock.withLock { withContext(Dispatchers.Default) { runCatching { engine.execute("list.get") }.getOrNull() } }
            listLevels = levelsFrom(got)
            return
        }
        if (params.isEmpty() && command in setOf("para.defineBullet", "para.defineNumber", "para.setNumberingValue")) { listDialog = command; return }
        if (command == "review.comments") { runCommand("view.commentsPane"); return }
        if (command == "styles.manage") { manageStyles = true; return }
        if (command == "styles.create" && params["name"] == null) { newStyleBase = null; newStyleOpen = true; return }
        if (command == "styles.modify" && params["style"] == null) {
            // The style at the caret.
            val id = engineLock.withLock { withContext(Dispatchers.Default) { runCatching { (engine.execute("format.state") as? JsonObject)?.get("style")?.jsonPrimitive?.contentOrNull }.getOrNull() } }
            val all = engineLock.withLock { withContext(Dispatchers.Default) { runCatching { parseStyleEntries(engine.execute("styles.manage")) }.getOrDefault(emptyList()) } }
            all.firstOrNull { it.id == id }?.let { modifyStyle = it } ?: run { manageStyles = true }
            return
        }
        if (command == "layout.pageSetup" && params["section"] == null) {
            val section = engineLock.withLock { withContext(Dispatchers.Default) { runCatching { engine.execute("layout.section") as? JsonObject }.getOrNull() } }
            pageDialog = pageFormFrom(section ?: JsonObject(emptyMap()))
            return
        }
        if (command == "para.dialog") {
            val state = engineLock.withLock { withContext(Dispatchers.Default) { runCatching { engine.execute("para.state") as? JsonObject }.getOrNull() } }
            paraDialog = paraFormFrom(state ?: JsonObject(emptyMap()))
            return
        }
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
        if (installDocumentFonts(engine, engineLock)) revision++
        if (initialPath != null) {
            perform { execute("file.open", buildJsonObject { put("path", initialPath) }) }
            stylesRevision++
        }
        fontFamilies = engineLock.withLock {
            withContext(Dispatchers.Default) {
                runCatching { parseFontFamilies(engine.execute("font.list", buildJsonObject { put("all", true) })) }.getOrDefault(emptyList())
            }
        }
    }

    // Every engine command's keyboard shortcuts, by chord.
    var shortcuts by remember { mutableStateOf<Map<Chord, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val commands = engineLock.withLock { withContext(Dispatchers.Default) { runCatching { engine.commands() }.getOrNull() } }
        ribbon = commands?.let { runCatching { parseRibbon(it) }.getOrNull() }.orEmpty()
        shortcuts = commands?.let { runCatching { parseShortcuts(it) }.getOrNull() }.orEmpty()
    }

    // The Equation tab's structures and symbols don't change while the app runs.
    var equationGallery by remember { mutableStateOf(EquationGallery.EMPTY) }
    LaunchedEffect(Unit) {
        equationGallery = engineLock.withLock {
            withContext(Dispatchers.Default) { runCatching { parseEquationGallery(engine.execute("equation.gallery")) }.getOrDefault(EquationGallery.EMPTY) }
        }
    }
    LaunchedEffect(stylesRevision) {
        styles = engineLock.withLock {
            withContext(Dispatchers.Default) {
                runCatching { parseStyles(engine.execute("styles.manage", buildJsonObject { put("sort", "recommended") })) }.getOrDefault(emptyList())
            }
        }
    }

    LaunchedEffect(stylesRevision, manageStyles, stylesAlphabetical, listLevels != null) {
        if (!manageStyles && listLevels == null) return@LaunchedEffect
        styleEntries = engineLock.withLock {
            withContext(Dispatchers.Default) {
                runCatching { parseStyleEntries(engine.execute("styles.manage", buildJsonObject { put("sort", if (stylesAlphabetical) "alphabetical" else "recommended") })) }.getOrDefault(emptyList())
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
                val fmt = runCatching { parseFormatState(engine.execute("format.state")) }.getOrDefault(FormatState.EMPTY)
                val info = runCatching { engine.execute("file.info") as? JsonObject }.getOrNull()
                val dirty = info?.get("dirty")?.jsonPrimitive?.booleanOrNull == true
                val path = info?.get("path")?.jsonPrimitive?.contentOrNull
                val first = engine.renderPage(0, scale) ?: return@withContext Snapshot(sel, emptyList(), flat, counts, cmdState, dirty, fmt)
                // Cap the page count so a huge document can't exhaust memory with bitmaps.
                // Bitmap size grows with the square of the zoom, so the page cap shrinks accordingly.
                val cap = pageRenderCap(zoom, first.width, first.height)
                val rest = (1 until minOf(first.pages, cap)).mapNotNull { engine.renderPage(it, scale) }
                Snapshot(sel, (listOf(first) + rest).map { rgbaToImageBitmap(it.width, it.height, it.rgba) }, flat, counts, cmdState, dirty, fmt, path)
            }
        }
        selection = snap.selection
        flat = snap.flat
        counts = snap.counts
        commandState = snap.commands
        formatState = snap.format
        unsaved = snap.dirty
        docPath = snap.path
        pages = snap.pages
    }

    var showAbout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Save, or Save As for a document that has no file yet. True when the document was written
    // (and published, on platforms that need it); false when the user cancelled or it failed.
    suspend fun saveDocument(): Boolean {
        var saved: String? = null
        perform {
            val r = execute("file.save") as? JsonObject
            if (r?.get("saved")?.jsonPrimitive?.booleanOrNull == true) saved = r["path"]?.jsonPrimitive?.contentOrNull
        }
        if (status.isNotEmpty()) return false
        val path = saved ?: (withContext(Dispatchers.Default) { pickFile(save = true) } ?: return false).also { target ->
            perform { execute("file.saveAs", buildJsonObject { put("path", target) }) }
            if (status.isNotEmpty()) return false
        }
        withContext(Dispatchers.Default) { runCatching { publishFile(path) } }
        return true
    }

    // Runs [action] now, or after the user has chosen what to do with unsaved changes.
    fun guarded(action: () -> Unit) { if (unsaved) pendingAction = action else action() }

    LaunchedEffect(closeRequest) { if (closeRequest > 0) guarded(onClose) }
    // A document is blank when nothing was typed and it has no file; opening another then reuses this window.
    fun isBlank() = docPath == null && !unsaved && (counts?.characters ?: 0) == 0

    suspend fun openHere(path: String) {
        perform { execute("file.open", buildJsonObject { put("path", path) }) }
        stylesRevision++
    }

    suspend fun runFileMenu(id: String) {
        when (id) {
            // New and Open go to a new window unless this one is blank (Open) or the platform has only one.
            "file.new" -> if (onNewWindow != null) onNewWindow(null) else guarded {
                scope.launch { perform { execute("file.new") }; stylesRevision++ }
            }
            "file.open" -> {
                val path = withContext(Dispatchers.Default) { pickFile(save = false) } ?: return
                if (onNewWindow != null && !isBlank()) onNewWindow(path) else guarded { scope.launch { openHere(path) } }
            }
            "file.save" -> saveDocument()
            "file.saveAs" -> {
                val path = withContext(Dispatchers.Default) { pickFile(save = true) } ?: return
                perform { execute("file.saveAs", buildJsonObject { put("path", path) }) }
                if (status.isEmpty()) withContext(Dispatchers.Default) { runCatching { publishFile(path) } }
            }
            "file.print" -> {
                // The engine lays the pages out and writes the PDF; the platform prints it.
                val path = withContext(Dispatchers.Default) { printJobPath() }
                perform { execute("file.printPdf", buildJsonObject { put("path", path) }) }
                if (status.isEmpty()) status = withContext(Dispatchers.Default) { printPdf(path, "$APP_NAME document") } ?: ""
            }
            "app.about" -> showAbout = true
        }
    }

    // A key's command: file commands that need a picker or a window go through the File menu's path.
    fun runKey(k: KeyCommand) {
        scope.launch { if (k.command in FILE_MENU_COMMANDS) runFileMenu(k.command) else runCommand(k.command, k.params) }
    }
    val fileMenu: @Composable () -> Unit = { FileMenuButton { id -> scope.launch { runFileMenu(id) } } }
    val quickCommands: @Composable () -> Unit = {
        for ((label, command, shortcut) in QUICK_ACCESS) {
            CommandIconButton(command, label, shortcut, commandState.disabled[command], command in commandState.on) { scope.launch { runCommand(command) } }
        }
    }
    val quickAccess: @Composable () -> Unit = { fileMenu(); quickCommands() }
    // Android 15 draws edge to edge; keep content clear of the system bars, display cutout and keyboard.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
    if (showAbout) AboutDialog { showAbout = false }
    listLevels?.let { start ->
        DefineListDialog(
            start,
            styleEntries.filter { (it.type == "paragraph" || it.type == "linked") && !it.hidden }.map { it.id to it.name }.sortedBy { it.second },
            onApply = { p -> listLevels = null; scope.launch { runCommand("list.define", p) } },
            onDismiss = { listLevels = null },
        )
    }
    when (listDialog) {
        "para.defineBullet" -> DefineBulletDialog({ p -> listDialog = null; scope.launch { runCommand("para.defineBullet", p) } }, { listDialog = null })
        "para.defineNumber" -> DefineNumberDialog({ p -> listDialog = null; scope.launch { runCommand("para.defineNumber", p) } }, { listDialog = null })
        "para.setNumberingValue" -> NumberingValueDialog({ p -> listDialog = null; scope.launch { runCommand("para.setNumberingValue", p) } }, { listDialog = null })
    }
    if (manageStyles) {
        ManageStylesDialog(
            styleEntries, stylesAlphabetical, { stylesAlphabetical = it },
            run = { cmd, p -> scope.launch { runCommand(cmd, p) } },
            onModify = { modifyStyle = it },
            onNew = { newStyleBase = it; newStyleOpen = true },
            onClose = { manageStyles = false },
        )
    }
    modifyStyle?.let { st ->
        ModifyStyleDialog(st, onApply = { p ->
            modifyStyle = null
            if (p.isNotEmpty()) scope.launch { runCommand("styles.modify", p) }
        }, onDismiss = { modifyStyle = null })
    }
    if (newStyleOpen) {
        NewStyleDialog(newStyleBase?.name, onCreate = { p ->
            newStyleOpen = false
            scope.launch { runCommand("styles.create", p) }
        }, onDismiss = { newStyleOpen = false })
    }
    pageDialog?.let { start ->
        PageSetupDialog(start, onApply = { changes ->
            pageDialog = null
            if (changes.isNotEmpty()) scope.launch { runCommand("layout.pageLayout", changes) }
        }, onDismiss = { pageDialog = null })
    }
    paraDialog?.let { start ->
        ParagraphDialog(start, onApply = { changes ->
            paraDialog = null
            if (changes.isNotEmpty()) scope.launch { runCommand("para.set", changes) }
        }, onDismiss = { paraDialog = null })
    }
    fontDialog?.let { start ->
        FontDialog(start, fontFamilies, onApply = { changes ->
            fontDialog = null
            if (changes.isNotEmpty()) scope.launch { runCommand("format.set", buildJsonObject { put("props", changes) }) }
        }, onDismiss = { fontDialog = null })
    }
    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("Save changes?") },
            text = { Text("This document has changes that are not saved. If you don't save them, they will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingAction = null
                    scope.launch { if (saveDocument()) action() }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingAction = null; action() }) { Text("Don't save") }
                    TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
                }
            },
        )
    }
    val sizeClass = when {
        maxWidth < 600.dp -> SizeClass.Compact
        maxWidth < 900.dp -> SizeClass.Medium
        else -> SizeClass.Expanded
    }
    // Shortcuts work wherever focus is (the ribbon, the title bar): keys a focused text box doesn't
    // use come up to here. The page handles its own keys first (see its onPreviewKeyEvent).
    Column(Modifier.fillMaxSize().onKeyEvent { e -> shortcutCommand(e, shortcuts)?.let { runKey(it); true } ?: false }) {
        if (titleBar != null) {
            // The title bar is the top row at every width: Undo/Redo, the document's name, the menu, window buttons.
            val hits = remember { TitleBarHits() }
            Row(
                Modifier.fillMaxWidth().height(titleBar.height).titleBarArea(titleBar, hits)
                    .padding(start = titleBar.leftInset + 8.dp, end = titleBar.rightInset + 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.titleBarControl(hits)) { quickCommands() }
                Text(
                    windowTitle(docPath, unsaved),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Row(Modifier.titleBarControl(hits)) { fileMenu() }
                titleBar.windowButtons?.let { buttons -> Row(Modifier.titleBarControl(hits)) { buttons() } }
            }
        } else if (sizeClass == SizeClass.Expanded) Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { quickAccess() }
        Ribbon(
            tabs = ribbon,
            styles = styles,
            state = commandState,
            sizeClass = sizeClass,
            leading = if (titleBar != null) ({}) else quickAccess,
            onApplyStyle = { id -> scope.launch { runCommand("styles.apply", buildJsonObject { put("style", id) }) } },
            onRun = { id -> scope.launch { runCommand(id) } },
            format = formatState,
            fonts = fontFamilies,
            onRunWith = { id, params -> scope.launch { runCommand(id, params) } },
            tableTool = tableTool,
            onTableTool = ::setTableTool,
            equation = equationGallery,
        )
        findBar?.let { b ->
            FindBar(
                b, { findBar = it },
                run = { cmd, p ->
                    runCatching { engineLock.withLock { withContext(Dispatchers.Default) { engine.execute(cmd, p) } } }
                        .onSuccess { revision++ }
                        .recoverCatching { e -> throw (e as? EngineException ?: e) }
                },
                onClose = {
                    findBar = null
                    scope.launch { runCatching { engineLock.withLock { withContext(Dispatchers.Default) { engine.execute("edit.advancedFind", buildJsonObject { put("highlight", false) }) } } }; revision++ }
                },
            )
        }
        val pageFocus = remember { FocusRequester() }
        // With a keyboard (desktop), the page takes focus at once so typing and shortcuts work
        // before the first click. On a touch screen that would pop up the on-screen keyboard.
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(pageFocus) { if (inputMode.inputMode == InputMode.Keyboard) runCatching { pageFocus.requestFocus() } }
        val keyboard = LocalSoftwareKeyboardController.current
        // The Navigation pane follows the engine's View › Navigation Pane toggle: beside the page, or above it in a narrow window.
        val navOpen = "view.navigationPane" in commandState.on
        val navRun: suspend (String, JsonObject) -> Result<kotlinx.serialization.json.JsonElement?> = { cmd, p ->
            runCatching { engineLock.withLock { withContext(Dispatchers.Default) { engine.execute(cmd, p) } } }.onSuccess { if (cmd != "nav.headings" && cmd != "nav.search" && cmd != "review.comments") revision++ }
        }
        if (navOpen && sizeClass == SizeClass.Compact) {
            NavPane(revision, counts?.pages ?: 0, selection.caret?.page?.plus(1), navRun, { scope.launch { runCommand("view.navigationPane") } }, Modifier.fillMaxWidth().height(240.dp))
        }
        val commentsOpen = "view.commentsPane" in commandState.on
        if (commentsOpen && sizeClass == SizeClass.Compact) {
            CommentsPane(revision, navRun, { scope.launch { runCommand("view.commentsPane") } }, Modifier.fillMaxWidth().height(240.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
        if (navOpen && sizeClass != SizeClass.Compact) {
            NavPane(revision, counts?.pages ?: 0, selection.caret?.page?.plus(1), navRun, { scope.launch { runCommand("view.navigationPane") } }, Modifier.width(300.dp).fillMaxHeight())
        }
        val maxZoom = maxZoomFor(LocalDensity.current.density)
        val previewFonts = rememberPreviewFonts()
        val currentZoom by rememberUpdatedState(zoom)
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxHeight().background(if (flat) Color.White else DESK_COLOR)
                .onGloballyPositioned { viewportOrigin = it.positionInRoot() }
                // Two fingers zoom; one finger is left alone so it scrolls and taps. Seen before the
                // pages are (Initial pass) and consumed only while pinching.
                .pointerInput(maxZoom) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pinching = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                pinching = true
                                val z = event.calculateZoom()
                                if (z.isFinite() && z > 0f) pinch = (pinch * z).coerceIn(MIN_ZOOM / currentZoom, maxZoom / currentZoom)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) {
                            zoom = settleZoom(currentZoom, pinch, maxZoom)
                            pinch = 1f
                        }
                    }
                },
        ) {
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
        // Where each page sits in the scrolled content (top, pixels per point), to find the caret on screen.
        val pageGeo = remember { mutableMapOf<Int, Pair<Float, Float>>() }
        val vScroll = rememberScrollState()
        // Keep the caret in view as it moves, and when the keyboard shrinks the viewport.
        val viewportPx = constraints.maxHeight
        LaunchedEffect(selection, viewportPx) {
            val c = selection.caret ?: return@LaunchedEffect
            val (top, pxPerPt) = pageGeo[c.page] ?: return@LaunchedEffect
            val margin = with(density) { 24.dp.toPx() }
            val caretTop = top + c.y * pxPerPt
            val caretBottom = caretTop + c.h * pxPerPt
            val target = when {
                caretBottom + margin > vScroll.value + viewportPx -> (caretBottom + margin - viewportPx).toInt()
                caretTop - margin < vScroll.value -> (caretTop - margin).toInt()
                else -> return@LaunchedEffect
            }
            vScroll.animateScrollTo(target.coerceAtLeast(0))
        }
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer { scaleX = pinch; scaleY = pinch; transformOrigin = TransformOrigin(0.5f, 0f) }
                .verticalScroll(vScroll).horizontalScroll(rememberScrollState())
                .widthIn(min = viewport)
                .onPreviewKeyEvent { e ->
                    if (tableTool != null && e.key == Key.Escape && e.type == KeyEventType.KeyDown) {
                        setTableTool(null)
                        return@onPreviewKeyEvent true
                    }
                    // The engine's shortcuts first (Ctrl+O, F7, …), then typing and moving the caret.
                    val k = shortcutCommand(e, shortcuts) ?: keyCommand(e) ?: return@onPreviewKeyEvent false
                    runKey(k)
                    true
                }
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The page is a bitmap, so this hidden input is what the on-screen keyboard and IME talk to.
            ImeInput(Modifier.focusRequester(pageFocus)) { k -> scope.launch { runCommand(k.command, k.params) } }
            val penInk = MaterialTheme.colorScheme.primary
            pages.forEachIndexed { i, bitmap ->
                // Image scales the bitmap down to fit the window width, so the points-to-pixels
                // factor is measured from the displayed size rather than assumed to be PAGE_SCALE.
                // Kept per page index, not per bitmap, so a new image doesn't reset it for a frame.
                var shown by remember(i) { mutableStateOf(IntSize.Zero) }
                val pxPerPt = if (shown.width > 0) shown.width * scale / bitmap.width else scale
                Box(Modifier.padding(bottom = 16.dp).onGloballyPositioned { pageGeo[i] = it.positionInParent().y to pxPerPt; pageOrigin[i] = it.positionInRoot() }) {
                Image(
                    bitmap,
                    contentDescription = "Page ${i + 1}",
                    // Full size: a window narrower than the page scrolls sideways instead of shrinking it.
                    modifier = Modifier
                        .size(with(density) { bitmap.width.toDp() }, with(density) { bitmap.height.toDp() })
                        .then(if (flat) Modifier else Modifier.shadow(4.dp, RectangleShape, clip = false))
                        .onSizeChanged { shown = it }
                        .drawWithContent {
                            drawContent()
                            drawSelection(selection, i, pxPerPt)
                            penStroke?.takeIf { it.page == i }?.let { drawPenStroke(it, pxPerPt, penInk) }
                        }
                        .then(if (tableTool == null) Modifier else Modifier.pointerHoverIcon(PointerIcon.Crosshair).pointerInput(i, pxPerPt, tableTool) {
                            // A page tool: every press is a stroke (a finger's too), and the caret stays put.
                            val tool = tableTool ?: return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val x0 = down.position.x / pxPerPt
                                val y0 = down.position.y / pxPerPt
                                penStroke = PenStroke(i, x0, y0, x0, y0)
                                drag(down.id) { change ->
                                    change.consume()
                                    penStroke = PenStroke(i, x0, y0, change.position.x / pxPerPt, change.position.y / pxPerPt)
                                }
                                val done = penStroke
                                penStroke = null
                                val cmd = done?.let { penCommand(tool, it) } ?: return@awaitEachGesture
                                scope.launch {
                                    runCommand(cmd.first, cmd.second)
                                    // The hint stays while the tool does; a failed stroke's reason shows instead.
                                    if (tableTool == tool && status.isEmpty()) status = tableToolHint(tool)
                                }
                            }
                        })
                        .then(if (tableTool != null) Modifier else Modifier.pointerInput(i, pxPerPt) {
                            // Right-click: seen before the tap and drag handlers (Initial pass) and taken, so the
                            // selection a click inside it would collapse is still there to be copied.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (down.type == PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) {
                                    down.consume()
                                    pageFocus.requestFocus()
                                    scope.launch {
                                        if (!pointInSelection(selection.rects, i, down.position.x / pxPerPt, down.position.y / pxPerPt)) perform { setCaret(i, down.position, pxPerPt, false) }
                                        openContextMenu(i, down.position)
                                    }
                                }
                            }
                        }.pointerInput(i, pxPerPt) {
                            detectTapGestures(
                                // Long press selects the word under the finger; the handles then adjust it.
                                onLongPress = { o ->
                                    pageFocus.requestFocus()
                                    scope.launch {
                                        // On the selection already: its menu. Elsewhere: select the word, and if it is a
                                        // misspelling offer its fixes at once (the handles and toolbar cover the rest).
                                        if (selectObjectAt(i, o, pxPerPt)) {
                                            // An object is picked: its frame has the handles.
                                        } else if (pointInSelection(selection.rects, i, o.x / pxPerPt, o.y / pxPerPt)) {
                                            openContextMenu(i, o)
                                        } else {
                                            perform { setCaret(i, o, pxPerPt, false); execute("select.word") }
                                            if (hasIssueAtCaret()) openContextMenu(i, o)
                                        }
                                    }
                                },
                            ) { o ->
                                pageFocus.requestFocus()
                                // A second tap close to the first: in a header or footer area it edits that one; in the body, while one
                                // is being edited, it goes back to the body.
                                val twice = lastTapMark?.let { isDoubleTap(it.elapsedNow().inWholeMilliseconds, lastTapAt, o, tapSlopPx) } == true
                                lastTapMark = if (twice) null else TimeSource.Monotonic.markNow()
                                lastTapAt = o
                                // A picture, shape or text box under the finger is selected (its frame appears); anything else puts the caret there.
                                scope.launch {
                                    if (twice && enterHeaderFooterAt(i, o, pxPerPt)) return@launch
                                    if (twice && selection.hf != null) { perform { execute("insert.closeHeader"); setCaret(i, o, pxPerPt, false) }; return@launch }
                                    if (selectObjectAt(i, o, pxPerPt)) keyboard?.hide()
                                    else { keyboard?.show(); perform { setCaret(i, o, pxPerPt, false) } }
                                }
                            }
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
                                        mouseSelecting = true
                                        scope.launch { perform { setCaret(i, down.position, pxPerPt, false) } }
                                    }
                                    change.consume()
                                    scope.launch { perform { setCaret(i, change.position, pxPerPt, true) } }
                                }
                                mouseSelecting = false
                            }
                        }),
                )
                // Selection handles hang below the first and last line; dragging one moves that end.
                selectionHandles(if (selection.obj == null) selection.rects else emptyList())?.let { (startPt, endPt) ->
                    for ((end, pt) in listOf(HandleEnd.Start to startPt, HandleEnd.End to endPt)) if (pt.page == i) {
                        SelectionHandle(
                            end, pt, pxPerPt,
                            // The far end stays put; this end follows the finger.
                            onEnd = { handleDragging = false },
                            onStart = { e -> handleDragging = true; scope.launch { perform { execute("select.grabEnd", buildJsonObject { put("end", if (e == HandleEnd.Start) "start" else "end") }) } } },
                            onDrag = { _, pos -> scope.launch { perform { setCaret(i, pos, pxPerPt, true) } } },
                        )
                    }
                }
                // The selected object's frame: move, resize and rotate it.
                selection.obj?.takeIf { it.page == i }?.let { o ->
                    ObjectFrameOverlay(o, pxPerPt) { id, p -> scope.launch { runCommand(id, p) } }
                }
                ctxMenu?.takeIf { it.page == i }?.let { m ->
                    Box(Modifier.offset { IntOffset(m.x.roundToInt(), m.y.roundToInt()) }) {
                        ContextMenu(m, commandState, { id, p -> scope.launch { runCommand(id, p) } }, { ctxMenu = null })
                    }
                }
                }
            }
        }
        selection.hf?.let { kind ->
            HeaderFooterBar(kind, { scope.launch { runCommand("insert.closeHeader") } }, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
        // The floating toolbar over a selection: above its first visible line, flipping below when
        // there is no room. Hidden while something else is moving the selection or has the screen.
        val miniShown = selection.rects.isNotEmpty() && selection.obj == null && ctxMenu == null && pinch == 1f && !handleDragging && !mouseSelecting &&
            commandState.disabled["format.bold"] == null && fontDialog == null && paraDialog == null &&
            pageDialog == null && !manageStyles && listLevels == null && listDialog == null
        if (miniShown) {
            val miniEnv = remember(commandState, formatState, fontFamilies) {
                RibbonEnv(emptyMap(), commandState, formatState, fontFamilies, previewFonts, { id -> scope.launch { runCommand(id) } }, { id, p -> scope.launch { runCommand(id, p) } })
            }
            val rectsPx = selection.rects.mapNotNull { r ->
                val origin = pageOrigin[r.page] ?: return@mapNotNull null
                val k = pageGeo[r.page]?.second ?: return@mapNotNull null
                val left = origin.x + r.x * k - viewportOrigin.x
                val top = origin.y + r.y * k - viewportOrigin.y
                Rect(left, top, left + r.w * k, top + r.h * k)
            }
            val anchor = visibleAnchorRect(rectsPx, Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
            var barSize by remember { mutableStateOf(IntSize(1, 1)) }
            val at = anchor?.let {
                miniToolbarOffset(
                    it.top, it.bottom, it.left, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
                    barSize.width.toFloat(), barSize.height.toFloat(), MINI_BAR_GAP_DP * density.density, MINI_BAR_INSET_DP * density.density,
                )
            }
            if (at != null) {
                MiniToolbar(miniEnv, Modifier.align(Alignment.TopStart).offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }.onSizeChanged { barSize = it })
            }
        }
        }
        if (commentsOpen && sizeClass != SizeClass.Compact) {
            CommentsPane(revision, navRun, { scope.launch { runCommand("view.commentsPane") } }, Modifier.width(320.dp).fillMaxHeight())
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
            onZoom = { zoom = it.coerceIn(MIN_ZOOM, maxZoomFor(screenDensity)) },
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
