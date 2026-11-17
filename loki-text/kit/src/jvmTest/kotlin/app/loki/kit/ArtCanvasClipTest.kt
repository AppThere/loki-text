package app.loki.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtCanvasClipTest {
    /** A page zoomed far past the canvas must not paint over what is beside it (the tool rail, the dock). */
    @Test
    fun a_zoomed_page_stays_inside_the_canvas() {
        val view = CanvasView()
        val scene = ImageComposeScene(400, 200, Density(1f)) {
            MaterialTheme {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(100.dp).fillMaxHeight().background(Color.Red))
                    ArtCanvas(Rect(0f, 0f, 100f, 100f), remember { ImageBitmap(100, 100) }, view, onInput = {}, modifier = Modifier.width(300.dp).fillMaxHeight())
                }
            }
        }
        try {
            // Frames for the canvas to measure itself and fit the page.
            for (f in 0..5) { scene.render(f * 16_000_000L); Thread.sleep(20) }
            val fitted = org.jetbrains.skia.Bitmap.makeFromImage(scene.render(100_000_000))
            assertEquals(0xFFFFFFFF.toInt(), fitted.getColor(250, 100), "the page is not drawn in the canvas: #${Integer.toHexString(fitted.getColor(250, 100))}, zoom ${view.zoom}")
            // Zoom in about a point just inside the page's left edge: the page grows past the canvas's
            // left edge, under where the panel is (the page is 152 px wide at x 74 once fitted).
            view.zoomBy(20f, Offset(80f, 100f))
            // A state write outside composition reaches the scene once snapshot changes are announced.
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            for (f in 0..3) scene.render(200_000_000L + f * 16_000_000L)
            val image = scene.render(300_000_000)
            val pixels = org.jetbrains.skia.Bitmap.makeFromImage(image)
            // Scene x 80 is the panel; unclipped, the page (from canvas x −40) would be drawn there.
            val c = pixels.getColor(80, 100)
            assertEquals(0xFFFF0000.toInt(), c, "the panel beside the canvas was painted over: #${Integer.toHexString(c)}")
        } finally {
            scene.close()
        }
    }
}
