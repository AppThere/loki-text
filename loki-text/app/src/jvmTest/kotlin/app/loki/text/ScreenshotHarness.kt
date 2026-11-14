package app.loki.text

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Draws the real app off screen and saves PNGs, so the layout can be looked at without a display:
 *
 *     LOKI_LIB=$PWD/target/debug LOKI_SHOTS=/tmp/shots \
 *     LOKI_SHOT_SCRIPT='size=390x800@2; wait; shot=phone.png; click=30,62; wait; shot=menu.png' \
 *     gradle :app:jvmTest --tests '*ScreenshotHarness*'
 *
 * Steps: `size=WxH@density` (dp; starts a fresh app), `wait[=ms]`, `shot=name.png`, `click=x,y` (dp),
 * `drag=x1,y1,x2,y2` (dp). `LOKI_SHOT_DOC` names a document to open at start. Without `LOKI_SHOTS` the test does nothing,
 * as it needs the native engine library (`scripts/build-desktop.sh`, or `cargo build -p loki-ffi`).
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotHarness {
    @Test
    fun runScript() {
        val dir = System.getenv("LOKI_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        System.getenv("LOKI_LIB")?.let { System.setProperty("loki.lib", it) }
        File(dir).mkdirs()
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
            for (step in (System.getenv("LOKI_SHOT_SCRIPT") ?: "size=1200x800@1; wait; shot=app.png").split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val name = step.substringBefore('=')
                val arg = step.substringAfter('=', "")
                when (name) {
                    "size" -> {
                        scene?.close()
                        val (w, h) = arg.substringBefore('@').split('x').map { it.toInt() }
                        density = arg.substringAfter('@', "1").toFloat()
                        scene = ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) { App(initialPath = System.getenv("LOKI_SHOT_DOC")?.takeIf { it.isNotBlank() }) }
                    }
                    "wait" -> pump(arg.toLongOrNull() ?: 1500)
                    "shot" -> scene?.let { s ->
                        val png = s.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG) ?: error("no image")
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
}
