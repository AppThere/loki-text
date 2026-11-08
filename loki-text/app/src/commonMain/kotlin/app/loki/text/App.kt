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
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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

    val quickAccess: @Composable () -> Unit = {
        FileMenuButton { id -> scope.launch { runFileMenu(id) } }
        for ((label, command, shortcut) in QUICK_ACCESS) {
            CommandIconButton(command, label, shortcut, commandState.disabled[command], command in commandState.on) { scope.launch { runCommand(command) } }
        }
    }
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
            format = formatState,
            fonts = fontFamilies,
            onRunWith = { id, params -> scope.launch { runCommand(id, params) } },
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
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().background(if (flat) Color.White else DESK_COLOR)) {
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
            Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(rememberScrollState())
                .widthIn(min = viewport)
                .onPreviewKeyEvent { e ->
                    val k = keyCommand(e) ?: return@onPreviewKeyEvent false
                    // File commands may need a picker or a new window, so they go through the menu's path.
                    if (k.command.startsWith("file.")) scope.launch { runFileMenu(k.command) } else scope.launch { runCommand(k.command, k.params) }
                    true
                }
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The page is a bitmap, so this hidden input is what the on-screen keyboard and IME talk to.
            ImeInput(Modifier.focusRequester(pageFocus)) { k -> scope.launch { runCommand(k.command, k.params) } }
            pages.forEachIndexed { i, bitmap ->
                // Image scales the bitmap down to fit the window width, so the points-to-pixels
                // factor is measured from the displayed size rather than assumed to be PAGE_SCALE.
                // Kept per page index, not per bitmap, so a new image doesn't reset it for a frame.
                var shown by remember(i) { mutableStateOf(IntSize.Zero) }
                val pxPerPt = if (shown.width > 0) shown.width * scale / bitmap.width else scale
                Box(Modifier.padding(bottom = 16.dp).onGloballyPositioned { pageGeo[i] = it.positionInParent().y to pxPerPt }) {
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
                        }
                        .pointerInput(i, pxPerPt) {
                            detectTapGestures(
                                // Long press selects the word under the finger; the handles then adjust it.
                                onLongPress = { o ->
                                    pageFocus.requestFocus()
                                    scope.launch { perform { setCaret(i, o, pxPerPt, false); execute("select.word") } }
                                },
                            ) { o -> pageFocus.requestFocus(); keyboard?.show(); scope.launch { perform { setCaret(i, o, pxPerPt, false) } } }
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
                // Selection handles hang below the first and last line; dragging one moves that end.
                selectionHandles(selection.rects)?.let { (startPt, endPt) ->
                    for ((end, pt) in listOf(HandleEnd.Start to startPt, HandleEnd.End to endPt)) if (pt.page == i) {
                        SelectionHandle(
                            end, pt, pxPerPt,
                            // The far end stays put; this end follows the finger.
                            onStart = { e -> scope.launch { perform { execute("select.grabEnd", buildJsonObject { put("end", if (e == HandleEnd.Start) "start" else "end") }) } } },
                            onDrag = { _, pos -> scope.launch { perform { setCaret(i, pos, pxPerPt, true) } } },
                        )
                    }
                }
                }
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
