package com.trailblazer.core.motion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** What the level should make the phone feel like. */
enum class LevelCue {
    /** A light tick: the tilt just crossed a whole degree within [LevelHaptics.DETENT_MAX_DEG] of level. */
    Detent,

    /** A firm confirm: the phone has just become level. */
    Level,

    /** A warning: the tilt has just passed the steep limit (vehicle mode). */
    Steep,
}

/** Tilt past which a vehicle is steep. Side tilt is the rollover risk, so it has its own, lower limit. */
data class SteepLimit(val pitchDeg: Double, val rollDeg: Double) {
    fun exceeded(pitchDeg: Double, rollDeg: Double): Boolean = abs(pitchDeg) > this.pitchDeg || abs(rollDeg) > this.rollDeg

    /** Clearly back under both limits, by [LevelHaptics.STEEP_REARM_DEG]: only then can the warning fire again. */
    internal fun clear(pitchDeg: Double, rollDeg: Double): Boolean =
        abs(pitchDeg) < this.pitchDeg - LevelHaptics.STEEP_REARM_DEG && abs(rollDeg) < this.rollDeg - LevelHaptics.STEEP_REARM_DEG
}

/**
 * Turns a stream of pitch/roll readings into haptic cues, so the level can be used by feel.
 *
 * Every cue has hysteresis, because a resting phone's reading jitters by a tenth of a degree or so: "level" fires on
 * entering [LEVEL_DEG] and re-arms only beyond [LEVEL_REARM_DEG]; a detent changes band only once the tilt is
 * [DETENT_HYSTERESIS_DEG] past a whole degree; the steep warning re-arms [STEEP_REARM_DEG] under its limit. No two cues
 * come closer than [MIN_GAP_MS]; a Level or Steep cue that falls inside that gap is kept and fires on the next reading
 * instead of being lost. Non-finite readings are ignored.
 */
class LevelHaptics {
    private var levelArmed = true
    private var steepArmed = true
    private var band: Int? = null
    private var lastCueMs: Long? = null

    /** The cue for this reading, if any. [steep] is null when there is no steep limit (flat mode). */
    fun update(pitchDeg: Double, rollDeg: Double, nowMs: Long, steep: SteepLimit?): LevelCue? {
        if (!pitchDeg.isFinite() || !rollDeg.isFinite()) return null
        val tilt = max(abs(pitchDeg), abs(rollDeg))
        val last = lastCueMs
        // A clock that jumps backwards must not silence cues until it catches up.
        val gapOk = last == null || nowMs < last || nowMs - last >= MIN_GAP_MS

        if (tilt > LEVEL_REARM_DEG) levelArmed = true
        if (steep == null || steep.clear(pitchDeg, rollDeg)) steepArmed = true

        val prevBand = band
        val nextBand = nextBand(prevBand, tilt)
        band = nextBand

        val cue = when {
            steep != null && steepArmed && steep.exceeded(pitchDeg, rollDeg) -> if (gapOk) LevelCue.Steep.also { steepArmed = false } else null
            levelArmed && tilt < LEVEL_DEG -> if (gapOk) LevelCue.Level.also { levelArmed = false } else null
            prevBand != null && nextBand != prevBand && nextBand <= DETENT_MAX_DEG && gapOk -> LevelCue.Detent
            else -> null
        }
        if (cue != null) lastCueMs = nowMs
        return cue
    }

    /** The whole-degree band the tilt is in (1 means 0–1°], moving to a neighbour only once clearly past its edge. */
    private fun nextBand(current: Int?, tilt: Double): Int {
        val raw = ceil(tilt).toInt().coerceAtLeast(1)
        if (current == null || raw == current) return raw
        return if (raw < current) {
            if (tilt < current - 1 - DETENT_HYSTERESIS_DEG) raw else current
        } else {
            if (tilt > current + DETENT_HYSTERESIS_DEG) raw else current
        }
    }

    companion object {
        /** The same threshold the Level screen uses to say "Level". */
        const val LEVEL_DEG = 0.5
        const val LEVEL_REARM_DEG = 1.0
        const val DETENT_MAX_DEG = 4
        const val DETENT_HYSTERESIS_DEG = 0.1
        const val STEEP_REARM_DEG = 2.0
        const val MIN_GAP_MS = 80L
    }
}
