package com.example.trailblazer.sensors

import com.trailblazer.core.sensors.Accuracy
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.sensors.UnavailableReason
import com.trailblazer.core.sensors.shareReading

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Sound level relative to digital full scale. Phones are not calibrated, so this is dBFS, not dB SPL. */
data class SoundLevel(val rmsDbfs: Double, val peakDbfs: Double)

/**
 * Microphone level meter. Audio samples are reduced to two numbers in memory and discarded; nothing is
 * stored or sent. Emits at most ~15 times per second, so the UI does not recompose at 60 fps.
 */
class AcousticRepository(private val clock: Clock, private val permitted: () -> Boolean) {
    @SuppressLint("MissingPermission") // Checked by permitted() immediately before AudioRecord is created.
    fun level(): Flow<Reading<SoundLevel>> {
        if (!permitted()) return flowOf(Reading.Unavailable(UnavailableReason.PermissionDenied))
        return flow {
            emit(Reading.Acquiring)
            val rate = 44_100
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                emit(Reading.Unavailable(UnavailableReason.NoHardware))
                return@flow
            }
            val chunk = rate / 15
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, chunk * 2))
            } catch (_: SecurityException) {
                emit(Reading.Unavailable(UnavailableReason.PermissionDenied))
                return@flow
            } catch (_: IllegalArgumentException) {
                emit(Reading.Unavailable(UnavailableReason.NoHardware))
                return@flow
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                emit(Reading.Unavailable(UnavailableReason.NoHardware))
                return@flow
            }
            val buf = ShortArray(chunk)
            try {
                record.startRecording()
                while (currentCoroutineContext().isActive) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) {
                        emit(Reading.Unavailable(UnavailableReason.Disabled))
                        break
                    }
                    var sum = 0.0
                    var peak = 0
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        sum += v.toDouble() * v
                        val a = if (v < 0) -v else v
                        if (a > peak) peak = a
                    }
                    val rms = sqrt(sum / n) / 32768.0
                    emit(Reading.Value(SoundLevel(dbfs(rms), dbfs(peak / 32768.0)), Accuracy.Unknown, clock.nowMs()))
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun dbfs(amplitude: Double): Double = if (amplitude <= 1e-6) -120.0 else 20 * log10(amplitude)
}
