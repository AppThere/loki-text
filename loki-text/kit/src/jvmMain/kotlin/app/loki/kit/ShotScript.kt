package app.loki.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
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
 * * `click=x,y` and `drag=x1,y1,x2,y2` (dp) send mouse input (the primary button);
 * * `pen=x1,y1,x2,y2[,eraser]` draws with a pen from one point to the other, its pressure rising and
 *   falling as a real stroke's does (`eraser`: with the eraser end); `finger=x1,y1,x2,y2` drags one finger.
 *
 * It cannot type: open a prepared document instead. It draws the common code with the desktop's
 * Skia, so it proves layout at a size, not the Android or iOS platform edge.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun runShotScript(dir: File, script: String, content: @Composable () -> Unit) {
    dir.mkdirs()
    // LOKI_SHOT_TOUCH=1 draws the phone and tablet layout (the touch top bar and menu sheet) on the desktop.
    if (System.getenv("LOKI_SHOT_TOUCH") == "1") isTouchPlatform = true
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
                    val held = PointerButtons(isPrimaryPressed = true)
                    s.sendPointerEvent(PointerEventType.Move, Offset(v[0], v[1]))
                    s.sendPointerEvent(PointerEventType.Press, Offset(v[0], v[1]), buttons = held)
                    for (i in 1..10) {
                        s.sendPointerEvent(PointerEventType.Move, Offset(v[0] + (v[2] - v[0]) * i / 10f, v[1] + (v[3] - v[1]) * i / 10f), buttons = held)
                        pump(30)
                    }
                    s.sendPointerEvent(PointerEventType.Release, Offset(v[2], v[3]))
                    pump(400)
                }
                "pen", "finger" -> scene?.let { s ->
                    val parts = arg.split(',').map { it.trim() }
                    val v = parts.take(4).map { it.toFloat() * density }
                    val type = when {
                        name == "finger" -> PointerType.Touch
                        parts.getOrNull(4) == "eraser" -> PointerType.Eraser
                        else -> PointerType.Stylus
                    }
                    val id = PointerId(1)
                    fun at(i: Int) = Offset(v[0] + (v[2] - v[0]) * i / 20f, v[1] + (v[3] - v[1]) * i / 20f)
                    // A light touch-down, firmer through the middle, lighter again at the lift.
                    fun pressure(i: Int) = 0.25f + 0.75f * kotlin.math.sin(Math.PI * i / 20.0).toFloat()
                    fun send(t: PointerEventType, i: Int, pressed: Boolean) =
                        s.sendPointerEvent(t, listOf(ComposeScenePointer(id, at(i), pressed, type, pressure(i))))
                    send(PointerEventType.Press, 0, true)
                    for (i in 1..20) { send(PointerEventType.Move, i, true); pump(15) }
                    send(PointerEventType.Release, 20, false)
                    pump(400)
                }
                else -> error("unknown step `$step`")
            }
        }
    } finally {
        scene?.close()
    }
}
