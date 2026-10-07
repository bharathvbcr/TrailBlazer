package com.trailblazer.core.motion

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelHapticsTest {
    private fun run(h: LevelHaptics, samples: List<Pair<Double, Double>>, stepMs: Long = 50, steepDeg: Double? = null): List<LevelCue> {
        var t = 0L
        val limit = steepDeg?.let { SteepLimit(pitchDeg = it, rollDeg = it) }
        return samples.mapNotNull { (p, r) -> h.update(p, r, t, limit).also { t += stepMs } }
    }

    @Test
    fun reachingLevelConfirmsOnceEvenWhenTheSensorJittersAtTheEdge() {
        val h = LevelHaptics()
        val approach = listOf(3.2 to 0.0, 2.4 to 0.0, 1.6 to 0.0, 0.9 to 0.0, 0.3 to 0.0)
        // Noise straddling the 0.5° level line must not re-trigger.
        val jitter = List(200) { i -> (if (i % 2 == 0) 0.49 else 0.62) to 0.1 }
        val cues = run(h, approach + jitter)
        assertEquals(1, cues.count { it == LevelCue.Level }, "$cues")
    }

    @Test
    fun levelRearmsOnlyAfterClearlyLeaving() {
        val h = LevelHaptics()
        val cues = run(h, listOf(0.2 to 0.0, 0.8 to 0.0, 0.2 to 0.0, 1.4 to 0.0, 0.2 to 0.0), stepMs = 500)
        assertEquals(2, cues.count { it == LevelCue.Level }, "0.8° is not far enough to re-arm, 1.4° is: $cues")
    }

    @Test
    fun detentsTickAtWholeDegreesNearLevelAndNotFurtherOut() {
        val h = LevelHaptics()
        val sweep = (0..100).map { i -> (8.0 - i * 0.075) to 0.0 } // 8° down to 0.5°, slowly
        val ticks = run(h, sweep, stepMs = 100).count { it == LevelCue.Detent }
        assertEquals(4, ticks, "one tick each at 4°, 3°, 2° and 1° on the way in")
    }

    @Test
    fun detentsDoNotChatterOnABoundary() {
        val h = LevelHaptics()
        val wobble = List(300) { i -> (if (i % 2 == 0) 2.02 else 1.98) to 0.0 }
        val ticks = run(h, wobble, stepMs = 100).count { it == LevelCue.Detent }
        assertTrue(ticks <= 1, "hovering on 2° gave $ticks ticks")
    }

    @Test
    fun fastSweepsAreRateLimited() {
        val h = LevelHaptics()
        val sweep = (0..40).map { i -> (4.9 - i * 0.12) to 0.0 } // 4.9° to 0.1° in 40 samples 5 ms apart
        val cues = run(h, sweep, stepMs = 5)
        assertTrue(cues.size <= 3, "a flick through every degree in 0.2 s must not buzz continuously: $cues")
    }

    @Test
    fun steepWarnsOnceAndRearmsBelowTheThreshold() {
        val h = LevelHaptics()
        val cues = run(h, listOf(10.0 to 0.0, 21.0 to 0.0, 24.0 to 0.0, 22.0 to 0.0, 17.0 to 0.0, 21.0 to 0.0), stepMs = 500, steepDeg = 20.0)
        assertEquals(2, cues.count { it == LevelCue.Steep }, "$cues")
        val flat = run(LevelHaptics(), listOf(10.0 to 0.0, 30.0 to 0.0), steepDeg = null)
        assertTrue(LevelCue.Steep !in flat, "no steep warning when no limit is given")
    }

    @Test
    fun sideTiltHasItsOwnLimit() {
        val vehicle = SteepLimit(pitchDeg = 25.0, rollDeg = 20.0)
        assertTrue(vehicle.exceeded(0.0, 21.0))
        assertTrue(!vehicle.exceeded(22.0, 0.0), "22° nose-up is under the 25° pitch limit")
        assertTrue(vehicle.exceeded(-26.0, 0.0))
        assertTrue(!vehicle.exceeded(Double.NaN, 0.0))
    }

    @Test
    fun rollCountsTheSameAsPitch() {
        val h = LevelHaptics()
        assertEquals(LevelCue.Level, run(h, listOf(0.0 to 3.0, 0.0 to 0.2), stepMs = 500).last())
    }

    @Test
    fun garbageInputIsIgnoredAndDoesNotCorruptState() {
        val h = LevelHaptics()
        val bad = listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY to Double.NaN)
        assertTrue(run(h, bad).isEmpty())
        assertEquals(LevelCue.Level, run(h, listOf(3.0 to 0.0, 0.1 to 0.0), stepMs = 500).last())
        // Time going backwards (clock change) must not wedge the rate limiter.
        val h2 = LevelHaptics()
        assertEquals(LevelCue.Level, h2.update(0.1, 0.0, 1_000_000L, null))
        assertEquals(null, h2.update(3.0, 0.0, 10L, null)?.takeIf { it == LevelCue.Level })
        assertEquals(LevelCue.Level, h2.update(0.1, 0.0, 700L, null))
    }

    @Test
    fun randomWalksNeverProduceABurst() {
        val rnd = Random(7)
        repeat(50) {
            val h = LevelHaptics()
            var p = rnd.nextDouble(-6.0, 6.0)
            var r = rnd.nextDouble(-6.0, 6.0)
            var t = 0L
            val times = ArrayList<Long>()
            repeat(2_000) {
                p += rnd.nextDouble(-0.3, 0.3); r += rnd.nextDouble(-0.3, 0.3)
                if (h.update(p, r, t, SteepLimit(25.0, 20.0)) != null) times += t
                t += 16
            }
            val gaps = times.zipWithNext { a, b -> b - a }
            assertTrue(gaps.all { it >= LevelHaptics.MIN_GAP_MS }, "two cues closer than ${LevelHaptics.MIN_GAP_MS} ms")
        }
    }
}
