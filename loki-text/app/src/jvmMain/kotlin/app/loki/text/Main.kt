package app.loki.text

import androidx.compose.foundation.layout.Row
import app.loki.kit.windowDrag
import app.loki.kit.IS_MAC
import app.loki.kit.MENU_MINIMIZE
import app.loki.kit.MENU_ZOOM
import app.loki.kit.LokiMenuBar
import app.loki.kit.MenuBridge
import app.loki.kit.installMacAppMenu
import app.loki.kit.nameMacApplication
import app.loki.kit.useOwnFrame
import app.loki.kit.toggleMaximized
import app.loki.kit.WindowButtons
import app.loki.kit.ResizeEdges
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
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
import app.loki.text.res.Res
import app.loki.text.res.loki_text_icon
import org.jetbrains.compose.resources.painterResource
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

actual fun pickFile(save: Boolean, suggestedName: String): String? {
    val dialog = FileDialog(null as Frame?, if (save) "Save As" else "Open", if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save && suggestedName.isNotBlank()) dialog.file = suggestedName
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

fun main() {
    nameMacApplication("Loki Text")
    lokiApplication()
}

private fun lokiApplication() = application {
    val windows = remember { mutableStateListOf(DocWindow(0, null)) }
    // Every window's state and AWT window, for Switch Windows, Arrange All, Side by Side and full screen.
    val registry = remember { DesktopWindows() }
    var nextId by remember { mutableStateOf(1) }
    // Closing asks about unsaved changes first; App calls onClose once that is settled. Kept here so Quit can ask every window.
    val closeRequests = remember { mutableStateMapOf<Int, Int>() }
    // The menu bar of each window, and the one in front, which About and Settings in the application menu act on.
    val bridges = remember { mutableStateMapOf<Int, MenuBridge>() }
    var front by remember { mutableStateOf(0) }
    if (IS_MAC) LaunchedEffect(Unit) {
        installMacAppMenu(
            onAbout = { bridges[front]?.run("app.about") },
            onSettings = { bridges[front]?.run("file.options") },
            // Quit closes the windows one by one, each asking about unsaved changes; the last one ends the app.
            onQuit = { for (w in windows) closeRequests[w.id] = (closeRequests[w.id] ?: 0) + 1 },
        )
    }
    // Each document has its own window, and the application ends with the last one.
    for (w in windows) key(w.id) {
        val closeRequest = closeRequests[w.id] ?: 0
        val requestClose = { closeRequests[w.id] = (closeRequests[w.id] ?: 0) + 1 }
        val bridge = remember { MenuBridge() }
        DisposableEffect(bridge) { bridges[w.id] = bridge; onDispose { bridges.remove(w.id); closeRequests.remove(w.id) } }
        val windowState = rememberWindowState()
        val ownFrame = remember { useOwnFrame() }
        val host = remember(w.id) { registry.hostFor(w.id) }
        DisposableEffect(w.id) { registry.states[w.id] = windowState; onDispose { registry.remove(w.id) } }
        Window(onCloseRequest = { requestClose() }, title = "Loki Text", icon = painterResource(Res.drawable.loki_text_icon), state = windowState, undecorated = ownFrame) {
            DisposableEffect(window) { registry.awt[w.id] = window; onDispose { } }
            DisposableEffect(window) {
                val l = object : java.awt.event.WindowAdapter() {
                    override fun windowActivated(e: java.awt.event.WindowEvent?) { front = w.id }
                }
                window.addWindowListener(l)
                onDispose { window.removeWindowListener(l) }
            }
            if (IS_MAC) LokiMenuBar(bridge, mac = true, menus = { b, mac -> appMenus(b.catalog, mac) }) { id ->
                when (id) {
                    MENU_MINIMIZE -> windowState.isMinimized = true
                    MENU_ZOOM -> windowState.toggleMaximized()
                    else -> bridge.run(id)
                }
            }
            // On a JetBrains Runtime with custom title bars the app draws the title bar and the system keeps the frame. Where the
            // runtime has none (Linux) the window is undecorated and the app draws the frame; elsewhere the native bar stays.
            val installed = remember(window) { if (ownFrame) null else installCustomTitleBar(window, TITLE_BAR_DP) }
            var insets by remember { mutableStateOf(0f to 0f) }
            LaunchedEffect(installed, windowState.placement) {
                if (installed != null) { delay(150); insets = installed.leftInset to installed.rightInset }
            }
            // Linux has no system caption buttons in a custom bar, so the app draws them.
            val ownButtons = System.getProperty("os.name").orEmpty().startsWith("Linux")
            val maximize = { windowState.toggleMaximized() }
            val titleBar = if (ownFrame) TitleBarConfig(
                height = TITLE_BAR_DP.dp, leftInset = 0.dp, rightInset = 0.dp, onControl = {},
                windowButtons = {
                    WindowButtons(
                        maximized = windowState.placement == WindowPlacement.Maximized,
                        onMinimize = { windowState.isMinimized = true }, onMaximize = maximize, onClose = { requestClose() },
                    )
                },
                drag = Modifier.windowDrag(window, onDoubleClick = maximize),
            ) else installed?.let { bar ->
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
                                onClose = { requestClose() },
                            )
                        }
                    },
                )
            }
            // The first window shows the splash while the engine starts; windows opened later start at once.
            WithSplash(enabled = w.id == 0) {
            Box(Modifier.fillMaxSize().then(if (ownFrame && windowState.placement != WindowPlacement.Maximized) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant) else Modifier)) {
            App(
                closeRequest,
                onClose = { windows.remove(w); if (windows.isEmpty()) exitApplication() },
                initialPath = w.path,
                onNewWindow = { path -> windows.add(DocWindow(nextId++, path)) },
                titleBar = titleBar,
                menuBridge = if (IS_MAC) bridge else null,
                startScreen = w.id == 0,
                windowHost = host,
            )
            if (ownFrame && windowState.placement != WindowPlacement.Maximized) ResizeEdges(window)
            }
            }
        }
    }
}

actual fun previewFontFamily(identity: String, bytes: ByteArray): androidx.compose.ui.text.font.FontFamily? =
    runCatching { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.platform.Font(identity, bytes)) }.getOrNull()
