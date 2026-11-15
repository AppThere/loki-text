package app.loki.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.ZoomOut
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// The canvas of an art app: the page on a pasteboard, panned and zoomed, and every press on it
// turned into document-space input for the app's tools. It knows no engine: the app hands it the
// page's picture and gets back [CanvasInput]s, which its tools turn into engine commands.
//
// Pens (an S Pen, an Apple Pencil, a Wacom pen) are first-class: pressure, tilt and barrel rotation
// travel with every point, the eraser end is reported as such, the high-rate samples a pen produces
// between frames are kept, and once a pen has touched the canvas a finger no longer draws (palm
// rejection): fingers pan and zoom, the pen draws, as in the art apps people use on tablets.

/** How a point was made. */
enum class InputDevice { Mouse, Touch, Pen, Eraser }

/** A point on the document: position in document units, and how the pen was held (a mouse: pressure 1, no tilt). */
class CanvasPoint(
    val x: Double,
    val y: Double,
    val pressure: Float = 1f,
    /** Degrees −90..90, as Pointer Events give it: positive leaning right (x) and toward the bottom of the page (y). */
    val tiltX: Float? = null,
    val tiltY: Float? = null,
    /** The barrel's rotation, degrees clockwise 0..360, on a pen that reads it. */
    val rotation: Float? = null,
    val timeMs: Long = 0,
)

enum class CanvasPhase { Down, Drag, Up, Cancel, Hover }

/** Shift, Alt (Option) and Ctrl/Cmd, as held for this input. */
class CanvasMods(val shift: Boolean = false, val alt: Boolean = false, val command: Boolean = false)

/**
 * One piece of input for the tool in hand. [points] are in document units: one for Down, Up and
 * Hover; for Drag every sample since the last one (a pen gives several per frame). [barrel] is true
 * while a pen's side button is held (an S Pen's button).
 */
class CanvasInput(val phase: CanvasPhase, val points: List<CanvasPoint>, val device: InputDevice, val mods: CanvasMods, val barrel: Boolean = false)

/** How the document sits in the canvas: [zoom] screen pixels per document unit, [pan] the screen position of the document's origin. */
@Stable
class CanvasView {
    var zoom by mutableFloatStateOf(0f)
        internal set
    var pan by mutableStateOf(Offset.Zero)
        internal set
    /** The canvas's size in pixels, once laid out. */
    var viewport by mutableStateOf(Size.Zero)
        internal set
    internal var fittedTo: Rect? = null

    fun toScreen(x: Double, y: Double): Offset = Offset((x * zoom).toFloat() + pan.x, (y * zoom).toFloat() + pan.y)
    fun toDoc(p: Offset): Pair<Double, Double> = if (zoom <= 0f) 0.0 to 0.0 else ((p.x - pan.x) / zoom).toDouble() to ((p.y - pan.y) / zoom).toDouble()

    /** Zoom by [factor] about [focus] (screen pixels), within 1 %…6400 % of a document unit per pixel. */
    fun zoomBy(factor: Float, focus: Offset) {
        if (zoom <= 0f || !factor.isFinite() || factor <= 0f) return
        val z = (zoom * factor).coerceIn(0.01f, 64f)
        val k = z / zoom
        pan = focus + (pan - focus) * k
        zoom = z
    }

    fun panBy(d: Offset) { if (d.x.isFinite() && d.y.isFinite()) pan += d }

    /** Fit [page] (document units) in the viewport with a margin, centred. */
    fun fit(page: Rect, margin: Float = 24f) {
        if (viewport.width <= 0f || viewport.height <= 0f || page.width <= 0f || page.height <= 0f) return
        val z = min((viewport.width - 2 * margin) / page.width, (viewport.height - 2 * margin) / page.height).coerceIn(0.01f, 64f)
        zoom = z
        pan = Offset((viewport.width - page.width * z) / 2f - page.left * z, (viewport.height - page.height * z) / 2f - page.top * z)
        fittedTo = page
    }
}

/** A pen's pose read from the platform's own event, where Compose's common API does not carry it. */
class PenPose(val tiltX: Float?, val tiltY: Float?, val rotation: Float?, val barrel: Boolean)

/** Tilt, rotation and the side button for [change], from the platform event behind [event]; null where the platform gives none. */
expect fun penPose(event: PointerEvent, change: PointerInputChange): PenPose?

/** What the art workspace hands its canvas: the tool in hand, the engine's state, and ways to act. */
class WorkspaceContext(
    val tool: String,
    val state: kotlinx.serialization.json.JsonElement?,
    val revision: Int,
    /** Run a command; [then] gets its answer (null on failure, which is also shown). The canvas re-reads after it. */
    val run: (command: String, params: kotlinx.serialization.json.JsonObject, then: (kotlinx.serialization.json.JsonElement?) -> Unit) -> Unit,
    /** Call a method without re-reading after it (a question, or a stream of pointer events); [then] gets its answer, null on failure, which is not shown. */
    val call: (method: String, params: kotlinx.serialization.json.JsonObject, then: (kotlinx.serialization.json.JsonElement?) -> Unit) -> Unit,
    /** Re-read the document now (the end of a streamed gesture). */
    val refresh: () -> Unit,
    val status: (String) -> Unit,
)

val LocalWorkspace = staticCompositionLocalOf<WorkspaceContext?> { null }

private fun deviceOf(t: PointerType): InputDevice = when (t) {
    PointerType.Stylus -> InputDevice.Pen
    PointerType.Eraser -> InputDevice.Eraser
    PointerType.Touch -> InputDevice.Touch
    else -> InputDevice.Mouse
}

/**
 * The canvas. [page] is the document's page in document units (an artboard, the image), [image] its
 * picture as last rendered (any resolution: it is scaled to the page), [overlay] what the app draws
 * over it in screen space (selection, the stroke being drawn, a brush cursor). [onInput] gets the
 * input for the tool in hand; [panTool] true makes a one-pointer drag pan instead (the Hand tool).
 */
@Composable
fun ArtCanvas(
    page: Rect?,
    image: ImageBitmap?,
    view: CanvasView,
    onInput: (CanvasInput) -> Unit,
    modifier: Modifier = Modifier,
    panTool: Boolean = false,
    overlay: DrawScope.(CanvasView) -> Unit = {},
) {
    val input by rememberUpdatedState(onInput)
    val handPans by rememberUpdatedState(panTool)
    // Palm rejection: once a pen has touched this canvas, fingers navigate and never draw.
    var penSeen by remember { mutableStateOf(false) }
    LaunchedEffect(page, view.viewport) {
        if (page != null && view.viewport.width > 0f && (view.fittedTo == null || view.fittedTo?.size != page.size)) view.fit(page)
    }
    // Clipped: a page panned or zoomed past the canvas's edges must not paint over the tools, panels and bars around it.
    BoxWithConstraints(modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val pageColor = MaterialTheme.colorScheme.outlineVariant
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    var stroke: PointerId? = null
                    var strokeDevice = InputDevice.Mouse
                    var panning: PointerId? = null
                    var lastPan = Offset.Zero
                    while (true) {
                        val e = awaitPointerEvent()
                        val km = e.keyboardModifiers
                        val mods = CanvasMods(km.isShiftPressed, km.isAltPressed, km.isCtrlPressed || km.isMetaPressed)
                        fun point(c: PointerInputChange, pos: Offset = c.position, pose: PenPose? = penPose(e, c)): CanvasPoint {
                            val (x, y) = view.toDoc(pos)
                            val pen = c.type == PointerType.Stylus || c.type == PointerType.Eraser
                            return CanvasPoint(x, y, if (pen) c.pressure.coerceIn(0f, 1f) else 1f, pose?.tiltX, pose?.tiltY, pose?.rotation, c.uptimeMillis)
                        }
                        if (e.type == PointerEventType.Scroll) {
                            val c = e.changes.firstOrNull() ?: continue
                            val d = c.scrollDelta
                            if (mods.command) view.zoomBy(exp(-d.y * 0.12f), c.position) else view.panBy(Offset(-d.x * 40f, -d.y * 40f))
                            c.consume()
                            continue
                        }
                        val pens = e.changes.filter { it.type == PointerType.Stylus || it.type == PointerType.Eraser }
                        if (pens.isNotEmpty()) penSeen = true
                        val touches = e.changes.filter { it.type == PointerType.Touch && it.pressed }
                        // Two fingers (or more): pan and pinch. A finger stroke that just began is taken back.
                        if (stroke == null || strokeDevice == InputDevice.Touch) {
                            if (touches.size >= 2) {
                                if (stroke != null) { input(CanvasInput(CanvasPhase.Cancel, emptyList(), InputDevice.Touch, mods)); stroke = null }
                                panning = null
                                val zoom = e.calculateZoom(); val pan = e.calculatePan(); val centroid = e.calculateCentroid()
                                if (zoom != 1f && centroid.isSpecified()) view.zoomBy(zoom, centroid)
                                view.panBy(pan)
                                e.changes.forEach { it.consume() }
                                continue
                            }
                        }
                        // Pan: the Hand tool, a middle-button drag, or one finger once a pen has been seen.
                        val panner = e.changes.firstOrNull { c ->
                            c.pressed && (handPans || e.buttons.isTertiaryPressed || (c.type == PointerType.Touch && penSeen))
                        }
                        if (stroke == null && (panning != null || (panner != null && panner.previousPressed.not()))) {
                            val c = e.changes.firstOrNull { it.id == panning } ?: panner
                            if (c != null && c.pressed) {
                                if (panning == null) { panning = c.id; lastPan = c.position } else { view.panBy(c.position - lastPan); lastPan = c.position }
                                c.consume()
                                continue
                            } else panning = null
                        }
                        for (c in e.changes) {
                            val device = deviceOf(c.type)
                            // While a pen is down, nothing else draws.
                            if (stroke != null && c.id != stroke) { c.consume(); continue }
                            val pose = penPose(e, c)
                            val barrel = pose?.barrel == true
                            when {
                                stroke == null && c.pressed && !c.previousPressed -> {
                                    if (device == InputDevice.Touch && penSeen) continue
                                    if (device == InputDevice.Mouse && !e.buttons.isPrimaryPressed) continue
                                    stroke = c.id; strokeDevice = device
                                    input(CanvasInput(CanvasPhase.Down, listOf(point(c, pose = pose)), device, mods, barrel))
                                    c.consume()
                                }
                                c.id == stroke && c.pressed -> {
                                    // Every sample the platform batched since the last frame, then this one.
                                    val pts = c.historical.map { h -> point(c, h.position, pose) } + point(c, pose = pose)
                                    input(CanvasInput(CanvasPhase.Drag, pts, device, mods, barrel))
                                    c.consume()
                                }
                                c.id == stroke && !c.pressed -> {
                                    input(CanvasInput(CanvasPhase.Up, listOf(point(c, pose = pose)), device, mods, barrel))
                                    stroke = null
                                    c.consume()
                                }
                                stroke == null && !c.pressed && e.type == PointerEventType.Move && device != InputDevice.Touch ->
                                    input(CanvasInput(CanvasPhase.Hover, listOf(point(c, pose = pose)), device, mods, barrel))
                            }
                        }
                    }
                }
            },
        ) {
            view.viewport = size
            val z = view.zoom
            if (page != null && z > 0f) {
                val tl = view.toScreen(page.left.toDouble(), page.top.toDouble())
                val sz = Size(page.width * z, page.height * z)
                // A thin frame and a soft shadow set the page off the pasteboard.
                drawRect(Color.Black.copy(alpha = 0.25f), tl + Offset(2f, 3f), sz)
                drawRect(Color.White, tl, sz)
                if (image != null) {
                    drawImage(
                        image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()), dstSize = IntSize(max(1, sz.width.roundToInt()), max(1, sz.height.roundToInt())),
                        filterQuality = if (sz.width > image.width) FilterQuality.None else FilterQuality.Medium,
                    )
                }
                drawRect(pageColor, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            }
            overlay(view)
        }
        ZoomPill(view, page, Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

private fun Offset.isSpecified() = x.isFinite() && y.isFinite()

/** Zoom out, the zoom, zoom in, fit: in reach on a touch screen with no wheel or keyboard. */
@Composable
private fun ZoomPill(view: CanvasView, page: Rect?, modifier: Modifier) {
    val centre = Offset(view.viewport.width / 2f, view.viewport.height / 2f)
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clickable { view.zoomBy(1f / 1.25f, centre) }, contentAlignment = Alignment.Center) { Icon(Lucide.ZoomOut, contentDescription = "Zoom out", modifier = Modifier.size(16.dp)) }
        Text("${(view.zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
        Box(Modifier.size(32.dp).clickable { view.zoomBy(1.25f, centre) }, contentAlignment = Alignment.Center) { Icon(Lucide.ZoomIn, contentDescription = "Zoom in", modifier = Modifier.size(16.dp)) }
        Box(Modifier.size(32.dp).clickable { page?.let { view.fit(it) } }, contentAlignment = Alignment.Center) { Icon(Lucide.Maximize, contentDescription = "Fit the page", modifier = Modifier.size(16.dp)) }
    }
}

/** The render scale to ask for at [zoom] (screen pixels per document unit): rounded to half-steps of two, so a pinch doesn't re-render at every frame. */
fun renderScaleFor(zoom: Float, maxScale: Float = 16f): Float {
    if (zoom <= 0f || !zoom.isFinite()) return 1f
    val step = kotlin.math.round(kotlin.math.ln(zoom.toDouble()) / kotlin.math.ln(2.0) * 2.0) / 2.0
    return kotlin.math.min(maxScale.toDouble(), kotlin.math.max(0.05, 2.0.pow(step))).toFloat()
}

/**
 * What an app's own File actions (Open, Save, Save As, Export) get: a file picker ([pick]: save or
 * open, with a suggested name; null when cancelled; it may block, so call it off the UI thread),
 * [publish] to copy a written file to where the person chose, the engine ([call], on its lock; null
 * on failure, which is shown), and ways to re-read and report.
 */
class FileIo(
    val pick: (save: Boolean, suggestedName: String) -> String?,
    val publish: (path: String) -> Unit,
    val call: suspend (method: String, params: kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonElement?,
    val refresh: () -> Unit,
    val status: (String) -> Unit,
)

/** [tabs] with [items] first in the File menu (a File menu is added when there is none). */
fun addToFileMenu(tabs: List<RibbonTab>, items: List<RibbonItem>): List<RibbonTab> {
    if (items.isEmpty()) return tabs
    val group = RibbonGroup("Document", items)
    return if (tabs.any { it.name == "File" }) tabs.map { if (it.name == "File") RibbonTab(it.name, listOf(group) + it.groups) else it }
    else listOf(RibbonTab("File", listOf(group))) + tabs
}
