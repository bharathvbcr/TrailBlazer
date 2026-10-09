package com.example.trailblazer.sensors

import com.trailblazer.core.sensors.Accuracy
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import com.trailblazer.core.sensors.shareReading

import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.Surface
import com.trailblazer.core.math.CircularLowPass
import com.trailblazer.core.math.RAD
import com.trailblazer.core.math.mod360
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/** Where the heading comes from, best first. [GameRotation] is not referenced to north: it only shows relative turns. */
enum class HeadingSource { RotationVector, AccelMag, GameRotation }

/** How the phone is held. [Auto] switches from flat to upright when the phone is tilted more than 60°. */
enum class Hold { Flat, Upright, Auto }

data class Orientation(
    /** Degrees clockwise from magnetic north (or from an arbitrary start for [HeadingSource.GameRotation]). */
    val azimuthDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
    val source: HeadingSource,
    /** Android's estimated heading accuracy (rotation vector values[4]) in degrees, when reported. */
    val headingAccuracyDeg: Double?,
    val upright: Boolean,
)

/**
 * Compass orientation with the fallback chain ROTATION_VECTOR → accelerometer+magnetometer →
 * GAME_ROTATION_VECTOR. The accelerometer+magnetometer pair comes before the game vector because it is
 * still north-referenced; the game vector is last and labelled relative.
 */
class OrientationRepository(
    private val source: SensorSource,
    private val clock: Clock,
    private val displayRotation: () -> Int,
) {
    val headingSource: HeadingSource? = when {
        source.has(Sensor.TYPE_ROTATION_VECTOR) -> HeadingSource.RotationVector
        source.has(Sensor.TYPE_ACCELEROMETER) && source.has(Sensor.TYPE_MAGNETIC_FIELD) -> HeadingSource.AccelMag
        source.has(Sensor.TYPE_GAME_ROTATION_VECTOR) -> HeadingSource.GameRotation
        else -> null
    }

    fun orientation(hold: Hold): Flow<Reading<Orientation>> {
        val src = headingSource ?: return flowOf(Reading.Unavailable(UnavailableReason.NoHardware))
        val period = SensorManager.SENSOR_DELAY_UI
        return flow {
            emit(Reading.Acquiring)
            val filter = CircularLowPass(0.25)
            val r = FloatArray(9)
            val matrix = FloatArray(9)
            val o = FloatArray(3)
            val v4 = FloatArray(4)
            var currentAutoUpright = false
            var lastUpright: Boolean? = null
            var lastRotation: Int? = null

            suspend fun processMatrix(accuracy: Int, accuracyRad: Double?) {
                val dispRot = displayRotation()

                // Hysteresis for Auto hold mode:
                // cosTilt = abs(matrix[8]). Flat is cosTilt=1.0, Upright (vertical) is cosTilt=0.0.
                // Switch to Upright when tilted > 65° (|cosTilt| < 0.4226f).
                // Switch back to Flat when tilted < 55° (|cosTilt| > 0.5736f).
                val upright = when (hold) {
                    Hold.Flat -> false
                    Hold.Upright -> true
                    Hold.Auto -> {
                        val cosTilt = abs(matrix[8])
                        if (!currentAutoUpright && cosTilt < COS_65_DEG) {
                            currentAutoUpright = true
                        } else if (currentAutoUpright && cosTilt > COS_55_DEG) {
                            currentAutoUpright = false
                        }
                        currentAutoUpright
                    }
                }

                // Reset filter when posture or display rotation changes so different frames do not bleed together
                if (lastUpright != null && (lastUpright != upright || lastRotation != dispRot)) {
                    filter.reset()
                }
                lastUpright = upright
                lastRotation = dispRot

                if (!remap(matrix, upright, dispRot, r)) return
                SensorManager.getOrientation(r, o)
                // A degenerate rotation matrix gives NaN angles; skip that sample rather than emit it.
                if (!o[0].isFinite() || !o[1].isFinite() || !o[2].isFinite()) return
                val az = filter.update(mod360(o[0] * RAD)) ?: return

                val pitchDeg = o[1] * RAD
                val rollDeg = o[2] * RAD
                val tiltDeg = kotlin.math.hypot(pitchDeg, rollDeg)
                val baseAcc = accuracyRad ?: when (accuracyOf(accuracy)) {
                    Accuracy.High -> 3.5
                    Accuracy.Medium -> 8.0
                    Accuracy.Low -> 20.0
                    Accuracy.Unreliable -> 45.0
                    Accuracy.Unknown -> if (src == HeadingSource.RotationVector) 5.0 else 12.0
                }
                // Tilt penalty: vertical magnetic dip leaks into horizontal heading when phone is tilted
                val tiltPenalty = if (!upright && tiltDeg > 2.0) (tiltDeg - 2.0) * 0.25 else 0.0
                val effectiveAccuracy = (baseAcc + tiltPenalty).coerceIn(1.0, 60.0)

                emit(
                    Reading.Value(
                        Orientation(az, pitchDeg, rollDeg, src, effectiveAccuracy, upright),
                        accuracyOf(accuracy),
                        clock.nowMs(),
                    ),
                )
            }

            when (src) {
                HeadingSource.RotationVector, HeadingSource.GameRotation -> {
                    val type = if (src == HeadingSource.RotationVector) Sensor.TYPE_ROTATION_VECTOR else Sensor.TYPE_GAME_ROTATION_VECTOR
                    source.samples(type, period).collect { s ->
                        if (s.values.size < 3 || s.values.any { !it.isFinite() }) return@collect
                        if (s.values.take(minOf(s.values.size, 4)).all { it == 0f }) return@collect
                        val v = if (s.values.size >= 4) {
                            System.arraycopy(s.values, 0, v4, 0, 4)
                            v4
                        } else s.values
                        SensorManager.getRotationMatrixFromVector(matrix, v)
                        val acc = s.values.getOrNull(4)?.takeIf { it >= 0f && src == HeadingSource.RotationVector }?.toDouble()?.times(RAD)
                        processMatrix(s.accuracy, acc)
                    }
                }
                HeadingSource.AccelMag -> {
                    combine(
                        source.samples(Sensor.TYPE_ACCELEROMETER, period),
                        source.samples(Sensor.TYPE_MAGNETIC_FIELD, period),
                    ) { a, g ->
                        if (a.values.size < 3 || g.values.size < 3 || a.values.any { !it.isFinite() } || g.values.any { !it.isFinite() }) null
                        else if (SensorManager.getRotationMatrix(matrix, null, a.values, g.values)) g.accuracy
                        else null
                    }.collect { acc ->
                        if (acc != null) {
                            processMatrix(acc, null)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val COS_65_DEG = 0.42261826f
        private const val COS_55_DEG = 0.57357644f

        /** Remaps a device rotation matrix for the display rotation and for flat vs upright holding. */
        fun remap(inR: FloatArray, upright: Boolean, rotation: Int, outR: FloatArray): Boolean {
            val x: Int
            val y: Int
            if (!upright) {
                when (rotation) {
                    Surface.ROTATION_90 -> { x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X }
                    Surface.ROTATION_180 -> { x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y }
                    Surface.ROTATION_270 -> { x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X }
                    else -> { x = SensorManager.AXIS_X; y = SensorManager.AXIS_Y }
                }
            } else {
                when (rotation) {
                    Surface.ROTATION_90 -> { x = SensorManager.AXIS_Z; y = SensorManager.AXIS_MINUS_X }
                    Surface.ROTATION_180 -> { x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Z }
                    Surface.ROTATION_270 -> { x = SensorManager.AXIS_MINUS_Z; y = SensorManager.AXIS_X }
                    else -> { x = SensorManager.AXIS_X; y = SensorManager.AXIS_Z }
                }
            }
            return SensorManager.remapCoordinateSystem(inR, x, y, outR)
        }
    }
}
