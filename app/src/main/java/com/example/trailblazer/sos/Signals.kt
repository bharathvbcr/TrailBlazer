package com.example.trailblazer.sos

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.trailblazer.core.sos.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/**
 * Flashes the camera torch along a timeline. setTorchMode needs no CAMERA permission (API 23+).
 * The torch is always switched off when [play] ends or is cancelled.
 */
class TorchController(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    /** Back camera with a flash unit, or null when the phone has no torch. */
    val cameraId: String? = try {
        manager?.cameraIdList?.firstOrNull { id ->
            val ch = manager.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (_: CameraAccessException) {
        null
    }

    private fun set(on: Boolean): Boolean = try {
        val id = cameraId ?: return false
        manager?.setTorchMode(id, on)
        true
    } catch (_: CameraAccessException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Plays [timeline] until cancelled (repeating). Returns false if the torch could not be used. */
    suspend fun play(timeline: List<Pulse>, onPulse: (Boolean) -> Unit = {}): Boolean {
        if (cameraId == null || timeline.isEmpty()) return false
        try {
            while (currentCoroutineContext().isActive) {
                for (p in timeline) {
                    if (!set(p.on)) return false
                    onPulse(p.on)
                    delay(p.durationMs)
                }
            }
        } finally {
            set(false)
            onPulse(false)
        }
        return true
    }
}

/**
 * Distress whistle: a ~3.2 kHz tone (where small speakers are loudest and human hearing most sensitive)
 * played as three long blasts and a pause, on the alarm audio stream. Stops when cancelled.
 */
class WhistlePlayer {
    suspend fun play(timeline: List<Pulse>, frequencyHz: Double = 3150.0, onPulse: (Boolean) -> Unit = {}) = withContext(Dispatchers.Default) {
        val rate = 44_100
        val frames = rate / 10
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(frames * 2 * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val tone = ShortArray(frames)
        val silence = ShortArray(frames)
        var phase = 0.0
        val step = 2 * PI * frequencyHz / rate
        try {
            track.play()
            while (isActive) {
                for (p in timeline) {
                    onPulse(p.on)
                    val total = (p.durationMs * rate / 1000).toInt()
                    val ramp = rate / 100 // 10 ms fade in/out avoids clicks at blast edges
                    var pos = 0
                    var remaining = total
                    while (remaining > 0 && isActive) {
                        val n = minOf(frames, remaining)
                        if (p.on) {
                            for (i in 0 until n) {
                                val gain = minOf(1.0, pos.toDouble() / ramp, (total - pos).toDouble() / ramp)
                                tone[i] = (sin(phase) * 0.9 * gain * Short.MAX_VALUE).toInt().toShort()
                                phase += step
                                if (phase > 2 * PI) phase -= 2 * PI
                                pos++
                            }
                            track.write(tone, 0, n)
                        } else {
                            track.write(silence, 0, n)
                        }
                        remaining -= n
                    }
                }
            }
        } finally {
            onPulse(false)
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }
}
