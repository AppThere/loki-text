package app.loki.text.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** Where to draw the caret and selection; see `selection_geometry` in `ffi/src/lib.rs`. */
class SelectionGeometry(val caret: PageRect?, val rects: List<PageRect>)

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
    /** The window size in points, for the reflowing views (Web Layout, Read Mode). */
    fun setViewport(widthPt: Float, heightPt: Float) {
        call("loki.viewport", buildJsonObject { put("width", widthPt); put("height", heightPt) })
    }

    /** Caret and selection boxes, or empty geometry when the reply is malformed. */
    fun selection(): SelectionGeometry {
        val r = call("loki.selection").jsonObject
        fun box(o: JsonObject, w: String, h: String) = PageRect(
            o["page"]?.jsonPrimitive?.intOrNull ?: 0,
            o["x"]?.jsonPrimitive?.floatOrNull ?: 0f,
            o[if (w == "width") "top" else "y"]?.jsonPrimitive?.floatOrNull ?: 0f,
            o[w]?.jsonPrimitive?.floatOrNull ?: 0f,
            o[h]?.jsonPrimitive?.floatOrNull ?: 0f,
        )
        val caret = (r["caret"] as? JsonObject)?.let { box(it, "width", "height") }
        val rects = (r["rects"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> box(o, "w", "h") } } ?: emptyList()
        return SelectionGeometry(caret, rects)
    }

    fun renderPage(page: Int, scale: Float): PageBitmap? = native.renderPage(page, scale)

    override fun close() = native.close()
}
