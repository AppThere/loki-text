package app.loki.text

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
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

/** Height of the title bar the app draws, in dp. */
private const val TITLE_BAR_DP = 40f

fun main() = application {
    val windows = remember { mutableStateListOf(DocWindow(0, null)) }
    var nextId by remember { mutableStateOf(1) }
    // Each document has its own window, and the application ends with the last one.
    for (w in windows) key(w.id) {
        // Closing asks about unsaved changes first; App calls onClose once that is settled.
        var closeRequest by remember { mutableStateOf(0) }
        val windowState = rememberWindowState()
        Window(onCloseRequest = { closeRequest++ }, title = "Loki Text", state = windowState) {
            // On a JetBrains Runtime the app draws the title bar; otherwise the native one stays.
            val installed = remember(window) { installCustomTitleBar(window, TITLE_BAR_DP) }
            var insets by remember { mutableStateOf(0f to 0f) }
            LaunchedEffect(installed, windowState.placement) {
                if (installed != null) { delay(150); insets = installed.leftInset to installed.rightInset }
            }
            // Linux has no system caption buttons in a custom bar, so the app draws them.
            val ownButtons = System.getProperty("os.name").orEmpty().startsWith("Linux")
            val titleBar = installed?.let { bar ->
                TitleBarConfig(
                    height = TITLE_BAR_DP.dp,
                    leftInset = insets.first.dp,
                    rightInset = insets.second.dp,
                    onControl = { bar.forceHitTest(it) },
                    windowButtons = if (!ownButtons) null else {
                        {
                            WindowButtons(
                                maximized = windowState.placement == WindowPlacement.Maximized,
                                onMinimize = { windowState.isMinimized = true },
                                onMaximize = { windowState.placement = if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized },
                                onClose = { closeRequest++ },
                            )
                        }
                    },
                )
            }
            App(
                closeRequest,
                onClose = { windows.remove(w); if (windows.isEmpty()) exitApplication() },
                initialPath = w.path,
                onNewWindow = { path -> windows.add(DocWindow(nextId++, path)) },
                titleBar = titleBar,
            )
        }
    }
}

/** Minimize, maximize/restore and close, for a custom title bar on a platform whose system does not draw them. */
@Composable
private fun WindowButtons(maximized: Boolean, onMinimize: () -> Unit, onMaximize: () -> Unit, onClose: () -> Unit) {
    Row {
        IconButton(onClick = onMinimize, modifier = Modifier.size(32.dp)) { Icon(Lucide.Minus, contentDescription = "Minimize", modifier = Modifier.size(16.dp)) }
        IconButton(onClick = onMaximize, modifier = Modifier.size(32.dp)) {
            Icon(if (maximized) Lucide.Copy else Lucide.Square, contentDescription = if (maximized) "Restore" else "Maximize", modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Lucide.X, contentDescription = "Close", modifier = Modifier.size(16.dp)) }
    }
}

actual fun previewFontFamily(identity: String, bytes: ByteArray): androidx.compose.ui.text.font.FontFamily? =
    runCatching { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.platform.Font(identity, bytes)) }.getOrNull()
