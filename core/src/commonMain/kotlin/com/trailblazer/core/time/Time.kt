package com.trailblazer.core.time

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Time scales used by the astronomy code. All instants are Unix epoch milliseconds (UTC);
 * the Android app converts to local wall time only for display.
 */
object JulianDay {
    const val J2000 = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5
    private const val MS_PER_DAY = 86_400_000.0

    /** Julian Day in Universal Time. */
    fun fromEpochMillis(ms: Long): Double = ms / MS_PER_DAY + UNIX_EPOCH_JD

    fun toEpochMillis(jd: Double): Long = ((jd - UNIX_EPOCH_JD) * MS_PER_DAY).roundToLong()

    /** Julian Ephemeris Day (Terrestrial Time) for a UT instant. */
    fun ephemeris(ms: Long): Double = fromEpochMillis(ms) + DeltaT.seconds(ms) / 86_400.0

    /** Julian centuries since J2000.0 for a Julian Day. */
    fun centuries(jd: Double): Double = (jd - J2000) / 36525.0
}

/**
 * ΔT = TT − UT in seconds, polynomial fits by Espenak & Meeus (NASA, 2006),
 * valid 1900–2150. Outside that range the nearest fit is used; the error there
 * is minutes, which only matters for lunar work far from the present.
 */
object DeltaT {
    fun seconds(ms: Long): Double {
        val y = decimalYear(ms)
        return when {
            y < 1920 -> { val t = y - 1900; -2.79 + 1.494119 * t - 0.0598939 * t * t + 0.0061966 * t * t * t - 0.000197 * t * t * t * t }
            y < 1941 -> { val t = y - 1920; 21.20 + 0.84493 * t - 0.076100 * t * t + 0.0020936 * t * t * t }
            y < 1961 -> { val t = y - 1950; 29.07 + 0.407 * t - t * t / 233 + t * t * t / 2547 }
            y < 1986 -> { val t = y - 1975; 45.45 + 1.067 * t - t * t / 260 - t * t * t / 718 }
            y < 2005 -> { val t = y - 2000; 63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t + 0.000651814 * t * t * t * t + 0.00002373599 * t * t * t * t * t }
            y < 2050 -> { val t = y - 2000; 62.92 + 0.32217 * t + 0.005589 * t * t }
            else -> { val u = (y - 1820) / 100; -20 + 32 * u * u - 0.5628 * (2150 - y) }
        }
    }

    private fun decimalYear(ms: Long): Double = 1970.0 + ms / (365.2425 * 86_400_000.0)
}

/** Proleptic Gregorian civil date arithmetic (Howard Hinnant's algorithms); no java.time needed on API 24. */
object CivilDate {
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = floorDiv(y.toLong(), 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Returns (year, month 1-12, day 1-31) for days since 1970-01-01. */
    fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468
        val era = floorDiv(z, 146097)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
    }

    private fun floorDiv(a: Long, b: Long): Long = floor(a.toDouble() / b).toLong()
}

/** Minimal ISO-8601 instant support for GPX/KML timestamps. */
object Iso8601 {
    private val pattern = Regex(
        """^\s*(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?\s*$""",
    )

    /** Parses an ISO-8601 date-time; a missing zone is taken as UTC. Returns null when malformed or out of range. */
    fun parse(text: String): Long? {
        if (text.length > 64) return null
        val m = pattern.matchEntire(text) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].ifEmpty { "0" }.toInt()
        val minute = g[5].ifEmpty { "0" }.toInt()
        val second = g[6].ifEmpty { "0" }.toInt()
        if (month !in 1..12 || day !in 1..daysInMonth(year, month) || hour > 23 || minute > 59 || second > 60) return null
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.substring(0, 3)?.toInt() ?: 0
        val offsetMinutes = when (val z = g[8]) {
            "", "Z" -> 0
            else -> {
                val sign = if (z[0] == '-') -1 else 1
                val digits = z.substring(1).replace(":", "")
                val oh = digits.substring(0, 2).toInt()
                val om = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
                if (oh > 18 || om > 59) return null
                sign * (oh * 60 + om)
            }
        }
        val days = CivilDate.daysFromCivil(year, month, day)
        val secs = days * 86_400 + hour * 3600 + minute * 60 + second - offsetMinutes * 60L
        return secs * 1000 + millis
    }

    /** Formats an instant as `yyyy-MM-ddTHH:mm:ssZ` (UTC), or with millis when non-zero. */
    fun format(ms: Long): String {
        val days = floor(ms.toDouble() / 86_400_000.0).toLong()
        val msOfDay = ms - days * 86_400_000L
        val (y, mo, d) = CivilDate.civilFromDays(days)
        val h = msOfDay / 3_600_000
        val mi = msOfDay / 60_000 % 60
        val s = msOfDay / 1000 % 60
        val milli = msOfDay % 1000
        val base = "${y.toString().padStart(4, '0')}-${mo.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}T${h.toString().padStart(2, '0')}:${mi.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        return if (milli == 0L) "${base}Z" else "$base.${milli.toString().padStart(3, '0')}Z"
    }

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
