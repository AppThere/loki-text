package app.loki.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The engine said no, in its own words. */
class EngineException(message: String) : Exception(message)

/**
 * The one call every Craft engine's C ABI offers (`loki_call`): a method and JSON params in, the
 * `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}` text out. An app's platform binding (JNA,
 * cinterop) implements this.
 */
interface EngineTransport {
    fun callRaw(method: String, paramsJson: String?): String
    fun close()
}

/**
 * The calls every front end makes, whatever the engine: run a command or a control-protocol
 * method by id, list the commands, read command state, take the engine's requests to its window.
 * Calls block and the session is not thread-safe: run them off the UI thread, one at a time.
 * An app extends this with what its own surface needs (rendering, selection geometry).
 */
open class EngineClient(private val transport: EngineTransport) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }

    /** Calls [method]; the result, or [EngineException] with the engine's reason. A reply that is not the expected JSON is also an [EngineException]. */
    fun call(method: String, params: JsonObject? = null): JsonElement {
        val reply = try {
            json.parseToJsonElement(transport.callRaw(method, params?.toString())) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw EngineException("the engine's reply to $method was not readable")
        if (reply["ok"]?.jsonPrimitive?.contentOrNull == "true") return reply["result"] ?: JsonObject(emptyMap())
        throw EngineException(reply["error"]?.jsonPrimitive?.contentOrNull ?: "unknown engine error")
    }

    fun execute(command: String, params: JsonObject = JsonObject(emptyMap())): JsonElement =
        call("engine.execute", buildJsonObject {
            put("command", command)
            put("params", params)
        })

    fun commands(): JsonElement = call("engine.commands")

    /** Commands that can't run now (with the reason) and the toggles that are on. */
    fun commandState(): JsonElement = call("loki.commandState")

    /**
     * What commands asked the front end to do since the last call (`{"open": "exportPdf"}`,
     * `{"interfaceTheme": "dark"}`, `{"close": true}`), oldest first; reading them clears them.
     */
    fun uiRequests(): List<JsonObject> = (call("loki.uiRequests") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    override fun close() = transport.close()
}
