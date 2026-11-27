package com.appthere.loki.kit

import kotlin.test.Test
import kotlin.test.assertEquals

class NumbersTest {
    @Test
    fun roundingCarriesIntoTheWholePart() {
        assertEquals("10.0", fixed(9.96, 1))
        assertEquals("1.00", fixed(0.999, 2))
    }

    @Test
    fun smallFractionsKeepTheirLeadingZeros() {
        assertEquals("3.05", fixed(3.05, 2))
        assertEquals("0.007", fixed(0.007, 3))
    }

    @Test
    fun negativesKeepTheirSignUnlessTheyRoundToZero() {
        assertEquals("-2.5", fixed(-2.5, 1))
        assertEquals("0.0", fixed(-0.01, 1))
    }

    @Test
    fun noDecimalsGivesAWholeNumber() {
        assertEquals("4", fixed(3.6, 0))
    }
}
