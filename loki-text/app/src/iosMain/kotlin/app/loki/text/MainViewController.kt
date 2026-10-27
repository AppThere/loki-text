package app.loki.text

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.window.ComposeUIViewController
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

actual fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap =
    Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, width * 4).asComposeImageBitmap()

/** Called from the Xcode host app's SwiftUI/UIKit wrapper. */
fun MainViewController() = ComposeUIViewController { App() }
