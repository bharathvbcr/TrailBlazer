package com.trailblazer.core.geo

import com.trailblazer.core.budgetMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measurements, not benchmarks: they print what decoding and projecting cost on this machine (JVM, not a phone) so the
 * numbers can be quoted, and fail only if something is an order of magnitude beyond reason. On-device smoothness is
 * not measured here.
 */
class TileCostMeasurementTest {

    private fun minOfRuns(runs: Int, block: () -> Unit): Double {
        var best = Double.MAX_VALUE
        repeat(runs) {
            val t0 = System.nanoTime()
            block()
            best = minOf(best, (System.nanoTime() - t0) / 1e6)
        }
        return best
    }

    @Test
    fun decodingADenseContourTile() {
        // 40 contour lines of 5,000 points: a dense z14 mountain tile, about 200,000 points.
        val features = (0 until 40).map { f ->
            val xy = LongArray(2 * 4999) { i -> if (i % 2 == 0) 3L else ((i / 2 + f) % 11).toLong() - 5L }
            // Cumulative targets, since lineTo takes absolute positions.
            var x = 0L
            var y = 0L
            val abs = LongArray(xy.size)
            for (k in 0 until 4999) { x += xy[2 * k]; y += xy[2 * k + 1]; abs[2 * k] = x; abs[2 * k + 1] = y }
            TileFixtures.FeatureSpec(2, TileFixtures.Geom().moveTo(0, 0).lineTo(*abs).build(), listOf(0, 0), f.toLong())
        }
        val tile = TileFixtures.gzip(
            TileFixtures.mvtTile(TileFixtures.mvtLayer("contour", features, keys = listOf("ele"), values = listOf(TileFixtures.mvtValueString("1200")))),
        )
        var points = 0
        val ms = minOfRuns(7) {
            val t = TileUtils.parseMvt(tile)
            points = t.layers.sumOf { l -> l.features.sumOf { f -> f.geometry.sumOf { it.size } } }
        }
        println("MEASURE decode ${tile.size / 1024} KiB gzip tile, $points points: ${"%.1f".format(ms)} ms (min of 7, JVM)")
        assertEquals(40 * 5000, points)
        assertTrue(ms < budgetMs(3000).toDouble(), "decoding took $ms ms")
    }

    @Test
    fun projectingALongRecordedTrack() {
        // 100,000 GPS fixes on a 30 km walk.
        val pts = (0 until 100_000).map { i -> LatLon(46.0 + i * 3e-6, 7.0 + kotlin.math.sin(i / 2000.0) * 0.02) }
        val center = LatLon(46.15, 7.0)
        // Before: a fresh 200,000-float array on every frame. After: one buffer reused by every frame.
        val allocating = minOfRuns(15) {
            TileUtils.projectPolyline(pts, center, 12.0, 1080f, 1920f, TileUtils.PolylineBuffer())
        }
        val reused = TileUtils.PolylineBuffer()
        TileUtils.projectPolyline(pts, center, 12.0, 1080f, 1920f, reused)
        val reusedMs = minOfRuns(15) { TileUtils.projectPolyline(pts, center, 12.0, 1080f, 1920f, reused) }
        val kept = reused.count
        val naive = minOfRuns(9) { for (p in pts) TileUtils.latLonToScreen(p, center, 12.0, 1080f, 1920f) }
        println(
            "MEASURE project 100,000 track points: fresh buffer per frame ${"%.2f".format(allocating)} ms, reused buffer " +
                "${"%.2f".format(reusedMs)} ms, keeps $kept of 100,000 (naive per-point project: ${"%.2f".format(naive)} ms) (min of 15, JVM)",
        )
        assertTrue(kept < 5000, "kept $kept")
        assertTrue(reusedMs < budgetMs(1000).toDouble())
    }
}
