package app.loki.text

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap =
    Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * 4).toComposeImageBitmap()

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Loki Text") { App() }
}
