package app.loki.text

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.Window
import kotlin.math.ceil
import kotlin.math.sqrt

/** Where a window goes in a tiling of [count] windows over a [w] × [h] area at ([x], [y]): column-major rows and columns, as even as they come. */
internal fun tile(index: Int, count: Int, x: Int, y: Int, w: Int, h: Int): IntArray {
    val cols = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(count / cols.toDouble()).toInt().coerceAtLeast(1)
    val c = index % cols
    val r = index / cols
    val cw = w / cols
    val rh = h / rows
    return intArrayOf(x + c * cw, y + r * rh, cw, rh)
}

/** Every open document window of the desktop app: their state, AWT windows and titles, and which pairs are side by side. */
internal class DesktopWindows(
    /** The screen area windows may use (less the taskbar or dock); null when it isn't known. */
    private val screenArea: () -> java.awt.Rectangle? = { runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull() },
) {
    val states = HashMap<Int, WindowState>()
    val awt = HashMap<Int, Window>()
    val titles = mutableStateMapOf<Int, String>()
    private val partner = HashMap<Int, Int>()
    private val synced = HashSet<Int>()
    /** Where each window is asked to scroll by the one beside it. */
    val follow = mutableStateMapOf<Int, Float>()

    fun remove(id: Int) {
        states.remove(id); awt.remove(id); titles.remove(id); follow.remove(id); synced.remove(id)
        partner.remove(id)?.let { partner.remove(it); synced.remove(it) }
    }

    fun hostFor(id: Int): WindowHost = object : WindowHost {
        private var before: WindowPlacement? = null

        override fun setFullScreen(on: Boolean) {
            val s = states[id] ?: return
            if (on && s.placement != WindowPlacement.Fullscreen) { before = s.placement; s.placement = WindowPlacement.Fullscreen }
            if (!on && s.placement == WindowPlacement.Fullscreen) s.placement = before ?: WindowPlacement.Floating
        }

        override fun others(): List<OpenWindow> = states.keys.filter { it != id }.sorted().map { OpenWindow(it, titles[it] ?: "Untitled") }

        override fun focus(id: Int) {
            awt[id]?.let { w -> w.toFront(); w.requestFocus() }
            states[id]?.isMinimized = false
        }

        override fun setTitle(title: String) { titles[id] = title }

        override fun arrangeAll(): String? {
            val area = screenArea() ?: return "The screen's size isn't known"
            val ids = states.keys.sorted()
            ids.forEachIndexed { i, w -> place(w, tile(i, ids.size, area.x, area.y, area.width, area.height)) }
            return null
        }

        override fun sideBySide(other: Int, on: Boolean): String? {
            if (other !in states) return "That window has closed"
            if (!on) { partner.remove(id); partner.remove(other); synced.remove(id); synced.remove(other); return null }
            val area = screenArea() ?: return "The screen's size isn't known"
            place(id, tile(0, 2, area.x, area.y, area.width, area.height))
            place(other, tile(1, 2, area.x, area.y, area.width, area.height))
            partner[id] = other
            partner[other] = id
            return null
        }

        override fun setSyncScroll(on: Boolean) {
            val p = partner[id] ?: return
            if (on) { synced += id; synced += p } else { synced -= id; synced -= p }
        }

        override fun scrolled(fraction: Float) {
            if (id !in synced) return
            partner[id]?.let { follow[it] = fraction }
        }

        override val followScroll: Float? get() = follow[id]

        private fun place(w: Int, r: IntArray) {
            val s = states[w] ?: return
            s.placement = WindowPlacement.Floating
            s.isMinimized = false
            s.position = WindowPosition(r[0].dp, r[1].dp)
            s.size = DpSize(r[2].dp, r[3].dp)
        }
    }
}
