package com.trailblazer.core.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatUtilsTest {
    @Test
    fun ordinaryValuesRoundToTheRequestedDecimals() {
        assertEquals("1.5", formatDecimals(1.5, 1))
        assertEquals("-0.5", formatDecimals(-0.5, 1))
        assertEquals("0.000010", formatDecimals(0.00001, 6))
        assertEquals("1234.6", formatDecimals(1234.56, 1))
        assertEquals("NaN", formatDecimals(Double.NaN, 1))
        assertEquals("Infinity", formatDecimals(Double.POSITIVE_INFINITY, 1))
    }

    @Test
    fun hugeValuesAreWrittenAsPlainDigitsNeverWithAnExponent() {
        // Past ~9.2e17 the value no longer fits a Long once scaled; the old code printed "1.0E25", which is not a valid
        // GPX <ele> (an XML decimal has no exponent).
        for (v in listOf(1e18, 9.3e17, 1e25, 1.5e300, Double.MAX_VALUE, -1e25, 9.223372036854776E18, 1.2345e19)) {
            for (decimals in listOf(1, 6)) {
                val s = formatDecimals(v, decimals)
                assertFalse(s.contains('E') || s.contains('e'), "$v/$decimals gave $s")
                assertTrue(Regex("-?[0-9]+\\.0{$decimals}").matches(s), "$v/$decimals gave $s")
            }
        }
        assertEquals("10000000000000000000000000.0", formatDecimals(1e25, 1))
        assertEquals("-10000000000000000000000000.0", formatDecimals(-1e25, 1))
        assertEquals("12345000000000000000.000000", formatDecimals(1.2345e19, 6))
        assertEquals("1000000000000000000.0", formatDecimals(1e18, 1))
        assertEquals(309 + 2, formatDecimals(Double.MAX_VALUE, 1).length)
    }

    @Test
    fun valuesBelowTheThresholdKeepTheirFraction() {
        assertEquals("1000000000000.25", formatDecimals(1e12 + 0.25, 2))
    }
}
