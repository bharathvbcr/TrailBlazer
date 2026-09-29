package com.example.trailblazer.sensors

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
            val upstream: Flow<Pair<FloatArray, Pair<Int, Double?>>?> = when (src) {
                HeadingSource.RotationVector, HeadingSource.GameRotation -> {
                    val type = if (src == HeadingSource.RotationVector) Sensor.TYPE_ROTATION_VECTOR else Sensor.TYPE_GAME_ROTATION_VECTOR
                    source.samples(type, period).map { s ->
                        val v = if (s.values.size > 4) s.values.copyOf(4) else s.values
                        val m = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(m, v)
                        val acc = s.values.getOrNull(4)?.takeIf { it >= 0f && src == HeadingSource.RotationVector }?.toDouble()?.times(RAD)
                        m to (s.accuracy to acc)
                    }
                }
                HeadingSource.AccelMag -> combine(
                    source.samples(Sensor.TYPE_ACCELEROMETER, period),
                    source.samples(Sensor.TYPE_MAGNETIC_FIELD, period),
                ) { a, g ->
                    val m = FloatArray(9)
                    if (SensorManager.getRotationMatrix(m, null, a.values, g.values)) m to (g.accuracy to null) else null
                }
            }
            upstream.collect { item ->
                val (matrix, acc) = item ?: return@collect
                val upright = when (hold) {
                    Hold.Flat -> false
                    Hold.Upright -> true
                    Hold.Auto -> abs(matrix[8]) < 0.5f
                }
                remap(matrix, upright, displayRotation(), r)
                val o = FloatArray(3)
                SensorManager.getOrientation(r, o)
                val az = mod360(o[0] * RAD)
                emit(
                    Reading.Value(
                        Orientation(filter.update(az), o[1] * RAD, o[2] * RAD, src, acc.second, upright),
                        accuracyOf(acc.first),
                        clock.nowMs(),
                    ),
                )
            }
        }
    }

    companion object {
        /** Remaps a device rotation matrix for the display rotation and for flat vs upright holding. */
        fun remap(inR: FloatArray, upright: Boolean, rotation: Int, outR: FloatArray) {
            val (x, y) = if (!upright) {
                when (rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
            } else {
                when (rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
                }
            }
            SensorManager.remapCoordinateSystem(inR, x, y, outR)
        }
    }
}
