package com.example.trailblazer.sensors

import kotlin.math.abs

/**
 * What a BLE receiver's fix is worth to the rest of the app. [com.example.trailblazer.tracking.TrackRecorder] keeps only fixes
 * with a reported accuracy of at most 30 m, so a fix with no accuracy is never recorded; these rules give every fix a number,
 * and the number is conservative: it never claims better than the receiver reported.
 */
object BleFixPolicy {
    /**
     * Accuracy (m) for a fix the receiver marks valid but gives no error estimate for (RMC and GLL sentences, an LNS frame
     * without a Position Quality). Consumer GNSS is typically within 5 m, so 15 m is a threefold margin that still passes the
     * 30 m recording gate, and only a receiver that said "valid fix" ever reaches it.
     */
    const val DEFAULT_ACCURACY_M = 15.0

    /**
     * Accuracy (m) for a position the receiver itself calls estimated (LNS Position Status 2). It is shown to the user as a
     * low-accuracy position and, being above the 30 m gate, is never recorded into a track.
     */
    const val ESTIMATED_ACCURACY_M = 100.0

    /** Horizontal error (m) per unit of HDOP, a typical user-equivalent range error for open-sky GNSS. */
    const val HDOP_TO_METERS = 5.0

    /** A Position Quality sample older than this no longer describes the latest LNS fix. */
    const val MAX_QUALITY_AGE_MS = 10_000L

    /** Plausibility ceiling for any accuracy figure, shared with the phone provider path. */
    const val MAX_ACCURACY_M = 100_000.0

    private const val DAY_MS = 86_400_000L

    /** Accuracy from an HDOP figure, or null when the figure is not a usable number. */
    fun accuracyFromHdop(hdop: Double?): Double? =
        hdop?.takeIf { it.isFinite() && it >= 0.0 }?.times(HDOP_TO_METERS)?.takeIf { it <= MAX_ACCURACY_M }

    /**
     * Turns a UTC time of day into epoch milliseconds. A receiver date ([epochDay], days since 1970) is used when it agrees
     * with the phone clock within a day; a receiver with a broken date (GPS week rollover, no date at all) falls back to the
     * calendar day, among yesterday, today and tomorrow, whose result is nearest the phone clock.
     */
    fun resolveTimeOfDay(timeOfDayMs: Long, epochDay: Long?, nowMs: Long): Long {
        if (epochDay != null) {
            val t = epochDay * DAY_MS + timeOfDayMs
            if (abs(t - nowMs) <= DAY_MS) return t
        }
        val today = Math.floorDiv(nowMs, DAY_MS)
        return (today - 1..today + 1).map { it * DAY_MS + timeOfDayMs }.minBy { abs(it - nowMs) }
    }

    /** A receiver-reported UTC instant if it is within a day of the phone clock, else the phone clock. */
    fun trustedUtc(utcMs: Long?, nowMs: Long): Long = if (utcMs != null && abs(utcMs - nowMs) <= DAY_MS) utcMs else nowMs
}
