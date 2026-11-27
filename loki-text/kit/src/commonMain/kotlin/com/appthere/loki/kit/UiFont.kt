package com.appthere.loki.kit

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.appthere.loki.kit.res.Res
import com.appthere.loki.kit.res.atkinson_hyperlegible_next
import com.appthere.loki.kit.res.atkinson_hyperlegible_next_italic
import org.jetbrains.compose.resources.Font

private val UI_WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

/**
 * Atkinson Hyperlegible Next (Braille Institute; SIL OFL 1.1, the licence is beside the font in
 * composeResources/font), bundled as a variable font. Every Craft front end's control text uses
 * it; a page, slide or picture the engine draws keeps its own fonts.
 */
@Composable
fun craftFontFamily(): FontFamily = FontFamily(
    UI_WEIGHTS.flatMap { w ->
        val settings = FontVariation.Settings(FontVariation.weight(w.weight))
        listOf(
            Font(Res.font.atkinson_hyperlegible_next, w, FontStyle.Normal, settings),
            Font(Res.font.atkinson_hyperlegible_next_italic, w, FontStyle.Italic, settings),
        )
    },
)

/** Material's default type scale with [family] on every style. */
fun craftTypographyWith(family: FontFamily): Typography {
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

/** The type scale of a Craft front end: Material's, in Atkinson Hyperlegible Next. */
@Composable
fun craftTypography(): Typography = craftTypographyWith(craftFontFamily())
