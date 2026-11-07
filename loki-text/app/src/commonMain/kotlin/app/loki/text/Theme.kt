package app.loki.text

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import app.loki.text.res.Res
import app.loki.text.res.atkinson_hyperlegible_next
import app.loki.text.res.atkinson_hyperlegible_next_italic

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

private val UI_WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

/**
 * Atkinson Hyperlegible Next (Braille Institute; SIL OFL 1.1), bundled as a variable font in
 * composeResources/font. The control text uses it; the page is drawn by the engine, so documents
 * keep their own fonts.
 */
@Composable
fun uiFontFamily(): FontFamily = FontFamily(
    UI_WEIGHTS.flatMap { w ->
        val settings = FontVariation.Settings(FontVariation.weight(w.weight))
        listOf(
            Font(Res.font.atkinson_hyperlegible_next, w, FontStyle.Normal, settings),
            Font(Res.font.atkinson_hyperlegible_next_italic, w, FontStyle.Italic, settings),
        )
    },
)

/** Material's default type scale with [family] on every style. */
fun typographyWith(family: FontFamily): Typography {
    val d = Typography()
    fun TextStyle.ui() = copy(fontFamily = family)
    return Typography(
        displayLarge = d.displayLarge.ui(), displayMedium = d.displayMedium.ui(), displaySmall = d.displaySmall.ui(),
        headlineLarge = d.headlineLarge.ui(), headlineMedium = d.headlineMedium.ui(), headlineSmall = d.headlineSmall.ui(),
        titleLarge = d.titleLarge.ui(), titleMedium = d.titleMedium.ui(), titleSmall = d.titleSmall.ui(),
        bodyLarge = d.bodyLarge.ui(), bodyMedium = d.bodyMedium.ui(), bodySmall = d.bodySmall.ui(),
        labelLarge = d.labelLarge.ui(), labelMedium = d.labelMedium.ui(), labelSmall = d.labelSmall.ui(),
    )
}
