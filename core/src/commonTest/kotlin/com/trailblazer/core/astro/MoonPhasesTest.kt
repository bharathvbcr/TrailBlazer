package com.trailblazer.core.astro

import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.time.CivilDate
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoonPhasesTest {
    private val day = 86_400_000L

    private fun utc(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long {
        val days = CivilDate.daysFromCivil(y, m, d)
        val secs = days * 86_400L + h * 3600L + min * 60L
        return secs * 1000L
    }

    @Test
    fun theNextFourPrincipalPhasesComeInOrderAQuarterMonthApart() {
        val rnd = Random(11)
        repeat(60) {
            val from = utc(1990, 1, 1) + (rnd.nextDouble() * 60 * 365.25 * day).toLong()
            val list = LunarPhase.upcoming(from)
            assertEquals(4, list.size)
            assertTrue(list.first().epochMs >= from && list.zipWithNext().all { (a, b) -> b.epochMs > a.epochMs }, "strictly after 'from', in time order")
            // Measured 1990–2050: consecutive principal phases are 6.59–8.23 days apart, so the first is at most ~8.2 days out.
            assertTrue(list.first().epochMs - from <= 8 * day + day / 2, "first one within a quarter month")
            val cycle = listOf(MoonPhaseName.NewMoon, MoonPhaseName.FirstQuarter, MoonPhaseName.FullMoon, MoonPhaseName.LastQuarter)
            val start = cycle.indexOf(list.first().name)
            assertEquals(List(4) { cycle[(start + it) % 4] }, list.map { it.name }, "the four principal phases in cyclic order")
            // Quarter-month spacing varies with the Moon's elliptical orbit (measured 6.59–8.23 days).
            list.zipWithNext().forEach { (a, b) -> assertTrue((b.epochMs - a.epochMs) in (6 * day + day / 2)..(8 * day + day / 2), "gap ${(b.epochMs - a.epochMs) / day.toDouble()} d") }
            // Each instant really is that phase: the elongation there is its target angle.
            list.forEach { p -> assertTrue(abs(angleDiff(p.targetDeg, LunarPhase.elongation(p.epochMs))) < 0.05, "${p.name} at ${p.epochMs}") }
        }
    }

    @Test
    fun matchesTheObservatoryTimesAlreadyCheckedForNext() {
        // Same USNO instants as AstroGoldenTest: full moon 2026-09-26 16:49 UTC is among the four after 2026-09-20.
        val full = LunarPhase.upcoming(utc(2026, 9, 20)).single { it.name == MoonPhaseName.FullMoon }
        assertTrue(abs(full.epochMs - utc(2026, 9, 26, 16, 49)) <= 3 * 60_000L)
    }

    @Test
    fun iconIlluminationFollowsTheCycle() {
        assertEquals(0.0, LunarPhase.illuminationFor(MoonPhaseName.NewMoon), 1e-9)
        assertEquals(0.5, LunarPhase.illuminationFor(MoonPhaseName.FirstQuarter), 1e-9)
        assertEquals(1.0, LunarPhase.illuminationFor(MoonPhaseName.FullMoon), 1e-9)
        assertEquals(0.5, LunarPhase.illuminationFor(MoonPhaseName.LastQuarter), 1e-9)
        assertTrue(LunarPhase.illuminationFor(MoonPhaseName.WaxingCrescent) in 0.1..0.2)
        assertTrue(LunarPhase.illuminationFor(MoonPhaseName.WaningGibbous) in 0.8..0.9)
        MoonPhaseName.entries.forEach { assertEquals(it, LunarPhase.nameFor(LunarPhase.typicalElongation(it))) }
    }
}

