package app.loki.text

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** A pale blue theme for the controls: tinted surfaces, one clear blue for the accent. */
val LokiColors = lightColorScheme(
    primary = Color(0xFF1F6FC5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6FA),
    onPrimaryContainer = Color(0xFF0B3A6B),
    secondary = Color(0xFF4A6785),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E6FA),
    onSecondaryContainer = Color(0xFF16324F),
    background = Color(0xFFF1F7FD),
    onBackground = Color(0xFF1B2733),
    surface = Color(0xFFEAF3FC),
    onSurface = Color(0xFF1B2733),
    surfaceVariant = Color(0xFFDCEAF8),
    onSurfaceVariant = Color(0xFF3F566E),
    surfaceTint = Color(0xFF1F6FC5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F7FD),
    surfaceContainer = Color(0xFFE8F1FB),
    surfaceContainerHigh = Color(0xFFE0ECF9),
    surfaceContainerHighest = Color(0xFFD8E7F7),
    outline = Color(0xFF6F86A0),
    outlineVariant = Color(0xFFBFD3EA),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** The ribbon's tab bar and the status bar: a deep, saturated blue with a hint of teal that frames the page. */
val BarBlue = Color(0xFF004F93)

/** [LokiColors] for content on [BarBlue]: white text and buttons, a soft red for errors. */
val OnBarColors: ColorScheme = LokiColors.copy(primary = Color.White, onSurface = Color.White, error = Color(0xFFFFB4AB))
