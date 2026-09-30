package com.example.trailblazer.ui.stars

import com.trailblazer.core.astro.SkyProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartIdentifyTest {
    private fun obj(name: String, az: Double, alt: Double, mag: Double, kind: ChartKind = ChartKind.Star) = ChartObject(null, name, az, alt, mag, kind)

    private val vega = obj("Vega", 60.0, 50.0, 0.0)
    private val faint = obj("Faint", 61.0, 50.5, 3.4)
    private val mars = obj("Mars", 200.0, 20.0, 1.2, ChartKind.Planet)
    private val below = obj("Below", 90.0, -0.5, -1.0)

    private fun tapOn(o: ChartObject, facing: Double) = SkyProjection.project(o.azimuthDeg, o.altitudeDeg, facing)!!

    @Test
    fun aTapFindsTheObjectUnderIt() {
        for (facing in listOf(0.0, 90.0, 180.0, 359.9)) {
            val p = tapOn(mars, facing)
            assertEquals(mars, identify(listOf(vega, faint, mars, below), facing, p.x, p.y))
        }
    }

    @Test
    fun onANearTieTheBrighterObjectWins() {
        val a = tapOn(vega, 180.0)
        val b = tapOn(faint, 180.0)
        val mid = SkyProjection.Point((a.x + b.x) / 2, (a.y + b.y) / 2)
        assertEquals(vega, identify(listOf(faint, vega), 180.0, mid.x, mid.y))
    }

    @Test
    fun emptySkyBelowHorizonAndGarbageTapsFindNothing() {
        assertNull(identify(listOf(vega, mars, below), 180.0, 0.95, -0.95)) // empty corner of the disc
        assertNull(identify(listOf(below), 180.0, 0.0, 1.0)) // below-horizon objects are never drawn, so never found
        assertNull(identify(emptyList(), 180.0, 0.0, 0.0))
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(identify(listOf(vega), 180.0, bad, 0.0))
            assertNull(identify(listOf(vega), bad, 0.0, 0.0))
        }
    }
}
