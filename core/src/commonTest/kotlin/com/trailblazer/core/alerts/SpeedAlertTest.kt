package com.trailblazer.core.alerts

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedAlertTest {
    @Test
    fun firesOnceThenRearmsBelowTheMargin() {
        val a = SpeedAlert(limitMps = 25.0)
        assertTrue(a.update(26.0))
        assertFalse(a.update(27.0))
        assertFalse(a.update(24.0)) // inside the hysteresis band: still not re-armed
        assertFalse(a.update(23.0))
        assertFalse(a.update(23.5))
        assertTrue(a.update(26.0)) // 23.0 m/s is under limit − 1.4, so it re-armed
    }

    @Test
    fun aLimitAtOrBelowTheMarginStillRearms() {
        for (limit in listOf(1.4, 1.0, 0.5, 0.01)) {
            val a = SpeedAlert(limitMps = limit)
            assertTrue(a.update(limit * 2), "first fire at limit $limit")
            assertFalse(a.update(limit * 3), "still over at limit $limit")
            assertFalse(a.update(0.0), "standing still re-arms at limit $limit")
            assertTrue(a.update(limit * 2), "second fire at limit $limit")
        }
    }

    @Test
    fun aClampedMarginDoesNotChangeALimitAboveTwiceTheMargin() {
        val a = SpeedAlert(limitMps = 2.8) // exactly twice the default margin
        assertTrue(a.update(3.0))
        assertFalse(a.update(1.5)) // above limit − margin = 1.4
        assertFalse(a.update(1.3))
        assertTrue(a.update(3.0))
    }

    @Test
    fun badSamplesNeverFireOrRearm() {
        val a = SpeedAlert(limitMps = 10.0)
        assertFalse(a.update(null))
        assertFalse(a.update(Double.NaN))
        assertFalse(a.update(Double.POSITIVE_INFINITY))
        assertTrue(a.update(11.0))
        assertFalse(a.update(null))
        assertFalse(a.update(Double.NaN))
        assertFalse(a.update(11.0))
    }

    @Test
    fun invalidConfigurationIsRejected() {
        assertFailsWith<IllegalArgumentException> { SpeedAlert(0.0) }
        assertFailsWith<IllegalArgumentException> { SpeedAlert(-1.0) }
        assertFailsWith<IllegalArgumentException> { SpeedAlert(10.0, -0.1) }
    }
}
