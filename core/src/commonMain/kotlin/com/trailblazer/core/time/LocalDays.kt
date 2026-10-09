package com.trailblazer.core.time

import kotlin.math.floor

/** Local-day windows in the device time zone; DST days are 23 or 25 hours long. */
object LocalDays {
    /** The instant of [hour]:00 local time on [epochDay] (days since 1970-01-01 in the local calendar). */
    private fun at(epochDay: Long, hour: Int, tz: PlatformTimeZone): Long {
        val (y, m, d) = CivilDate.civilFromDays(epochDay)
        return tz.localToEpochMs(y, m, d, hour, 0)
    }

    fun window(epochDay: Long, tz: PlatformTimeZone = PlatformTimeZone.current()): Pair<Long, Long> =
        at(epochDay, 0, tz) to at(epochDay + 1, 0, tz)

    fun today(nowMs: Long, tz: PlatformTimeZone = PlatformTimeZone.current()): Long =
        floor((nowMs + tz.offsetAtMs(nowMs)).toDouble() / 86_400_000.0).toLong()

    /** Local noon of [epochDay] to local noon of the next day: one whole night, never split at midnight. */
    fun noonToNoon(epochDay: Long, tz: PlatformTimeZone = PlatformTimeZone.current()): Pair<Long, Long> =
        at(epochDay, 12, tz) to at(epochDay + 1, 12, tz)

    /** The local date whose evening starts the night containing [nowMs]: before local noon, that is yesterday. */
    fun nightOf(nowMs: Long, tz: PlatformTimeZone = PlatformTimeZone.current()): Long {
        val day = today(nowMs, tz)
        return if (nowMs < noonToNoon(day, tz).first) day - 1 else day
    }
}
