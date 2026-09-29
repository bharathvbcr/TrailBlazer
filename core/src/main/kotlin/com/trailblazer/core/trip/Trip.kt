package com.trailblazer.core.trip

import com.trailblazer.core.astro.SolarDay
import com.trailblazer.core.astro.SolarEvents
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon

enum class StopKind { Start, Visit, Night, End }

/** A planned stop. [plannedDay] is days since 1970-01-01 in the traveller's calendar, when known. */
data class Stop(
    val id: String,
    val name: String,
    val kind: StopKind,
    val position: LatLon,
    val plannedDay: Long? = null,
    val note: String? = null,
)

data class Trip(val id: String, val name: String, val stops: List<Stop>)

/** Straight-line ("as the crow flies") leg; road distance is deliberately not guessed. */
data class Leg(val from: Stop, val to: Stop, val distanceM: Double, val initialBearingDeg: Double)

object LegCalculator {
    fun legs(stops: List<Stop>): List<Leg> = stops.zipWithNext { a, b ->
        Leg(a, b, Geo.distanceM(a.position, b.position), Geo.initialBearing(a.position, b.position))
    }

    fun totalM(stops: List<Stop>): Double = legs(stops).sumOf { it.distanceM }
}

/** Rules a trip must satisfy before it is saved or handed to a map app. */
object TripRules {
    sealed interface Problem {
        data object TooFewStops : Problem
        data class DuplicateAdjacent(val index: Int) : Problem
        data class DatesOutOfOrder(val index: Int) : Problem
    }

    fun check(stops: List<Stop>): List<Problem> {
        val out = ArrayList<Problem>()
        if (stops.size < 2) out += Problem.TooFewStops
        stops.zipWithNext().forEachIndexed { i, (a, b) ->
            if (a.position == b.position) out += Problem.DuplicateAdjacent(i + 1)
            val da = a.plannedDay
            val db = b.plannedDay
            if (da != null && db != null && db < da) out += Problem.DatesOutOfOrder(i + 1)
        }
        return out
    }
}

/** Sun times per stop on its planned day. The caller supplies each day's window in its time zone. */
object DaylightPlanner {
    data class Row(val stop: Stop, val day: SolarDay)

    fun plan(stops: List<Stop>, window: (Long) -> Pair<Long, Long>): List<Row> =
        stops.mapNotNull { s ->
            val d = s.plannedDay ?: return@mapNotNull null
            val (start, end) = window(d)
            Row(s, SolarEvents.day(s.position.lat, s.position.lon, start, end))
        }
}
