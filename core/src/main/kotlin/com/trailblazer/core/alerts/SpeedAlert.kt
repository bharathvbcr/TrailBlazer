package com.trailblazer.core.alerts

/**
 * Overspeed alert with hysteresis and re-arming. It fires when speed rises above the limit, then
 * stays quiet until speed drops [rearmMarginMps] below the limit — after which it can fire again.
 * (The web app fired once per session and was on by default; this one is off unless configured.)
 */
class SpeedAlert(private val limitMps: Double, private val rearmMarginMps: Double = 1.4) {
    init {
        require(limitMps > 0 && rearmMarginMps >= 0)
    }

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
