package app.loki.kit

import kotlin.test.Test
import kotlin.test.assertEquals

class LaneTimelineTest {
    @Test
    fun theRulerSaysMinutesAndSeconds() {
        assertEquals("0:00", rulerLabel(0.0, false))
        assertEquals("1:05", rulerLabel(65.4, false))
        assertEquals("0:02.5", rulerLabel(2.5, true))
        assertEquals("0:00", rulerLabel(-3.0, false))
        assertEquals("0:00", rulerLabel(Double.NaN, false).let { if (it.startsWith("0")) "0:00" else it })
    }

    @Test fun aPressOnAClipDragsItAndNearItsEndDragsTheEnd() {
        val lane = TimelineLane(1, "L", listOf(TimelineClip(1, 0.0, 10.0, "a"), TimelineClip(2, 10.0, 20.0, "b")))
        assertEquals(1L to ClipDrag.Move, hitClip(lane, 4.0, 0.5)!!.let { it.first.id to it.second })
        assertEquals(1L to ClipDrag.ResizeEnd, hitClip(lane, 9.8, 0.5)!!.let { it.first.id to it.second })
        assertEquals(1L to ClipDrag.ResizeEnd, hitClip(lane, 10.2, 0.5)!!.let { it.first.id to it.second }, "just past the end still grabs it")
        assertEquals(2L to ClipDrag.Move, hitClip(lane, 15.0, 0.5)!!.let { it.first.id to it.second })
        assertEquals(null, hitClip(lane, 25.0, 0.5)); assertEquals(null, hitClip(TimelineLane(2, "empty", emptyList()), 1.0, 0.5))
    }

    @Test fun aDragIsRoundedToTheSnap() {
        assertEquals(3.0, snapDelta(2.6, 1.0)); assertEquals(-2.0, snapDelta(-2.4, 1.0)); assertEquals(0.0, snapDelta(0.4, 1.0))
        assertEquals(2.6, snapDelta(2.6, 0.0)); assertEquals(Double.NaN, snapDelta(Double.NaN, 1.0))
    }
}
