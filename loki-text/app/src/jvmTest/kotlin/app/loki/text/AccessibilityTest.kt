package app.loki.text

import androidx.compose.ui.graphics.Color
import app.loki.kit.TEXT_CONTRAST
import app.loki.kit.contrastFailures
import app.loki.kit.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Craft accessibility standard's colour bars, on the colours this app draws with (WCAG 1.4.3, 1.4.11). */
class AccessibilityTest {
    @Test fun lightThemePassesTheContrastBars() = assertEquals(emptyList(), contrastFailures(LokiColors, "light"))

    @Test fun darkThemePassesTheContrastBars() = assertEquals(emptyList(), contrastFailures(LokiDarkColors, "dark"))

    // The status bar and the title bar draw white text, and the error colour, on the bar's blue.
    @Test fun textOnTheBlueBarIsReadable() {
        for (ink in listOf(Color.White, OnBarColors.error)) {
            val r = contrastRatio(ink, BarBlue)
            assert(r >= TEXT_CONTRAST) { "$ink on the bar is $r:1" }
        }
    }
}
