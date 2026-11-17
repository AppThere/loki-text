package app.loki.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The drag and resize gestures, driven through an off-screen scene with the screen pointer scripted (no real window or mouse is touched). */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class OwnFrameGestureTest {
    private val held = PointerButtons(isPrimaryPressed = true)
    private var current = Point(0, 0)
    private val original = screenPointer

    @AfterTest
    fun restore() { screenPointer = original }

    /** Press at [from] in the scene, pull the pointer across the slop, then on to [to] on the screen. Null when there is no display to make a Frame on. */
    private fun gesture(modifier: (Frame) -> Modifier, start: Rectangle, screenStart: Point, screenEnd: Point): Rectangle? {
        if (GraphicsEnvironment.isHeadless()) return null
        val frame = Frame().apply { bounds = start }
        try {
            screenPointer = { current }
            val scene = ImageComposeScene(400, 300, Density(1f)) { Box(Modifier.fillMaxSize().then(modifier(frame))) }
            scene.render(0)
            val at = Offset(50f, 50f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = held)
            current = screenStart
            scene.sendPointerEvent(PointerEventType.Move, Offset(90f, 50f), buttons = held)   // past the touch slop: the drag begins here
            current = screenEnd
            scene.sendPointerEvent(PointerEventType.Move, Offset(110f, 60f), buttons = held)
            scene.sendPointerEvent(PointerEventType.Release, Offset(110f, 60f))
            scene.close()
            return frame.bounds
        } finally { frame.dispose() }
    }

    @Test
    fun draggingTheBarMovesTheWindowByThePointersTravelOnTheScreen() {
        val r = gesture({ Modifier.windowDrag(it) }, Rectangle(100, 100, 800, 600), Point(500, 400), Point(560, 440)) ?: return
        assertEquals(Point(160, 140), r.location)
        assertEquals(800, r.width); assertEquals(600, r.height)
    }

    @Test
    fun draggingAnEastEdgeWidensTheWindow() {
        val r = gesture({ f -> Modifier.edgeDragForTest(f, FrameEdge.E) }, Rectangle(100, 100, 800, 600), Point(900, 300), Point(950, 320)) ?: return
        assertEquals(Rectangle(100, 100, 850, 600), r)
    }

    @Test
    fun draggingAWestEdgeKeepsTheRightSideWhereItWas() {
        val r = gesture({ f -> Modifier.edgeDragForTest(f, FrameEdge.W) }, Rectangle(100, 100, 800, 600), Point(100, 300), Point(150, 300)) ?: return
        assertEquals(Rectangle(150, 100, 750, 600), r)
    }
}
