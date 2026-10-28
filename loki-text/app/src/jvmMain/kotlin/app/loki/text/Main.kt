package app.loki.text

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.FileDialog
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

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Loki Text") { App() }
}
