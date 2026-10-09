package com.trailblazer.core.time

import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.defaultTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName

actual class PlatformTimeZone(val zone: NSTimeZone) {
    actual fun offsetAtMs(epochMs: Long): Long {
        val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
        return (zone.secondsFromGMTForDate(date) * 1000.0).toLong()
    }

    actual fun localToEpochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = NSCalendar.currentCalendar.copy() as NSCalendar
        calendar.timeZone = zone
        val comps = NSDateComponents().apply {
            this.year = year.toLong()
            this.month = month.toLong()
            this.day = day.toLong()
            this.hour = hour.toLong()
            this.minute = minute.toLong()
            this.second = 0
        }
        val date = calendar.dateFromComponents(comps)
        return (date?.timeIntervalSince1970?.times(1000.0) ?: 0.0).toLong()
    }

    actual companion object {
        actual fun current(): PlatformTimeZone = PlatformTimeZone(NSTimeZone.defaultTimeZone)
        actual fun of(zoneId: String): PlatformTimeZone =
            PlatformTimeZone(NSTimeZone.timeZoneWithName(zoneId) ?: NSTimeZone.defaultTimeZone)
    }
}
