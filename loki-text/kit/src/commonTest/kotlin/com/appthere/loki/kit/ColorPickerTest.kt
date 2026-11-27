package com.appthere.loki.kit

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorPickerTest {
    @Test
    fun hexRoundTrips() {
        assertEquals("#12ab34", hexOf(parseHex("#12AB34")!!))
        assertEquals("#ffcc00", hexOf(parseHex("fc0")!!))
        assertNull(parseHex("#12ab3"))
        assertNull(parseHex("zzzzzz"))
        assertNull(parseHex(""))
    }

    @Test
    fun hsvRoundTrips() {
        for (hex in listOf("#ff0000", "#00ff00", "#0000ff", "#12ab34", "#808080", "#000000", "#ffffff", "#c06030", "#3060c0")) {
            val c = parseHex(hex)!!
            val (h, s, v) = hsvOf(c).let { Triple(it[0], it[1], it[2]) }
            assertTrue(h in 0f..360f && s in 0f..1f && v in 0f..1f, hex)
            assertEquals(hex, hexOf(colorOfHsv(h, s, v)), hex)
        }
        assertEquals("#ff0000", hexOf(colorOfHsv(360f, 1f, 1f)))
        assertEquals("#ff0000", hexOf(colorOfHsv(-360f, 1f, 1f)))
    }

    @Test
    fun recentKeepsTheNewestFirstWithoutRepeats() {
        RecentColors.colors.clear()
        RecentColors.add(Color.Red); RecentColors.add(Color.Blue); RecentColors.add(Color.Red)
        assertEquals(listOf("#ff0000", "#0000ff"), RecentColors.colors.map(::hexOf))
        repeat(20) { RecentColors.add(colorOfHsv(it * 17f, 1f, 1f)) }
        assertEquals(12, RecentColors.colors.size)
        RecentColors.colors.clear()
    }

    @Test
    fun sliderScalesInvert() {
        for (log in listOf(false, true)) for (v in listOf(1.0, 2.0, 37.0, 500.0, 1000.0)) {
            val back = sliderValue(sliderPosition(v, 1.0, 1000.0, log), 1.0, 1000.0, log)
            assertTrue(abs(back - v) < v * 1e-3, "$v $log -> $back")
        }
        // A logarithmic scale gives the small sizes room: 10 of 1..1000 sits a third of the way along.
        assertTrue(abs(sliderPosition(10.0, 1.0, 1000.0, true) - 1f / 3f) < 1e-4)
        assertEquals(0f, sliderPosition(-5.0, 1.0, 1000.0, true))
        assertEquals(0f, sliderPosition(5.0, 3.0, 3.0, false))
    }

    @Test
    fun numbersFormatShort() {
        assertEquals("20", formatNumber(20.0))
        assertEquals("0.25", formatNumber(0.25, 2))
        assertEquals("1.5", formatNumber(1.5, 2))
        assertEquals("3", formatNumber(2.999, 1))
        assertEquals("?", formatNumber(Double.NaN))
    }
}
