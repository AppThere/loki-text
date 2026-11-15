package app.loki.kit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtCanvasTest {
    private fun view(w: Float = 1000f, h: Float = 800f) = CanvasView().apply { viewport = Size(w, h) }

    @Test
    fun a_page_is_fitted_whole_and_centred() {
        val v = view()
        v.fit(Rect(0f, 0f, 612f, 792f), margin = 24f)
        assertTrue(abs(v.zoom - (800f - 48f) / 792f) < 1e-4f, "${v.zoom}")
        val tl = v.toScreen(0.0, 0.0); val br = v.toScreen(612.0, 792.0)
        assertTrue(abs((tl.x + br.x) / 2f - 500f) < 0.5f && abs((tl.y + br.y) / 2f - 400f) < 0.5f)
    }

    @Test
    fun screen_and_document_points_round_trip_at_an_offset_page() {
        val v = view()
        v.fit(Rect(-50f, 30f, 450f, 330f))
        val (x, y) = v.toDoc(v.toScreen(123.25, 77.5))
        assertTrue(abs(x - 123.25) < 1e-3 && abs(y - 77.5) < 1e-3)
    }

    @Test
    fun zooming_keeps_the_point_under_the_fingers_still() {
        val v = view(); v.fit(Rect(0f, 0f, 100f, 100f))
        val focus = Offset(300f, 250f)
        val before = v.toDoc(focus)
        v.zoomBy(2.5f, focus)
        val after = v.toDoc(focus)
        assertTrue(abs(before.first - after.first) < 1e-3 && abs(before.second - after.second) < 1e-3)
    }

    @Test
    fun hostile_numbers_change_nothing() {
        val v = view(); v.fit(Rect(0f, 0f, 100f, 100f))
        val z = v.zoom; val p = v.pan
        v.zoomBy(Float.NaN, Offset.Zero); v.zoomBy(-2f, Offset.Zero); v.zoomBy(0f, Offset.Zero)
        v.panBy(Offset(Float.POSITIVE_INFINITY, 1f))
        assertEquals(z, v.zoom); assertEquals(p, v.pan)
        // No viewport yet, or an empty page: no fit, no division by zero.
        CanvasView().fit(Rect(0f, 0f, 100f, 100f))
        view().fit(Rect(0f, 0f, 0f, 100f))
    }

    @Test
    fun render_scales_move_in_half_steps_and_stay_in_bounds() {
        assertEquals(1f, renderScaleFor(1.1f))
        assertTrue(abs(renderScaleFor(1.3f) - 1.4142135f) < 1e-4f)
        assertEquals(16f, renderScaleFor(100f, maxScale = 16f))
        assertEquals(1f, renderScaleFor(Float.NaN))
        assertTrue(renderScaleFor(0.0001f) >= 0.05f)
    }
}
