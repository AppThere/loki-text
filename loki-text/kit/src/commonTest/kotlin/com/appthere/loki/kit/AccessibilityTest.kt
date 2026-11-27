package com.appthere.loki.kit

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccessibilityTest {
    @Test fun contrastRatioMatchesTheDefinition() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.001f)
        // Symmetric: it does not matter which is the text.
        assertEquals(contrastRatio(Color(0xFF1F6FC5), Color.White), contrastRatio(Color.White, Color(0xFF1F6FC5)), 0.001f)
    }

    @Test fun aSchemeWithPaleTextOnPaleGroundIsReported() {
        val bad = lightColorScheme(onSurface = Color(0xFFDDDDDD), surface = Color.White)
        assertTrue(contrastFailures(bad, "light").any { "onSurface on surface" in it })
    }
}
