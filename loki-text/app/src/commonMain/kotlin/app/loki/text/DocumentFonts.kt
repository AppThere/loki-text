package app.loki.text

import app.loki.text.engine.Engine
import app.loki.text.res.Res
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Metric-compatible stand-ins for the fonts Word documents ask for (Calibri, Cambria, Arial, Times
 * New Roman, Courier New, Georgia), bundled in composeResources/files/doc-fonts under the SIL OFL.
 * Android has none of them, so without these documents reflow with whatever font is left.
 */
internal val DOCUMENT_FONTS: List<String> = buildList {
    for (family in listOf("Carlito", "Caladea", "Tinos", "Cousine")) {
        for (style in listOf("Regular", "Bold", "Italic", "BoldItalic")) add("$family-$style.ttf")
    }
    add("Arimo-VariableFont_wght.ttf")
    add("Arimo-Italic-VariableFont_wght.ttf")
    add("Gelasio-VariableFont_wght.ttf")
    add("Gelasio-Italic-VariableFont_wght.ttf")
}

/** The engine's font database is process-wide, so the fonts go in once however many documents open. */
private var installed = false

/**
 * Adds [DOCUMENT_FONTS] to the engine (`font.add`). A font that fails to read or load is logged and
 * skipped. Returns true if this call added them, so the caller can lay the document out again.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal suspend fun installDocumentFonts(engine: Engine, lock: Mutex): Boolean {
    if (installed) return false
    val files = DOCUMENT_FONTS.mapNotNull { name ->
        runCatching { name to Base64.encode(Res.readBytes("files/doc-fonts/$name")) }
            .onFailure { println("document font $name not read: ${it.message}") }
            .getOrNull()
    }
    lock.withLock {
        withContext(Dispatchers.Default) {
            for ((name, data) in files) {
                runCatching { engine.execute("font.add", buildJsonObject { put("data", data); put("name", name) }) }
                    .onFailure { println("document font $name not added: ${it.message}") }
            }
        }
    }
    installed = true
    return true
}
