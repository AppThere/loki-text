package app.loki.text

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Gap between the bar and the selected line, in pixels at 1× density; callers scale it. */
internal const val MINI_BAR_GAP_DP = 8f

/** How far in from the selection's left edge the bar is centred, in dp. */
internal const val MINI_BAR_INSET_DP = 4f

/**
 * The topmost selection box that is actually visible inside [viewport], cut down to its visible
 * part. Null when none is: boxes that only touch the edge, or are not finite numbers, don't count.
 */
internal fun visibleAnchorRect(rects: List<Rect>, viewport: Rect): Rect? {
    if (!viewport.left.isFinite() || !viewport.top.isFinite() || viewport.width <= 0f || viewport.height <= 0f) return null
    var best: Rect? = null
    for (r in rects) {
        if (!(r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite())) continue
        val v = r.intersect(viewport)
        if (v.width <= 0f || v.height < 1f) continue
        if (best == null || v.top < best.top) best = v
    }
    return best
}

/**
 * Where the top-left corner of a [barW] × [barH] bar goes for a selection line spanning
 * [anchorTop]..[anchorBottom] starting at [anchorLeft], inside a [viewportW] × [viewportH] viewport:
 * above the line if it fits, else below, else pushed inside. Null for numbers that aren't usable.
 */
internal fun miniToolbarOffset(
    anchorTop: Float, anchorBottom: Float, anchorLeft: Float,
    viewportW: Float, viewportH: Float, barW: Float, barH: Float, gap: Float, inset: Float,
): Offset? {
    val nums = listOf(anchorTop, anchorBottom, anchorLeft, viewportW, viewportH, barW, barH, gap, inset)
    if (nums.any { !it.isFinite() } || viewportW <= 0f || viewportH <= 0f || barW <= 0f || barH <= 0f) return null
    // Centred on the start of the selection, kept inside the viewport.
    val cx = (anchorLeft + inset).coerceIn(0f, viewportW)
    val x = (cx - barW / 2f).coerceIn(0f, (viewportW - barW).coerceAtLeast(0f))
    val above = anchorTop - gap - barH
    val below = anchorBottom + gap
    val aboveFits = above >= 0f && above + barH <= viewportH
    val belowFits = below >= 0f && below + barH <= viewportH
    val y = when {
        aboveFits -> above
        belowFits -> below
        else -> (if (above < 0f) below else above).coerceIn(0f, (viewportH - barH).coerceAtLeast(0f))
    }
    return Offset(x, y)
}

/**
 * The floating bar over a selection: font, size, grow and shrink on one row; bold, italic,
 * underline, strikethrough, highlight, font colour and the four alignments on the other.
 */
@Composable
fun MiniToolbar(env: RibbonEnv, modifier: Modifier = Modifier) {
    Surface(
        // Taps on the bar's own padding must not reach the page and move the selection.
        modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DropdownField(env.format.font.orEmpty(), 140.dp, env.fonts, "Font", env.previews) { f -> env.onRunWith("format.font", buildJsonObject { put("name", f) }) }
                SizeField(env.format.size) { s -> env.onRunWith("format.size", buildJsonObject { put("size", s) }) }
                Cmd(env, "format.growFont", "Increase Font Size", ButtonSize.Icon)
                Cmd(env, "format.shrinkFont", "Decrease Font Size", ButtonSize.Icon)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cmd(env, "format.bold", "Bold", ButtonSize.Icon)
                Cmd(env, "format.italic", "Italic", ButtonSize.Icon)
                Cmd(env, "format.underline", "Underline", ButtonSize.Icon)
                Cmd(env, "format.strikethrough", "Strikethrough", ButtonSize.Icon)
                ColorSplitButton("format.highlight", env.label("format.highlight", "Text Highlight Color"), HIGHLIGHTS, HIGHLIGHTS.first(), "No Color", env.reason("format.highlight")) { c ->
                    env.onRunWith("format.highlight", buildJsonObject { put("color", c ?: "None") })
                }
                ColorSplitButton("format.color", env.label("format.color", "Font Color"), FONT_COLORS, FONT_COLORS[4], "Automatic", env.reason("format.color"), custom = true) { c ->
                    env.onRunWith("format.color", buildJsonObject { put("color", c ?: "auto") })
                }
                Cmd(env, "para.alignLeft", "Align Left", ButtonSize.Icon)
                Cmd(env, "para.alignCenter", "Center", ButtonSize.Icon)
                Cmd(env, "para.alignRight", "Align Right", ButtonSize.Icon)
                Cmd(env, "para.justify", "Justify", ButtonSize.Icon)
            }
        }
    }
}
