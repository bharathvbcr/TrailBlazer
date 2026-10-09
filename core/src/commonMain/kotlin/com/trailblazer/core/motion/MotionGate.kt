package com.trailblazer.core.motion

import kotlin.math.sqrt

/**
 * Tracks whether the phone has been continuously still for [stillForNs] nanoseconds. Stillness is broken by
 * samples differing from gravity by more than [thresholdMps2], by sample gaps longer than [maxSampleGapNs], or
 * by an external wake trigger via [onWake].
 */
class MotionGate(
    private val stillForNs: Long = STILL_FOR_NS,
    private val thresholdMps2: Double = 0.5,
    private val maxSampleGapNs: Long = 5_000_000_000L,
    gravityAlpha: Double = GRAVITY_ALPHA,
) {
    var stationary = false
        private set

    private val filter = GravityLowPass(gravityAlpha)
    private var lastNs: Long? = null
    private var stillSinceNs: Long? = null

    /** Feeds one sample and returns whether the phone is now stationary. Non-finite samples are ignored. */
    fun onSample(timestampNs: Long, values: FloatArray): Boolean {
        if (values.size < 3) return stationary
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        return onSample(timestampNs, x, y, z)
    }

    fun onSample(timestampNs: Long, x: Double, y: Double, z: Double): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return stationary
        val prev = lastNs
        lastNs = timestampNs
        val gap = prev == null || timestampNs <= prev || timestampNs - prev > maxSampleGapNs

        val g = filter.gravity ?: Vec3(x, y, z)
        val dx = x - g.x
        val dy = y - g.y
        val dz = z - g.z
        val moved = sqrt(dx * dx + dy * dy + dz * dz) > thresholdMps2
        filter.update(x, y, z)

        when {
            moved -> { stationary = false; stillSinceNs = timestampNs }
            stationary -> Unit
            gap -> stillSinceNs = timestampNs
            else -> {
                val since = stillSinceNs ?: timestampNs.also { stillSinceNs = it }
                if (timestampNs - since >= stillForNs) stationary = true
            }
        }
        return stationary
    }

    /** Something outside the samples says the phone moved: a significant-motion trigger, or a fix that moved. */
    fun onWake() {
        stationary = false
        stillSinceNs = null
        filter.reset()
    }

    companion object {
        const val STILL_FOR_NS = 5 * 60 * 1_000_000_000L
        const val GRAVITY_ALPHA = 0.1
    }
}
