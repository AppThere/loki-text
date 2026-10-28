package app.loki.text

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun rgbaToImageBitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap {
    val px = IntArray(width * height) { i ->
        val o = i * 4
        val r = rgba[o].toInt() and 0xFF
        val g = rgba[o + 1].toInt() and 0xFF
        val b = rgba[o + 2].toInt() and 0xFF
        val a = rgba[o + 3].toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

// TODO: Storage Access Framework picker (ActivityResultContracts.OpenDocument / CreateDocument).
actual fun pickFile(save: Boolean): String? = null

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
