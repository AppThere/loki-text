package app.loki.text

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CropAndGuidesTest {
    private val pic = FrameRect(100f, 100f, 200f, 100f, 0f)
    private fun JsonObject.f(k: String) = getValue(k).jsonPrimitive.float

    @Test fun trimmingASideCropsTheSourceByTheShareItShowed() {
        // An uncropped picture: 50 of its 200 points from the left is a quarter of the source.
        val to = resizeFrame(pic, FrameHandle.Left, 50f, 0f, keepAspect = false)
        val p = cropDragParams(pic, to, FrameHandle.Left, listOf(0f, 0f, 0f, 0f))!!
        assertEquals(0.25f, p.f("left"), 1e-4f)
        assertEquals(0f, p.f("right"))
        assertEquals(150f, p.f("width"), 1e-3f)
        assertEquals(100f, p.f("height"), 1e-3f)
    }

    @Test fun anAlreadyCroppedPictureShowsLessOfTheSourcePerPoint() {
        // Half the width is cropped away already, so 50 points of the frame are an eighth of the source.
        val to = resizeFrame(pic, FrameHandle.Right, -50f, 0f, keepAspect = false)
        val p = cropDragParams(pic, to, FrameHandle.Right, listOf(0.25f, 0f, 0.25f, 0f))!!
        assertEquals(0.375f, p.f("right"), 1e-4f)
        assertEquals(0.25f, p.f("left"))
    }

    @Test fun draggingOutGivesBackOnlyWhatWasCropped() {
        val to = resizeFrame(pic, FrameHandle.Top, 0f, -500f, keepAspect = false)
        val p = cropDragParams(pic, to, FrameHandle.Top, listOf(0f, 0.2f, 0f, 0f))!!
        assertEquals(0f, p.f("top"))
        assertTrue(p.f("height") > pic.h, "the picture grows by what came back")
    }

    @Test fun anAxisKeepsSomeOfThePicture() {
        val to = resizeFrame(pic, FrameHandle.BottomRight, 1000f, 1000f, keepAspect = false).let { FrameRect(it.x, it.y, 4f, 4f, 0f) }
        val p = cropDragParams(pic, to, FrameHandle.BottomRight, listOf(0.5f, 0.5f, 0f, 0f))!!
        assertTrue(p.f("left") + p.f("right") <= 0.95f + 1e-4f && p.f("top") + p.f("bottom") <= 0.95f + 1e-4f)
    }

    @Test fun noMoveNoCrop() = assertNull(cropDragParams(pic, pic, FrameHandle.Left, listOf(0f, 0f, 0f, 0f)))

    private val guides = pageGuides(RulerInfo(612f, 72f, 72f, 792f, 72f, 72f))

    @Test fun aMovingObjectSnapsToTheNearestGuideAndSaysWhich() {
        // Its left edge 3 points right of the left margin: it lands on the margin.
        val (at, lines) = snapToGuides(FrameRect(75f, 300f, 100f, 50f, 0f), guides, tol = 4f)
        assertEquals(72f, at.x)
        assertEquals(72f, lines.x)
        // Its middle near the page's middle across.
        val (mid, l2) = snapToGuides(FrameRect(256f + 2f, 500f, 100f, 50f, 0f), guides, tol = 4f)
        assertEquals(306f, mid.cx)
        assertEquals(306f, l2.x)
    }

    @Test fun farFromEveryGuideItStaysPut() {
        val f = FrameRect(150f, 300f, 100f, 50f, 0f)
        val (at, lines) = snapToGuides(f, guides, tol = 4f)
        assertEquals(f.x, at.x); assertEquals(f.y, at.y)
        assertEquals(GuideLines(null, null), lines)
    }

    @Test fun withoutTheHeightOnlyAcrossGuides() {
        val g = pageGuides(RulerInfo(612f, 72f, 72f))
        assertTrue(g.ys.isEmpty() && g.xs.isNotEmpty())
    }
}
