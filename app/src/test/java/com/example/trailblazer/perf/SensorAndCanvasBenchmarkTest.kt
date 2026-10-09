package com.example.trailblazer.perf

import android.hardware.GeomagneticField
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.sensors.Declination
import com.trailblazer.core.astro.ApparentContext
import com.trailblazer.core.astro.ApparentPlace
import com.trailblazer.core.astro.BrightStars
import com.trailblazer.core.astro.HorizontalContext
import com.trailblazer.core.astro.HorizontalTransform
import com.trailblazer.core.astro.SkyProjection
import com.trailblazer.core.astro.SolarPosition
import com.trailblazer.core.time.JulianDay
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Performance benchmarks comparing pre-optimization vs post-optimization paths
 * using interleaved A/B executions and min-of-N metrics.
 */
@RunWith(AndroidJUnit4::class)
class SensorAndCanvasBenchmarkTest {

    data class BenchmarkStats(
        val minMs: Double,
        val medianMs: Double,
        val meanMs: Double,
        val runsMs: List<Double>,
    )

    private fun benchmarkInterleaved(
        iterations: Int = 11,
        warmup: Int = 3,
        nameA: String = "Before (unoptimized)",
        runA: () -> Unit,
        nameB: String = "After (optimized)",
        runB: () -> Unit,
    ): Pair<BenchmarkStats, BenchmarkStats> {
        require(iterations >= 3) { "Requires at least 3 iterations for min-of-N" }

        // Warmup
        repeat(warmup) {
            runA()
            runB()
        }

        val timesA = ArrayList<Double>(iterations)
        val timesB = ArrayList<Double>(iterations)

        // Interleaved A/B runs
        for (i in 0 until iterations) {
            if (i % 2 == 0) {
                val t0 = System.nanoTime()
                runA()
                val dtA = (System.nanoTime() - t0) / 1_000_000.0
                timesA.add(dtA)

                val t1 = System.nanoTime()
                runB()
                val dtB = (System.nanoTime() - t1) / 1_000_000.0
                timesB.add(dtB)
            } else {
                val t1 = System.nanoTime()
                runB()
                val dtB = (System.nanoTime() - t1) / 1_000_000.0
                timesB.add(dtB)

                val t0 = System.nanoTime()
                runA()
                val dtA = (System.nanoTime() - t0) / 1_000_000.0
                timesA.add(dtA)
            }
        }

        fun stats(times: List<Double>): BenchmarkStats {
            val sorted = times.sorted()
            val min = sorted.first()
            val median = sorted[sorted.size / 2]
            val mean = sorted.average()
            return BenchmarkStats(min, median, mean, sorted)
        }

        val statsA = stats(timesA)
        val statsB = stats(timesB)

        val speedupMin = statsA.minMs / statsB.minMs.coerceAtLeast(0.001)
        val speedupMedian = statsA.medianMs / statsB.medianMs.coerceAtLeast(0.001)

        println("=== Benchmark Result ===")
        println("  $nameA -> min: ${"%.3f".format(statsA.minMs)} ms, median: ${"%.3f".format(statsA.medianMs)} ms")
        println("  $nameB -> min: ${"%.3f".format(statsB.minMs)} ms, median: ${"%.3f".format(statsB.medianMs)} ms")
        println("  Speedup: ${"%.2f".format(speedupMin)}x (min-of-$iterations), ${"%.2f".format(speedupMedian)}x (median)")

        return statsA to statsB
    }

    /**
     * Stargaze follow-compass path benchmark:
     * Before: Re-evaluating ephemeris (all ~284 stars with precession, nutation, sidereal time)
     *         on every compass sample (simulating 60 Hz sensor updates over 1 second = 60 samples).
     * After: Caching apparent positions per minute/place using ApparentContext and HorizontalContext,
     *        and applying compass facing rotation strictly at draw time.
     */
    @Test
    fun benchmarkStargazeFollowCompass() {
        val nowMs = 1760000000000L
        val lat = 45.0
        val lon = 10.0
        val samples = 60 // 1 second of compass updates at 60 Hz

        // Simulated compass headings (degrees)
        val headings = DoubleArray(samples) { i -> (i * 1.5) % 360.0 }

        val (statsBefore, statsAfter) = benchmarkInterleaved(
            iterations = 9,
            warmup = 3,
            nameA = "Stargaze follow-compass (Before: recompute ephemeris at sensor rate)",
            runA = {
                // Before: 60 sensor samples -> 60 full chart recomputations
                var sumX = 0.0
                for (heading in headings) {
                    val jde = JulianDay.ephemeris(nowMs)
                    for (s in BrightStars.all) {
                        // Without shared context: Meeus precession + nutation + aberration per star
                        val eq = ApparentPlace.fromJ2000(s.raJ2000Deg, s.decJ2000Deg, jde)
                        val h = HorizontalTransform.toHorizontal(nowMs, lat, lon, eq)
                        val pt = SkyProjection.project(h.azimuthDeg, h.apparentAltitudeDeg, heading)
                        if (pt != null) sumX += pt.x
                    }
                }
                assertTrue(sumX != 0.0)
            },
            nameB = "Stargaze follow-compass (After: context-cached ephemeris, draw-time rotation)",
            runB = {
                // After: Ephemeris calculated ONCE for the chart using shared ApparentContext & HorizontalContext
                val jde = JulianDay.ephemeris(nowMs)
                val apparentCtx = ApparentContext(jde)
                val horizCtx = HorizontalContext(nowMs, lon)

                val altAzList = ArrayList<Pair<Double, Double>>(BrightStars.all.size)
                for (s in BrightStars.all) {
                    val eq = s.apparent(apparentCtx)
                    val h = horizCtx.toHorizontal(lat, eq)
                    if (h.altitudeDeg > -1.0) {
                        altAzList.add(h.azimuthDeg to h.apparentAltitudeDeg)
                    }
                }

                // Sensor updates only project (draw-time rotation)
                var sumX = 0.0
                for (heading in headings) {
                    for ((az, alt) in altAzList) {
                        val pt = SkyProjection.project(az, alt, heading)
                        if (pt != null) sumX += pt.x
                    }
                }
                assertTrue(sumX != 0.0)
            },
        )

        assertTrue(
            "Optimized follow-compass must be faster than recalculating ephemeris at sensor rate",
            statsAfter.minMs < statsBefore.minMs,
        )
    }

    /**
     * Sky scrub path benchmark:
     * Before: Rebuilding 97 skyFor samples, 64 gradient calculations, and path layout on every scrub frame.
     * After: drawWithCache background caching — gradient bands and background geometry cached once,
     *        drawing only the body marker position per scrub frame.
     */
    @Test
    fun benchmarkSkyScrubFrame() {
        val windowStartMs = 1760000000000L
        val stepMs = 15 * 60_000L
        val n = 97
        val lat = 46.5
        val lon = 7.8
        val frames = 60 // 60 scrub frames during a drag gesture

        val scrubTimes = LongArray(frames) { i -> windowStartMs + (i * 24 * 3_600_000L / frames) }
        val sunPath = List(n) { i -> SolarPosition.horizontal(windowStartMs + i * stepMs, lat, lon) }

        val (statsBefore, statsAfter) = benchmarkInterleaved(
            iterations = 9,
            warmup = 3,
            nameA = "Sky scrub (Before: recompute background, paths, gradients every frame)",
            runA = {
                // Before: On every frame, recalculate gradients, path interpolation, and curves
                var dummy = 0.0
                for (t in scrubTimes) {
                    // 64 gradient calculations per frame
                    for (b in 0 until 64) {
                        val f = (b + 0.5) / 64.0
                        dummy += sin(f * Math.PI)
                    }
                    // Path calculations per frame
                    var px = 0.0
                    while (px <= 400.0) {
                        val f = px / 400.0
                        val hh = sin(f * 13.0) * 0.35 + sin(f * 29.0 + 1.3) * 0.2 + sin(f * 5.0 + 0.4) * 0.45
                        dummy += hh
                        px += 4.0
                    }
                    // Marker calculation
                    val f = ((t - windowStartMs).toDouble() / stepMs).coerceIn(0.0, n - 1.0)
                    val lo = f.toInt().coerceAtMost(n - 2)
                    val alt = sunPath[lo].altitudeDeg + (sunPath[lo + 1].altitudeDeg - sunPath[lo].altitudeDeg) * (f - lo)
                    dummy += alt
                }
                assertTrue(dummy != 0.0)
            },
            nameB = "Sky scrub (After: drawWithCache background, marker-only per frame)",
            runB = {
                // After: Cache background gradients and curves once
                var cachedGradientsSum = 0.0
                for (b in 0 until 64) {
                    val f = (b + 0.5) / 64.0
                    cachedGradientsSum += sin(f * Math.PI)
                }
                var cachedCurveSum = 0.0
                var px = 0.0
                while (px <= 400.0) {
                    val f = px / 400.0
                    val hh = sin(f * 13.0) * 0.35 + sin(f * 29.0 + 1.3) * 0.2 + sin(f * 5.0 + 0.4) * 0.45
                    cachedCurveSum += hh
                    px += 4.0
                }

                // Per-frame draw: only calculate interpolated marker position
                var markerSum = 0.0
                for (t in scrubTimes) {
                    val f = ((t - windowStartMs).toDouble() / stepMs).coerceIn(0.0, n - 1.0)
                    val lo = f.toInt().coerceAtMost(n - 2)
                    val alt = sunPath[lo].altitudeDeg + (sunPath[lo + 1].altitudeDeg - sunPath[lo].altitudeDeg) * (f - lo)
                    markerSum += alt
                }
                val total = cachedGradientsSum + cachedCurveSum + markerSum
                assertTrue(total != 0.0)
            },
        )

        assertTrue(
            "Cached sky scrub path must be faster than per-frame gradient and geometry rebuild",
            statsAfter.minMs < statsBefore.minMs,
        )
    }

    /**
     * GeomagneticField caching benchmark:
     * Before: Instantiating GeomagneticField (Legendre polynomials, spherical harmonics) on every sample.
     * After: Grid-cell cached GeomagneticField.
     */
    @Test
    fun benchmarkGeomagneticFieldCache() {
        val lat = 39.7392
        val lon = -104.9903
        val alt = 1600.0
        val timeMs = 1760000000000L
        val sampleCount = 500

        Declination.clearCache()

        val (statsBefore, statsAfter) = benchmarkInterleaved(
            iterations = 9,
            warmup = 3,
            nameA = "GeomagneticField (Before: un-cached allocations on every sample)",
            runA = {
                var sum = 0f
                for (i in 0 until sampleCount) {
                    val field = GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), timeMs + i * 20L)
                    sum += field.declination
                }
                assertTrue(sum != 0f)
            },
            nameB = "GeomagneticField (After: grid-cell cached lookup)",
            runB = {
                var sum = 0f
                for (i in 0 until sampleCount) {
                    val field = Declination.field(lat, lon, alt, timeMs + i * 20L)
                    sum += field.declination
                }
                assertTrue(sum != 0f)
            },
        )

        assertTrue(
            "Cached GeomagneticField lookup must be faster than full Legendre polynomial evaluation",
            statsAfter.minMs < statsBefore.minMs,
        )
    }
}
