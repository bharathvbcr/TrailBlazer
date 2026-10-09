package com.trailblazer.core.time

import java.util.Calendar
import java.util.TimeZone

actual class PlatformTimeZone(val zone: TimeZone) {
    actual fun offsetAtMs(epochMs: Long): Long = zone.getOffset(epochMs).toLong()

    actual fun localToEpochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance(zone)
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    actual companion object {
        actual fun current(): PlatformTimeZone = PlatformTimeZone(TimeZone.getDefault())
        actual fun of(zoneId: String): PlatformTimeZone = PlatformTimeZone(TimeZone.getTimeZone(zoneId))
        fun of(zone: TimeZone): PlatformTimeZone = PlatformTimeZone(zone)
    }
}
