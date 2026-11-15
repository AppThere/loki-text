package app.loki.kit

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertTrue

class SvgPathTest {
    @Test
    fun a_rectangle_is_read_and_mapped() {
        val p = svgPath("M10 20L110 20L110 70L10 70Z", { x, y -> Offset((x * 2).toFloat(), (y * 2).toFloat()) })
        val b = p.getBounds()
        assertTrue(b.left == 20f && b.top == 40f && b.right == 220f && b.bottom == 140f, "$b")
    }

    @Test
    fun curves_and_compact_numbers_are_read() {
        val b = svgPath("M0,0C10,-5 20,15 30,0Q40-10 50,0", { x, y -> Offset(x.toFloat(), y.toFloat()) }).getBounds()
        assertTrue(b.right == 50f, "$b")
    }

    @Test
    fun junk_never_throws() {
        for (d in listOf("", "Z", "M", "M 1", "L 1 2 3", "M nan 4", "M 1e400 2", "A 1 2 3 4 5 6 7", "M0 0 l 5 5 x y", "M0 0" + " L1 1".repeat(1000))) {
            svgPath(d, { x, y -> Offset(x.toFloat(), y.toFloat()) }, maxPoints = 500)
        }
    }
}
