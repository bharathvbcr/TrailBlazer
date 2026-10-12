package com.example.trailblazer.ui.trips

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.geo.TileUtils.GeometryType
import com.trailblazer.core.geo.TileUtils.TilePoint
import com.trailblazer.core.geo.TileUtils.VectorFeature
import com.trailblazer.core.geo.TileUtils.VectorLayer
import com.trailblazer.core.geo.TileUtils.VectorTile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VectorTilePathsTest {

    private fun pts(vararg xy: Float): List<TilePoint> = xy.toList().chunked(2).map { TilePoint(it[0], it[1]) }

    private fun feature(type: GeometryType, geometry: List<List<TilePoint>>, props: Map<String, String> = emptyMap(), layer: String = "l") =
        VectorFeature(1, type, geometry, props, layer)

    private fun tile(layerName: String, vararg features: VectorFeature) = VectorTile(listOf(VectorLayer(layerName, features.toList(), 4096)))

    @Test
    fun linesOfTheSameStyleShareOnePathAndStylesAreSeparate() {
        val line = pts(0f, 0f, 0.5f, 0.5f, 1f, 0.2f)
        val t = tile(
            "contour",
            feature(GeometryType.LineString, listOf(line), mapOf("ele" to "1200")),
            feature(GeometryType.LineString, listOf(line, line), mapOf("ele" to "1400")),
            feature(GeometryType.LineString, listOf(line), mapOf("ele" to "1250")),
        ).let { contour ->
            VectorTile(
                contour.layers + VectorLayer("water", listOf(feature(GeometryType.Polygon, listOf(line), layer = "water")), 4096) +
                    VectorLayer("misc", listOf(feature(GeometryType.LineString, listOf(line), layer = "misc")), 4096),
            )
        }
        val prepared = prepareVectorTile(t)
        assertEquals(
            listOf(VectorKind.Other, VectorKind.Water, VectorKind.ContourMinor, VectorKind.ContourMajor),
            prepared.paths.map { it.kind },
        )
        assertEquals(0, prepared.points.size)
        assertEquals(1.0f, VectorKind.Other.strokeWidth)
        assertEquals(2.2f, VectorKind.ContourMajor.strokeWidth)
    }

    @Test
    fun pointFeaturesBecomePointsNotLines() {
        val t = tile(
            "peaks",
            feature(GeometryType.Point, listOf(pts(0.25f, 0.5f), pts(0.75f, 0.125f))),
            feature(GeometryType.Point, listOf(pts(1f, 1f))),
        )
        val prepared = prepareVectorTile(t)
        assertTrue("points are not drawn as strokes", prepared.paths.isEmpty())
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 0.75f, 0.125f, 1f, 1f), prepared.points, 0f)
    }

    @Test
    fun degenerateGeometryIsSkippedAndTheTileCostGrowsWithItsPoints() {
        val empty = prepareVectorTile(VectorTile(emptyList()))
        assertTrue(empty.paths.isEmpty())
        val tiny = prepareVectorTile(tile("l", feature(GeometryType.LineString, listOf(pts(0.5f, 0.5f)))))
        assertTrue("a one-point 'line' draws nothing", tiny.paths.isEmpty())
        val big = prepareVectorTile(tile("l", feature(GeometryType.LineString, listOf(pts(*FloatArray(20_000) { (it % 100) / 100f })))))
        assertEquals(1, big.paths.size)
        assertTrue(big.bytes > empty.bytes + 9_000 * 16)
    }

    @Test
    fun measurePreparingADenseTile() {
        // 40 contour lines of 5,000 points: about 200,000 points, a dense z14 tile.
        val lines = (0 until 40).map { f ->
            feature(
                GeometryType.LineString,
                listOf(pts(*FloatArray(10_000) { i -> if (i % 2 == 0) (i / 2) / 5000f else ((i / 2 + f) % 97) / 97f })),
                mapOf("ele" to "${1000 + 10 * f}"),
                "contour",
            )
        }
        val t = VectorTile(listOf(VectorLayer("contour", lines, 4096)))
        var best = Double.MAX_VALUE
        repeat(7) {
            val t0 = System.nanoTime()
            prepareVectorTile(t)
            best = minOf(best, (System.nanoTime() - t0) / 1e6)
        }
        println(
            "MEASURE prepare 200,000-point vector tile paths: ${"%.1f".format(best)} ms once per loaded tile, off the main thread " +
                "(min of 7, JVM + native Skia path). The previous code paid this construction per tile on every frame.",
        )
        assertTrue("took $best ms", best < 5_000.0)
    }
}
