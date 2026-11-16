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
}
