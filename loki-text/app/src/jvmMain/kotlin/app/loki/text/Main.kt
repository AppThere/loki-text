package app.loki.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap =
    Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * 4).toComposeImageBitmap()

actual fun pickFile(save: Boolean): String? {
    val dialog = FileDialog(null as Frame?, if (save) "Save As" else "Open", if (save) FileDialog.SAVE else FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return java.io.File(dialog.directory ?: "", name).path
}

actual fun publishFile(path: String) {}

actual fun printJobPath(): String = java.nio.file.Files.createTempFile("loki-print-", ".pdf").toFile().apply { deleteOnExit() }.path

actual fun printPdf(path: String, jobName: String): String? {
    val file = java.io.File(path)
    if (!file.isFile) return "The document was not exported for printing"
    if (!java.awt.Desktop.isDesktopSupported()) return "This system has no print handler"
    val desktop = java.awt.Desktop.getDesktop()
    return try {
        when {
            desktop.isSupported(java.awt.Desktop.Action.PRINT) -> desktop.print(file)
            // Many systems only open a PDF; its viewer has the print dialog.
            desktop.isSupported(java.awt.Desktop.Action.OPEN) -> desktop.open(file)
            else -> return "This system has no print handler"
        }
        null
    } catch (e: Exception) {
        "Could not print: ${e.message ?: e::class.simpleName}"
    }
}

actual fun clipboardText(): String? = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()

actual fun setClipboardText(text: String) {
    runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}

/** One open document window; [path] is the file it opens with, or null for a new document. */
private class DocWindow(val id: Int, val path: String?)

fun main() = application {
    val windows = remember { mutableStateListOf(DocWindow(0, null)) }
    var nextId by remember { mutableStateOf(1) }
    // Each document has its own window, and the application ends with the last one.
    for (w in windows) key(w.id) {
        // Closing asks about unsaved changes first; App calls onClose once that is settled.
        var closeRequest by remember { mutableStateOf(0) }
        Window(onCloseRequest = { closeRequest++ }, title = "Loki Text") {
            App(
                closeRequest,
                onClose = { windows.remove(w); if (windows.isEmpty()) exitApplication() },
                initialPath = w.path,
                onNewWindow = { path -> windows.add(DocWindow(nextId++, path)) },
            )
        }
    }
}

actual fun previewFontFamily(identity: String, bytes: ByteArray): androidx.compose.ui.text.font.FontFamily? =
    runCatching { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.platform.Font(identity, bytes)) }.getOrNull()
