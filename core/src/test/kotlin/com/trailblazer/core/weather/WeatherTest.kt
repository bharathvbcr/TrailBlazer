package com.trailblazer.core.weather

import com.trailblazer.core.atmo.AirDensity
import com.trailblazer.core.atmo.BoilingPoint
import com.trailblazer.core.atmo.DensityAltitude
import com.trailblazer.core.atmo.DewPoint
import com.trailblazer.core.atmo.Isa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphereTest {
    @Test
    fun isaRoundTripAndKnownValues() {
        assertEquals(0.0, Isa.altitudeM(1013.25)!!, 1e-6)
        // ISA: 1000 m ≈ 898.75 hPa, 3000 m ≈ 701.1 hPa.
        assertEquals(1000.0, Isa.altitudeM(898.75)!!, 2.0)
        assertEquals(3000.0, Isa.altitudeM(701.1)!!, 3.0)
        val qnh = Isa.qnhHpa(898.75, 1000.0)!!
        assertEquals(1013.25, qnh, 0.1)
        for (bad in listOf(Double.NaN, -1.0, 0.0, Double.POSITIVE_INFINITY)) assertNull(Isa.altitudeM(bad))
        assertNull(Isa.qnhHpa(1013.25, 50_000.0))
    }

    @Test
    fun densityAltitudeNeedsARealTemperature() {
        // Regression: the web app set OAT = standard temperature and also double-counted altitude.
        assertNull(DensityAltitude.meters(1013.25, null))
        assertEquals(0.0, DensityAltitude.meters(1013.25, 15.0)!!, 15.0)
        // At ISA conditions, density altitude equals pressure altitude.
        val pa = Isa.pressureAltitudeM(843.1)!!
        assertEquals(pa, DensityAltitude.meters(843.1, Isa.standardTempC(pa))!!, 25.0)
        // A hot day (+20 °C over standard) at that station raises DA by roughly 118.8 ft/°C ≈ 36 m/°C.
        val hot = DensityAltitude.meters(843.1, Isa.standardTempC(pa) + 20)!!
        assertEquals(pa + 20 * 36.2, hot, 120.0)
    }

    @Test
    fun boilingPointAndDewPoint() {
        assertEquals(100.0, BoilingPoint.celsius(1013.25)!!, 0.1)
        assertEquals(90.0, BoilingPoint.celsius(701.1)!!, 1.0)
        assertNull(BoilingPoint.celsius(-5.0))
        assertNull(BoilingPoint.celsius(Double.NaN))
        val dp = DewPoint.celsius(25.0, 60.0)!!
        assertEquals(16.7, dp, 0.2)
        assertEquals(60.0, DewPoint.relativeHumidity(25.0, dp), 0.01)
        assertNull(DewPoint.celsius(25.0, 0.0))
        assertNull(AirDensity.kgPerM3(1013.25, null))
        assertEquals(1.225, AirDensity.kgPerM3(1013.25, 15.0)!!, 0.001)
    }
}

class PressureTrendTest {
    private val h = 3_600_000L
    private val now = 10 * 24 * h

    private fun series(minutes: Int, everyMin: Int, p: (Double) -> Double, elev: (Double) -> Double?) =
        (0..minutes step everyMin).map { m ->
            val t = now - (minutes - m) * 60_000L
            val hours = m / 60.0
            PressureSample(t, p(hours), elev(hours))
        }

    @Test
    fun insufficientBelowSixtyMinutesOrSixSamples() {
        val r = PressureTrend.compute(series(45, 5, { 1013.0 }, { 100.0 }), now)
        assertTrue(r is TrendResult.Insufficient)
        val few = PressureTrend.compute(series(120, 30, { 1013.0 }, { 100.0 }), now)
        assertTrue(few is TrendResult.Insufficient)
    }

    @Test
    fun twoHourWindowScaledToThreeHours() {
        // Regression: the web app always divided by 3 h, so 2 h of −2 hPa read as −2 instead of −3 hPa/3h.
        val r = PressureTrend.compute(series(120, 10, { 1013.0 - it }, { 50.0 }), now) as TrendResult.Trend
        assertEquals(-3.0, r.hpaPer3h, 0.01)
        assertEquals(TrendBasis.Station, r.basis)
        assertEquals(Tendency.Falling, r.tendency)
    }

    @Test
    fun descentAtConstantWeatherReadsSteady() {
        // 300 m descent in 2 h with unchanged sea-level pressure: station pressure rises ~35 hPa.
        val qnh = 1013.25
        val samples = series(120, 10, { hr ->
            val e = 1300.0 - 150.0 * hr
            qnh * Math.pow(1 - e / 44330.77, 1 / 0.190263)
        }, { hr -> 1300.0 - 150.0 * hr })
        val r = PressureTrend.compute(samples, now) as TrendResult.Trend
        assertEquals(TrendBasis.SeaLevel, r.basis)
        assertEquals(Tendency.Steady, r.tendency)
        assertEquals(0.0, r.hpaPer3h, 0.05)
    }

    @Test
    fun unknownElevationIsLabelled() {
        val r = PressureTrend.compute(series(180, 15, { 1010.0 + it }, { null }), now) as TrendResult.Trend
        assertEquals(TrendBasis.StationElevationUnknown, r.basis)
        assertEquals(3.0, r.hpaPer3h, 0.01)
    }

    @Test
    fun corruptedSamplesAreIgnored() {
        val good = series(120, 10, { 1000.0 }, { 10.0 })
        val bad = listOf(
            PressureSample(now - 30_000, Double.NaN, 10.0),
            PressureSample(now - 40_000, Double.POSITIVE_INFINITY, 10.0),
            PressureSample(now - 50_000, 5000.0, 10.0),
            PressureSample(now + 3_600_000, 900.0, 10.0),
        )
        val r = PressureTrend.compute(good + bad, now) as TrendResult.Trend
        assertEquals(0.0, r.hpaPer3h, 1e-9)
    }

    @Test
    fun tendencyScaleIsSymmetric() {
        assertEquals(Tendency.Steady, PressureTrend.tendency(0.4))
        assertEquals(Tendency.RisingSlowly, PressureTrend.tendency(1.0))
        assertEquals(Tendency.FallingSlowly, PressureTrend.tendency(-1.0))
        assertEquals(Tendency.FallingQuickly, PressureTrend.tendency(-5.0))
        assertEquals(Tendency.RisingVeryRapidly, PressureTrend.tendency(7.0))
    }

    @Test
    fun stormAlertHysteresis() {
        fun t(v: Double) = TrendResult.Trend(v, PressureTrend.tendency(v), TrendBasis.Station, 180, 18, 1000.0)
        var on = StormAlert.next(false, t(-3.9))
        assertFalse(on)
        on = StormAlert.next(on, t(-4.0)); assertTrue(on)
        on = StormAlert.next(on, t(-2.5)); assertTrue(on)
        on = StormAlert.next(on, t(-1.9)); assertFalse(on)
        assertFalse(StormAlert.next(true, TrendResult.Insufficient(10, 2)))
    }

    @Test
    fun zambretti() {
        assertEquals(ZambrettiForecast.SettledFine, Zambretti.forecast(1045.0, 2.0))
        assertEquals(ZambrettiForecast.FinePossibleShowers, Zambretti.forecast(1013.0, 0.0))
        assertEquals(ZambrettiForecast.StormyMuchRain, Zambretti.forecast(955.0, -5.0))
        assertNull(Zambretti.forecast(900.0, 0.0))
        assertNull(Zambretti.forecast(Double.NaN, 0.0))
    }
}
