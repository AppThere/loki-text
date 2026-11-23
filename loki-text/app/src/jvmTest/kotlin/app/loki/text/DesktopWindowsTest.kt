package app.loki.text

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWindowsTest {
    private fun registry(vararg ids: Int) = DesktopWindows { java.awt.Rectangle(0, 0, 1200, 800) }.apply { for (i in ids) { states[i] = WindowState(); titles[i] = "Doc $i" } }

    @Test fun tilesCoverTheAreaWithoutOverlapping() {
        for (n in 1..7) {
            val rects = (0 until n).map { tile(it, n, 0, 0, 1200, 800) }
            assertTrue(rects.all { it[0] >= 0 && it[1] >= 0 && it[0] + it[2] <= 1200 && it[1] + it[3] <= 800 }, "inside for $n")
            for (a in rects) for (b in rects) if (a !== b) {
                val apart = a[0] + a[2] <= b[0] || b[0] + b[2] <= a[0] || a[1] + a[3] <= b[1] || b[1] + b[3] <= a[1]
                assertTrue(apart, "overlap for $n")
            }
        }
        val (left, right) = tile(0, 2, 0, 0, 1200, 800) to tile(1, 2, 0, 0, 1200, 800)
        assertTrue(left[0] < right[0] && left[1] == right[1], "two windows are side by side")
    }

    @Test fun otherWindowsAreListedWithTheirTitles() {
        val r = registry(0, 1, 2)
        assertEquals(listOf(OpenWindow(1, "Doc 1"), OpenWindow(2, "Doc 2")), r.hostFor(0).others())
    }

    @Test fun scrollingPassesToThePartnerOnlyWhileSynchronous() {
        val r = registry(0, 1)
        val a = r.hostFor(0)
        val b = r.hostFor(1)
        a.scrolled(0.4f)
        assertNull(b.followScroll, "not side by side yet")
        assertNull(a.sideBySide(1, true))
        // Both windows now share the screen, this one on the left.
        assertTrue(r.states.getValue(0).position.x < r.states.getValue(1).position.x)
        run {
            a.scrolled(0.4f)
            assertNull(b.followScroll, "side by side, but scrolling isn't synchronous")
            a.setSyncScroll(true)
            a.scrolled(0.4f)
            assertEquals(0.4f, b.followScroll)
            b.scrolled(0.7f)
            assertEquals(0.7f, a.followScroll, "it goes both ways")
            a.sideBySide(1, false)
            a.scrolled(0.1f)
            assertEquals(0.4f, b.followScroll, "ending side by side stops it")
        }
    }

    @Test fun fullScreenComesBackToWhereItWas() {
        val r = registry(0)
        r.states.getValue(0).placement = WindowPlacement.Maximized
        val h = r.hostFor(0)
        h.setFullScreen(true)
        assertEquals(WindowPlacement.Fullscreen, r.states.getValue(0).placement)
        h.setFullScreen(false)
        assertEquals(WindowPlacement.Maximized, r.states.getValue(0).placement)
    }

    @Test fun aClosedWindowLeavesNoTrace() {
        val r = registry(0, 1)
        r.remove(1)
        assertTrue(r.hostFor(0).others().isEmpty())
        assertTrue(r.hostFor(0).sideBySide(1, true) != null, "can't pair with a closed window")
    }
}
