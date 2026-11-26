package app.loki.text

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.loki.text.res.Res
import app.loki.text.res.loki_text_icon
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource

/** How long the splash stays fully opaque once the app has started, then how long it takes to fade out. */
private const val HOLD_MS = 500L
private const val FADE_MS = 250

/**
 * Shows the Loki Text icon on a plain field while the app loads.
 *
 * The first frame is the splash alone: [content] (which starts the engine, and blocks the frame it
 * first composes in) is added on the next frame, under the splash, which then holds and fades out.
 * With [enabled] false the content is shown at once.
 */
@Composable
fun WithSplash(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) return content()
    var started by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var fading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        started = true
        delay(HOLD_MS)
        fading = true
        delay(FADE_MS.toLong())
        done = true
    }
    Box(Modifier.fillMaxSize()) {
        if (started) content()
        if (!done) {
            val alpha by animateFloatAsState(if (fading) 0f else 1f, tween(FADE_MS))
            Box(Modifier.fillMaxSize().alpha(alpha).background(SPLASH_FIELD), contentAlignment = Alignment.Center) {
                // Decorative: the window title already names the app.
                Image(painterResource(Res.drawable.loki_text_icon), contentDescription = null, modifier = Modifier.size(160.dp))
            }
        }
    }
}

/** Loki Text's pale blue-grey, so the icon's blue tile stands out. */
private val SPLASH_FIELD = Color(0xFFF2F5FC)
