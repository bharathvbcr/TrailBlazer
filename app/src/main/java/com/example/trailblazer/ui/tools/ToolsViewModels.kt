package com.example.trailblazer.ui.tools

import androidx.lifecycle.ViewModel
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.AltimeterCalibration
import com.example.trailblazer.ui.persist
import kotlinx.coroutines.Job

/** The level screen's stored zero offset. Writes outlive the screen (see [persist]). */
class LevelViewModel(private val c: AppContainer) : ViewModel() {
    fun setZero(pitchDeg: Double, rollDeg: Double): Job =
        c.persist { it.copy(levelPitchOffsetDeg = pitchDeg, levelRollOffsetDeg = rollDeg) }

    fun resetZero(): Job = setZero(0.0, 0.0)

    fun setHaptics(on: Boolean): Job = c.persist { it.copy(levelHaptics = on) }
}

/** The altimeter's stored sea-level pressure (QNH) calibration. Writes outlive the screen (see [persist]). */
class AltimeterViewModel(private val c: AppContainer) : ViewModel() {
    /** Calibrates to [qnhHpa], stamped with the time now. */
    fun calibrate(qnhHpa: Double): Job {
        val at = c.clock.nowMs()
        return c.persist { it.copy(calibration = AltimeterCalibration(qnhHpa, at)) }
    }

    fun clearCalibration(): Job = c.persist { it.copy(calibration = null) }
}
