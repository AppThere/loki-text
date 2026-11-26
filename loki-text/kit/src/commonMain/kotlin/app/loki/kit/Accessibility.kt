package app.loki.kit

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlin.math.max
import kotlin.math.min

/**
 * What the Craft accessibility standard asks of a front end, as the Loki apps can check it. The rules are
 * `standards/accessibility.md` in storytold/craft-contrib, written for egui and AccessKit; in Compose the same
 * things are semantics nodes, focus, and colours. Every Loki app calls [contrastFailures] on its colour schemes in a
 * test and puts [announces] on the messages that appear away from focus.
 */

/** WCAG contrast ratio of two opaque colours, 1 to 21 (the lighter's luminance + 0.05 over the darker's + 0.05). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** Text needs at least this much contrast against its background (WCAG 1.4.3, level AA). */
const val TEXT_CONTRAST = 4.5f

/** Controls, their borders and focus indicators need at least this much (WCAG 1.4.11). */
const val CONTROL_CONTRAST = 3f

/**
 * The pairs of [scheme] that fail the standard's contrast bars, as sentences: every "on" colour against the colour it
 * sits on, at 4.5:1, and the outline against the surfaces it borders, at 3:1. Empty when it passes. [name] is the
 * scheme's name in the messages ("light", "dark").
 */
fun contrastFailures(scheme: ColorScheme, name: String): List<String> {
    val text = listOf(
        "onPrimary on primary" to (scheme.onPrimary to scheme.primary),
        "onPrimaryContainer on primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "onSecondary on secondary" to (scheme.onSecondary to scheme.secondary),
        "onSecondaryContainer on secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "onBackground on background" to (scheme.onBackground to scheme.background),
        "onSurface on surface" to (scheme.onSurface to scheme.surface),
        "onSurface on surfaceContainer" to (scheme.onSurface to scheme.surfaceContainer),
        "onSurface on surfaceContainerHighest" to (scheme.onSurface to scheme.surfaceContainerHighest),
        "onSurfaceVariant on surface" to (scheme.onSurfaceVariant to scheme.surface),
        "onSurfaceVariant on surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
        "error on surface" to (scheme.error to scheme.surface),
    )
    val controls = listOf(
        "outline on surface" to (scheme.outline to scheme.surface),
        "outline on background" to (scheme.outline to scheme.background),
        "primary on surface" to (scheme.primary to scheme.surface),
    )
    fun fails(pairs: List<Pair<String, Pair<Color, Color>>>, bar: Float) = pairs.mapNotNull { (what, c) ->
        val r = contrastRatio(c.first, c.second)
        if (r < bar) "$name: $what is ${fixed(r.toDouble(), 2)}:1, needs $bar:1" else null
    }
    return fails(text, TEXT_CONTRAST) + fails(controls, CONTROL_CONTRAST)
}

/**
 * Speaks a change made away from focus: put it on the text of a status message, a result count or an error, and a screen
 * reader reads it when it changes (WCAG 4.1.3). Polite waits for what is being read to finish.
 */
@Stable
fun Modifier.announces(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
