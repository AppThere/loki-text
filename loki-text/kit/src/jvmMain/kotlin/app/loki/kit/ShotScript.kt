package app.loki.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Draws an app off screen and saves PNGs into [dir], so its layout can be looked at with no
 * display, device or emulator. [script] is steps separated by `;`:
 *
 * * `size=WxH@density` (dp) starts a fresh app of that window size;
 * * `wait[=ms]` lets effects and the engine catch up (default 1500);
 * * `shot=name.png` saves what is on screen;
 * * `click=x,y` and `drag=x1,y1,x2,y2` (dp) send pointer input.
 *
 * It cannot type: open a prepared document instead. It draws the common code with the desktop's
 * Skia, so it proves layout at a size, not the Android or iOS platform edge.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun runShotScript(dir: File, script: String, content: @Composable () -> Unit) {
    dir.mkdirs()
    var scene: ImageComposeScene? = null
    var density = 1f
    fun pump(ms: Long) {
        val s = scene ?: return
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            s.render(System.nanoTime())
            Thread.sleep(30)
        }
    }
    try {
        for (step in script.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val name = step.substringBefore('=')
            val arg = step.substringAfter('=', "")
            when (name) {
                "size" -> {
                    scene?.close()
                    val (w, h) = arg.substringBefore('@').split('x').map { it.trim().toInt() }
                    density = arg.substringAfter('@', "1").toFloat()
                    scene = ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) { content() }
                }
                "wait" -> pump(arg.toLongOrNull() ?: 1500)
                "shot" -> scene?.let { s ->
                    val png = s.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG) ?: error("no image for $arg")
                    File(dir, arg).writeBytes(png.bytes)
                }
                "click" -> scene?.let { s ->
                    val (x, y) = arg.split(',').map { it.trim().toFloat() * density }
                    s.sendPointerEvent(PointerEventType.Move, Offset(x, y))
                    s.sendPointerEvent(PointerEventType.Press, Offset(x, y))
                    pump(60)
                    s.sendPointerEvent(PointerEventType.Release, Offset(x, y))
                    pump(400)
                }
                "drag" -> scene?.let { s ->
                    val v = arg.split(',').map { it.trim().toFloat() * density }
                    s.sendPointerEvent(PointerEventType.Move, Offset(v[0], v[1]))
                    s.sendPointerEvent(PointerEventType.Press, Offset(v[0], v[1]))
                    for (i in 1..10) {
                        s.sendPointerEvent(PointerEventType.Move, Offset(v[0] + (v[2] - v[0]) * i / 10f, v[1] + (v[3] - v[1]) * i / 10f))
                        pump(30)
                    }
                    s.sendPointerEvent(PointerEventType.Release, Offset(v[2], v[3]))
                    pump(400)
                }
                else -> error("unknown step `$step`")
            }
        }
    } finally {
        scene?.close()
    }
}
