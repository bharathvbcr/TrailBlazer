package com.example.trailblazer.weather

import com.trailblazer.core.time.CivilDate
import com.trailblazer.core.time.Iso8601
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Open-Meteo's times ("2026-09-30" and "2026-09-30T05:00") are wall-clock times at the forecast place, with no zone.
 * These turn them into what the screen needs: a real instant (using the response's UTC offset) for hours, and a
 * day name relative to the place's own today for days. Anything malformed gives null rather than a guess.
 */
object ForecastTimes {
    private val stamp = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?$""")
    private val utc = TimeZone.getTimeZone("UTC")

    /** Fields read as if the wall-clock time were UTC; null when out of range (month 13, hour 25, 30 February). */
    private fun wallAsUtcMs(s: String): Long? {
        val m = stamp.matchEntire(s.trim()) ?: return null
        val (y, mo, d) = m.destructured.let { Triple(it.component1().toInt(), it.component2().toInt(), it.component3().toInt()) }
        val h = m.groupValues[4].ifEmpty { "0" }.toInt()
        val min = m.groupValues[5].ifEmpty { "0" }.toInt()
        if (y !in 1900..2200 || mo !in 1..12 || d !in 1..Iso8601.daysInMonth(y, mo) || h !in 0..23 || min !in 0..59) return null
        val days = CivilDate.daysFromCivil(y, mo, d)
        return (days * 86_400L + h * 3_600L + min * 60L) * 1_000L
    }

    /** The instant of a local "yyyy-MM-ddTHH:mm" at a place [utcOffsetSeconds] from UTC. */
    fun epochMs(local: String, utcOffsetSeconds: Int?): Long? {
        val offset = utcOffsetSeconds ?: return null
        return wallAsUtcMs(local)?.let { it - offset * 1000L }
    }

    /** Whole days from [today] to [date], both "yyyy-MM-dd" (the place's calendar). */
    fun daysBetween(today: String, date: String): Long? {
        val a = wallAsUtcMs(today.take(10)) ?: return null
        val b = wallAsUtcMs(date.take(10)) ?: return null
        return Math.floorDiv(b - a, 86_400_000L)
    }

    /** "Today", "Tomorrow", else the short weekday ("Thu"); null for a malformed date. */
    fun dayLabel(date: String, today: String?, locale: Locale): String? {
        wallAsUtcMs(date) ?: return null
        val ms = wallAsUtcMs(date.take(10)) ?: return null
        return when (today?.let { daysBetween(it, date) }) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> format(ms, "EEE", locale)
        }
    }

    /** "Wed 30 Sep" in the locale's order; null for a malformed date. */
    fun dayLong(date: String, locale: Locale): String? {
        wallAsUtcMs(date) ?: return null
        val ms = wallAsUtcMs(date.take(10)) ?: return null
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
        return format(ms, pattern, locale)
    }

    private fun format(ms: Long, pattern: String, locale: Locale): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = utc }.format(ms)

    /** True when the place's clock differs from the phone's right now, so the screen can say whose time it shows. */
    fun differsFromPhone(utcOffsetSeconds: Int?, nowMs: Long, phone: TimeZone = TimeZone.getDefault()): Boolean =
        utcOffsetSeconds != null && phone.getOffset(nowMs) != utcOffsetSeconds * 1000
}
