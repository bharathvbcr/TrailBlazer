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
    /** Matches the import cap: a planned trip stays editable and linkable. */
    const val MAX_STOPS = 50

    /**
     * Consecutive stops closer than this are the same place. One metre covers GPS noise,
     * the antimeridian (180° and −180°), and both poles, without merging a trailhead and its parking.
     */
    const val SAME_PLACE_M = 1.0

    sealed interface Problem {
        data object TooFewStops : Problem
        data object TooManyStops : Problem
        data class DuplicateAdjacent(val index: Int) : Problem
        data class DatesOutOfOrder(val index: Int) : Problem
        data class DuplicateId(val index: Int) : Problem
    }

    fun check(stops: List<Stop>): List<Problem> {
        val out = ArrayList<Problem>()
        if (stops.size < 2) out += Problem.TooFewStops
        if (stops.size > MAX_STOPS) out += Problem.TooManyStops
        val seen = HashSet<String>()
        var lastDated: Long? = null
        stops.forEachIndexed { i, stop ->
            if (!seen.add(stop.id)) out += Problem.DuplicateId(i)
            if (i > 0) {
                val gap = Geo.distanceM(stops[i - 1].position, stop.position)
                if (!gap.isFinite() || gap < SAME_PLACE_M) out += Problem.DuplicateAdjacent(i)
            }
            val day = stop.plannedDay
            if (day != null) {
                val earlier = lastDated
                if (earlier != null && day < earlier) out += Problem.DatesOutOfOrder(i)
                lastDated = day
            }
        }
        return out
    }
}

/** Spreadsheet-style stop letters: A…Z, AA… The UI must not crash on a bad index. */
object StopLabel {
    fun of(index: Int): String {
        if (index < 0) return "?"
        var n = index
        val chars = ArrayDeque<Char>()
        do {
            chars.addFirst('A' + (n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return chars.joinToString("")
    }
}

/** File name stem for a trip export. Strips path characters and control characters. */
object TripFiles {
    fun exportBase(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("""[^\p{L}\p{N}._ -]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '_')
            .take(60)
            .trim(' ', '.', '_')
        return cleaned.ifBlank { "trip" }
    }
}

/** Sun times per stop on its planned day. The caller supplies each day's window in its time zone. */
object DaylightPlanner {
    data class Row(val stop: Stop, val day: SolarDay)

    fun plan(stops: List<Stop>, window: (Long) -> Pair<Long, Long>): List<Row> =
        stops.mapNotNull { s ->
            val d = s.plannedDay ?: return@mapNotNull null
            val span = runCatching { window(d) }.getOrNull() ?: return@mapNotNull null
            val (start, end) = span
            if (end <= start) return@mapNotNull null
            runCatching { Row(s, SolarEvents.day(s.position.lat, s.position.lon, start, end)) }.getOrNull()
        }
}
