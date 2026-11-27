package com.appthere.loki.kit

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransformBoxTest {
    private val box = listOf(Offset(100f, 100f), Offset(300f, 100f), Offset(300f, 200f), Offset(100f, 200f))
    private fun near(a: Offset, b: Offset) = abs(a.x - b.x) < 1e-3f && abs(a.y - b.y) < 1e-3f

    @Test
    fun aCornerScalesAboutTheOppositeOne() {
        val r = TransformBox.scaled(box, BoxGrab.BottomRight, Offset(300f, 200f), Offset(500f, 300f), keepAspect = false, fromCenter = false)
        assertTrue(near(r[0], Offset(100f, 100f)) && near(r[2], Offset(500f, 300f)), "$r")
    }

    @Test
    fun anEdgeScalesOneWayOnly() {
        val r = TransformBox.scaled(box, BoxGrab.Right, Offset(300f, 150f), Offset(500f, 190f), keepAspect = false, fromCenter = false)
        assertTrue(near(r[1], Offset(500f, 100f)) && near(r[2], Offset(500f, 200f)), "height must not change: $r")
    }

    @Test
    fun shiftKeepsTheAspectAndAltScalesFromTheCentre() {
        val s = TransformBox.scaled(box, BoxGrab.BottomRight, Offset(300f, 200f), Offset(500f, 210f), keepAspect = true, fromCenter = false)
        assertEquals(2f, (s[1].x - s[0].x) / 200f, 1e-3f)
        assertEquals(2f, (s[2].y - s[1].y) / 100f, 1e-3f)
        val a = TransformBox.scaled(box, BoxGrab.Right, Offset(300f, 150f), Offset(400f, 150f), keepAspect = false, fromCenter = true)
        assertTrue(near(a[0], Offset(0f, 100f)) && near(a[1], Offset(400f, 100f)), "centre stays at x=200: $a")
    }

    @Test
    fun draggingPastTheOppositeSideFlipsInsteadOfBreaking() {
        val r = TransformBox.scaled(box, BoxGrab.Right, Offset(300f, 150f), Offset(50f, 150f), keepAspect = false, fromCenter = false)
        assertTrue(r.all { it.x.isFinite() && it.y.isFinite() }, "$r")
        assertTrue(r[1].x < r[0].x, "flipped: $r")
        // A degenerate drag (the handle on its own fixed side) stays finite.
        val d = TransformBox.scaled(box, BoxGrab.Right, Offset(300f, 150f), Offset(100f, 150f), keepAspect = false, fromCenter = false)
        assertTrue(d.all { it.x.isFinite() }, "$d")
    }

    @Test
    fun rotatingTurnsAboutTheCentreAndShiftSnaps() {
        // 90 degrees: the top-left corner (-100,-50 from the centre) goes to (+50,-100).
        val quarter = TransformBox.rotated(box, Offset(300f, 150f), Offset(200f, 250f), snap = false)
        assertTrue(near(quarter[0], Offset(250f, 50f)), "$quarter")
        // About 15.6 degrees snaps to exactly 15.
        val snapped = TransformBox.rotated(box, Offset(300f, 150f), Offset(300f, 178f), snap = true)
        val fifteen = Math.toRadians(15.0).let { Offset(200f + (-100f * kotlin.math.cos(it) + 50f * kotlin.math.sin(it)).toFloat(), 150f + (-100f * kotlin.math.sin(it) - 50f * kotlin.math.cos(it)).toFloat()) }
        assertTrue(near(snapped[0], fifteen), "$snapped vs $fifteen")
    }
}

class TransformBoxKnobTest {
    private fun view(zoom: Float, w: Float, h: Float) = CanvasView().apply { this.zoom = zoom; pan = Offset.Zero; viewport = androidx.compose.ui.geometry.Size(w, h) }

    @Test
    fun theKnobStandsOutwardFromTheCornerAndStaysOnTheCanvas() {
        val b = TransformBox(listOf(Offset(100f, 100f), Offset(300f, 100f), Offset(300f, 200f), Offset(100f, 200f)), hit = 26f, knob = 56f)
        val v = view(1f, 800f, 600f)
        val k = b.point(BoxGrab.Rotate, v)
        // Away from the centre (200,150) past the top-right corner (300,100): right and up, 56 px along.
        assertTrue(k.x > 300f && k.y < 100f, "$k")
        assertEquals(56f, kotlin.math.hypot(k.x - 300f, k.y - 100f), 0.01f)
        // The engine's own rotate zone is much nearer the corner than the knob.
        val e = b.enginePoint(BoxGrab.Rotate, v)
        assertEquals(TransformBox.ENGINE_ROTATE_ZONE, kotlin.math.hypot(e.x - 300f, e.y - 100f), 0.01f)
        // With no room at the right the knob moves to another corner that has some...
        val left = b.point(BoxGrab.Rotate, view(1f, 330f, 600f))
        assertTrue(left.x < 100f && left.y < 100f, "$left")
        // ...and where no corner has room (the box fills the view) it stands above the top side's middle.
        val wide = TransformBox(listOf(Offset(20f, 100f), Offset(300f, 100f), Offset(300f, 200f), Offset(20f, 200f)), hit = 26f, knob = 56f)
        val a = wide.point(BoxGrab.Rotate, view(1f, 320f, 600f))
        assertTrue(a.x in 100f..220f && a.y < 100f, "$a")
        // Zoomed out the knob stays 56 screen pixels from its corner (the nearest one, whichever has room).
        val z = view(0.25f, 800f, 600f).apply { pan = Offset(200f, 200f) }
        val kz = b.point(BoxGrab.Rotate, z)
        val nearest = b.corners.minOf { kotlin.math.hypot(kz.x - it.x, kz.y - it.y) }
        assertEquals(56f / 0.25f, nearest, 0.05f)
    }
}
