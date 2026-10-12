package com.trailblazer.core.alerts

/**
 * Overspeed alert with hysteresis and re-arming. It fires when speed rises above the limit, then
 * stays quiet until speed drops [rearmMarginMps] below the limit — after which it can fire again.
 * (The web app fired once per session and was on by default; this one is off unless configured.)
 */
class SpeedAlert(private val limitMps: Double, rearmMarginMps: Double = 1.4) {
    init {
        require(limitMps > 0 && rearmMarginMps >= 0)
    }

    /**
     * The margin is capped at half the limit: a limit at or below the margin would put the re-arm speed at or
     * below zero, which no sample can fall under, so the alert could never fire a second time.
     */
    private val rearmMarginMps = minOf(rearmMarginMps, limitMps / 2)

    var over = false; private set

    /** Returns true exactly when the alert should fire for this sample. */
    fun update(speedMps: Double?): Boolean {
        if (speedMps == null || !speedMps.isFinite()) return false
        if (!over && speedMps > limitMps) {
            over = true
            return true
        }
        if (over && speedMps < limitMps - rearmMarginMps) over = false
        return false
    }
}
