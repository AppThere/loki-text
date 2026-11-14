package app.loki.kit

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** The app's colours outside its coloured bars, for a menu that opens from a bar and must not inherit the bar's white-on-colour scheme. */
val LocalBaseColors = staticCompositionLocalOf<ColorScheme> { lightColorScheme() }

/** The theme choices, as the engine's interface-theme request names them. */
val THEME_CHOICES = listOf("system", "light", "dark")

/**
 * A Craft app's theme: [light], [dark], or the system's choice (the start). [content] gets the
 * choice (one of [THEME_CHOICES]), how to change it, and whether the result is dark, so the app can
 * answer the engine's interface-theme request and offer the choice in a menu.
 *
 * The window is usually a plain background, not a Surface, so the content colour is set here:
 * without that, icons and text stay black on the dark theme.
 */
@Composable
fun CraftTheme(
    light: ColorScheme,
    dark: ColorScheme,
    typography: Typography,
    content: @Composable (choice: String, setChoice: (String) -> Unit, dark: Boolean) -> Unit,
) {
    var choice by remember { mutableStateOf("system") }
    val isDark = when (choice) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (isDark) dark else light
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalBaseColors provides colors, LocalContentColor provides colors.onBackground) {
            content(choice, { c -> if (c in THEME_CHOICES) choice = c }, isDark)
        }
    }
}
