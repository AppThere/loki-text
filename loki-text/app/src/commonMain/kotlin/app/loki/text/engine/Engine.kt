package app.loki.text.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A rendered page: straight-alpha RGBA8, `width * height * 4` bytes. */
class PageBitmap(val width: Int, val height: Int, val pages: Int, val rgba: ByteArray)

/** Platform binding to the Rust `loki-ffi` C ABI. One instance per open document. */
expect class NativeEngine() : AutoCloseable {
    /** Raw `loki_call`: returns the `{"ok":…}` JSON text. */
    fun callRaw(method: String, paramsJson: String?): String
    /** Raw `loki_render_page`; null when the page does not exist. */

    fun renderPage(page: Int, scale: Float): PageBitmap?
    override fun close()
}

/** A box in points from a page's top-left. */
class PageRect(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * The selected picture, shape or text box: its frame in page points before rotation, the turn about its
 * centre in degrees (clockwise), whether it floats (an inline one is placed by the text) and its [kind].
 */
class ObjectFrame(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float, val floating: Boolean, val kind: String)

/** Where to draw the caret and selection; see `selection_geometry` in `ffi/src/lib.rs`. [obj] is set when the selection is one object, [hf] ("header" or "footer") when the caret is in one. */
class SelectionGeometry(val caret: PageRect?, val rects: List<PageRect>, val obj: ObjectFrame? = null, val hf: String? = null)

/** Parses `loki.selection`; boxes that are not usable numbers are left out. */
fun parseSelectionGeometry(r: JsonObject): SelectionGeometry {
    fun box(o: JsonObject, w: String, h: String) = PageRect(
        o["page"]?.jsonPrimitive?.intOrNull ?: 0,
        o["x"]?.jsonPrimitive?.floatOrNull ?: 0f,
        o[if (w == "width") "top" else "y"]?.jsonPrimitive?.floatOrNull ?: 0f,
        o[w]?.jsonPrimitive?.floatOrNull ?: 0f,
        o[h]?.jsonPrimitive?.floatOrNull ?: 0f,
    )
    val caret = (r["caret"] as? JsonObject)?.let { box(it, "width", "height") }
    val rects = (r["rects"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> box(o, "w", "h") } } ?: emptyList()
    val obj = (r["object"] as? JsonObject)?.let { o ->
        fun f(k: String) = o[k]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() }
        val (x, y, w, h) = listOf(f("x"), f("y"), f("w"), f("h"))
        if (x == null || y == null || w == null || h == null || w <= 0f || h <= 0f) null
        else ObjectFrame(
            o["page"]?.jsonPrimitive?.intOrNull ?: 0, x, y, w, h, f("rotation") ?: 0f,
            o["floating"]?.jsonPrimitive?.booleanOrNull ?: false, o["kind"]?.jsonPrimitive?.contentOrNull ?: "object",
        )
    }
    val hf = r["hf"]?.jsonPrimitive?.contentOrNull?.takeIf { it == "header" || it == "footer" }
    return SelectionGeometry(caret, rects, obj, hf)
}

class EngineException(message: String) : Exception(message)

/**
 * The editing session. Everything the UI does is a WordCraft command (`format.bold`,
 * `text.insert`, …), so the UI stays thin: [execute] by id, [inspect] to read state, [renderPage]
 * to draw. Calls block; run them off the UI thread (see [EngineHost]).
 */
class Engine(private val native: NativeEngine = NativeEngine()) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }

    fun call(method: String, params: JsonObject? = null): JsonElement {
        val reply = json.parseToJsonElement(native.callRaw(method, params?.toString())).jsonObject
        if (reply["ok"]?.jsonPrimitive?.contentOrNull == "true") return reply["result"] ?: JsonObject(emptyMap())
        throw EngineException(reply["error"]?.jsonPrimitive?.contentOrNull ?: "unknown engine error")
    }

    fun execute(command: String, params: JsonObject = JsonObject(emptyMap())): JsonElement =
        call("engine.execute", buildJsonObject {
            put("command", command)
            put("params", params)
        })

    fun insertText(text: String) = execute("text.insert", buildJsonObject { put("text", text) })
    fun inspect(): JsonElement = call("document.inspect")
    fun commands(): JsonElement = call("engine.commands")
    /** Commands that can't run now (with the reason) and the toggles that are on; see `ffi/src/state.rs`. */
    fun commandState(): JsonElement = call("loki.commandState")
    /** The window size in points, for the reflowing views (Web Layout, Read Mode). */
    fun setViewport(widthPt: Float, heightPt: Float) {
        call("loki.viewport", buildJsonObject { put("width", widthPt); put("height", heightPt) })
    }

    /**
     * What commands asked the front end to do since the last call (`{"open": "exportPdf"}`,
     * `{"interfaceTheme": "dark"}`, `{"close": true}`), oldest first; reading them clears them.
     */
    fun uiRequests(): List<JsonObject> = (call("loki.uiRequests") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** Caret and selection boxes, or empty geometry when the reply is malformed. */
    fun selection(): SelectionGeometry = parseSelectionGeometry(call("loki.selection").jsonObject)

    fun renderPage(page: Int, scale: Float): PageBitmap? = native.renderPage(page, scale)

    override fun close() = native.close()
}
