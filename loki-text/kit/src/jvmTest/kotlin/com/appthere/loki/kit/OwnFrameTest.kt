package com.appthere.loki.kit

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnFrameTest {
    private val start = Rectangle(100, 100, 800, 600)

    @Test
    fun anEastEdgeMovesOnlyTheRightSide() {
        assertEquals(Rectangle(100, 100, 850, 600), resizedBounds(start, FrameEdge.E, 50, 999))
    }

    @Test
    fun aWestEdgeHoldsTheRightSideStill() {
        val r = resizedBounds(start, FrameEdge.W, 60, 0)
        assertEquals(Rectangle(160, 100, 740, 600), r)
        assertEquals(start.x + start.width, r.x + r.width)
    }

    @Test
    fun aNorthWestCornerHoldsTheOppositeCornerStill() {
        val r = resizedBounds(start, FrameEdge.NW, -40, -30)
        assertEquals(Rectangle(60, 70, 840, 630), r)
        assertEquals(start.x + start.width, r.x + r.width)
        assertEquals(start.y + start.height, r.y + r.height)
    }

    @Test
    fun theWindowNeverShrinksBelowTheMinimumAndTheFarEdgeStaysPut() {
        val r = resizedBounds(start, FrameEdge.W, 5000, 0, minWidth = 480, minHeight = 320)
        assertEquals(480, r.width)
        assertEquals(start.x + start.width, r.x + r.width, "dragging the left edge past the right must not push the window")
        assertEquals(320, resizedBounds(start, FrameEdge.S, 0, -5000, minWidth = 480, minHeight = 320).height)
    }

    @Test
    fun aSouthEastCornerGrowsBothWays() {
        assertEquals(Rectangle(100, 100, 820, 640), resizedBounds(start, FrameEdge.SE, 20, 40))
    }

    @Test
    fun theFrameCanBeForcedEitherWayOnlyByTheEnvironment() {
        // Without LOKI_FRAME the answer depends on the OS and the runtime, which a test cannot choose; with a JVM that is not a JetBrains Runtime
        // (and so reports no decorations) Linux gets its own frame and no other system does.
        val linux = System.getProperty("os.name").orEmpty().startsWith("Linux")
        if (System.getenv("LOKI_FRAME") == null) assertEquals(linux && !jbrWindowDecorationsSupported(), useOwnFrame())
    }
}
