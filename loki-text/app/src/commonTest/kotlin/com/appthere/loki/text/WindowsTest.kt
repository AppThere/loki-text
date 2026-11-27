package com.appthere.loki.text

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsTest {
    private fun state(json: String) = parseViewState(Json.parseToJsonElement(json))

    @Test fun focusAndFullScreenViewHideTheChromeAndFillTheScreen() {
        val focus = state("""{"focusMode":true,"fullScreenView":true}""")
        assertEquals(Screen.Focus, focus.screen, "Focus wins when both are on, as on the desktop")
        assertTrue(focus.screen.immersive && wantsFullScreen(focus))
        val reading = state("""{"fullScreenView":true}""")
        assertEquals(Screen.FullScreenView, reading.screen)
        assertEquals("view.fullScreenView", reading.screen.exitCommand)
    }

    @Test fun fullScreenAloneKeepsTheRibbon() {
        val v = state("""{"fullScreen":true}""")
        assertFalse(v.screen.immersive)
        assertTrue(wantsFullScreen(v))
        assertFalse(wantsFullScreen(state("{}")))
    }

    @Test fun thisWindowIsListedFirst() {
        val choices = windowChoices("Report.docx — Loki Text", listOf(OpenWindow(3, "Notes"), OpenWindow(5, "Draft")))
        assertEquals(listOf(null, 3, 5), choices.map { it.first })
    }

    @Test fun scrollFractionsStayInRange() {
        assertEquals(0f, scrollFraction(10, 0))
        assertEquals(0.5f, scrollFraction(50, 100))
        assertEquals(1f, scrollFraction(150, 100))
    }
}
