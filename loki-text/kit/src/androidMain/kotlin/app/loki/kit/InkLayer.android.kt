package app.loki.kit

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

/**
 * Front-buffered ink (Android 10+, androidx.graphics): each segment is drawn into the front buffer
 * as soon as it arrives; a commit moves the stroke into the multi-buffered layer, where it stays
 * until [clear]. The layer is a transparent SurfaceView over the canvas, ordered on top so the ink
 * shows above the app's own frames; it takes no touches (the canvas, composed after it, does).
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class FrontBufferInk : InkLayer, CanvasFrontBufferedRenderer.Callback<InkSegment> {
    private var renderer: CanvasFrontBufferedRenderer<InkSegment>? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override val active: Boolean get() = renderer?.isValid() == true

    fun attach(view: SurfaceView) {
        renderer?.release(true)
        renderer = runCatching { CanvasFrontBufferedRenderer(view, this) }.getOrNull()
    }

    fun detach() {
        renderer?.release(true)
        renderer = null
    }

    private fun drawOn(canvas: Canvas, s: InkSegment) {
        paint.color = s.color.toArgb()
        paint.strokeWidth = s.width.coerceAtLeast(1f)
        if (s.x0 == s.x1 && s.y0 == s.y1) canvas.drawPoint(s.x0, s.y0, paint) else canvas.drawLine(s.x0, s.y0, s.x1, s.y1, paint)
    }

    override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: InkSegment) = drawOn(canvas, param)

    override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<InkSegment>) {
        canvas.drawColor(0, BlendMode.CLEAR)
        for (s in params) drawOn(canvas, s)
    }

    override fun draw(segment: InkSegment) { renderer?.takeIf { it.isValid() }?.renderFrontBufferedLayer(segment) }
    override fun commit() { renderer?.takeIf { it.isValid() }?.commit() }
    override fun cancel() { renderer?.takeIf { it.isValid() }?.cancel() }
    override fun clear() { renderer?.takeIf { it.isValid() }?.clear() }
}

@Composable
actual fun rememberInkLayer(): InkLayer = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) FrontBufferInk() else NoInkLayer }

@Composable
actual fun InkSurface(layer: InkLayer, modifier: Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || layer !is FrontBufferInk) return
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                isClickable = false
                isFocusable = false
                layer.attach(this)
            }
        },
        modifier = modifier,
        onRelease = { layer.detach() },
    )
}
