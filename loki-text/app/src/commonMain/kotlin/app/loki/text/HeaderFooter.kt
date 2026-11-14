package app.loki.text

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** How soon the second tap of a double tap must follow the first, in milliseconds. */
internal const val DOUBLE_TAP_MS = 350L

/**
 * Whether a tap at [pos], [sinceMs] after the previous one at [prev], is the second tap of a double tap: soon
 * enough, and within [slopPx] of the first on both axes. (Compose's own detector holds every single tap back to
 * wait for a second, which would slow down placing the caret.)
 */
internal fun isDoubleTap(sinceMs: Long, prev: Offset, pos: Offset, slopPx: Float): Boolean =
    sinceMs in 0..DOUBLE_TAP_MS && abs(pos.x - prev.x) <= slopPx && abs(pos.y - prev.y) <= slopPx

/** The note over the page while a header or footer is being edited, with the way out. [kind] is "header" or "footer". */
@Composable
fun HeaderFooterBar(kind: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Editing ${if (kind == "footer") "footer" else "header"}", style = MaterialTheme.typography.labelLarge)
            TextButton(onClose) { Text("Close") }
        }
    }
}
