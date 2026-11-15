package app.loki.kit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** No front-buffered rendering here: the canvas overlay draws the wet ink. */
@Composable
actual fun rememberInkLayer(): InkLayer = NoInkLayer

@Composable
actual fun InkSurface(layer: InkLayer, modifier: Modifier) {}
