package app.loki.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** One piece of a stroke being drawn, in the canvas's pixels: a round-capped line (a dot when its ends meet). */
class InkSegment(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float, val color: Color)

/**
 * Ink drawn the moment a pen moves, ahead of the app's own frames: on Android 10 and later straight
 * into the display's front buffer (the one being scanned out), which saves the frame or two a
 * stroke otherwise waits for the app to draw it. A tool [draw]s each new piece as the input
 * arrives, [commit]s the stroke when the pen lifts (it stays on screen), and [clear]s it once the
 * engine's own picture of the stroke is showing; [cancel] drops a stroke that was taken back.
 *
 * Where it is not [active] (the desktop, iOS for now, older Android) a tool draws its wet ink in
 * the canvas's own overlay instead.
 */
interface InkLayer {
    val active: Boolean
    fun draw(segment: InkSegment)
    fun commit()
    fun cancel()
    fun clear()
}

/** No front buffer here: the canvas overlay draws the wet ink. */
object NoInkLayer : InkLayer {
    override val active = false
    override fun draw(segment: InkSegment) {}
    override fun commit() {}
    override fun cancel() {}
    override fun clear() {}
}

/** The platform's ink layer for one canvas. */
@Composable
expect fun rememberInkLayer(): InkLayer

/**
 * Where [layer] draws: put it in the same box as the canvas and BEFORE it, so the canvas still
 * takes every touch (the ink shows above it all the same). Nothing where the layer is not active.
 */
@Composable
expect fun InkSurface(layer: InkLayer, modifier: Modifier)
