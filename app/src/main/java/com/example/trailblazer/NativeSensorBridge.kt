package com.example.trailblazer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface

/**
 * Exposes real hardware sensors (barometer, magnetometer, light), safe haptics,
 * and edge-to-edge safe area insets to the WebView.
 */
class NativeSensorBridge(private val context: Context) : SensorEventListener {

    private val sensorManager = try {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    } catch (_: Exception) {
        null
    }

    private val pressureSensor: Sensor? = try {
        sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    } catch (_: Exception) {
        null
    }

    private val magneticSensor: Sensor? = try {
        sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    } catch (_: Exception) {
        null
    }

    private val lightSensor: Sensor? = try {
        sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    } catch (_: Exception) {
        null
    }

    @Volatile private var pressureHpa = Double.NaN
    @Volatile private var magneticFluxUt = Double.NaN
    @Volatile private var lightLux = Double.NaN

    @Volatile private var safeAreaTop = 0f
    @Volatile private var safeAreaBottom = 0f
    @Volatile private var safeAreaLeft = 0f
    @Volatile private var safeAreaRight = 0f

    fun start() {
        try {
            listOfNotNull(pressureSensor, magneticSensor, lightSensor).forEach {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (_: Exception) {
            // Guard against driver failures
        }
    }

    fun stop() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
            // Guard against driver failures
        }
        pressureHpa = Double.NaN
        magneticFluxUt = Double.NaN
        lightLux = Double.NaN
    }

    fun updateSafeAreaInsets(top: Float, bottom: Float, left: Float, right: Float) {
        safeAreaTop = if (top.isFinite() && top >= 0f) top else 0f
        safeAreaBottom = if (bottom.isFinite() && bottom >= 0f) bottom else 0f
        safeAreaLeft = if (left.isFinite() && left >= 0f) left else 0f
        safeAreaRight = if (right.isFinite() && right >= 0f) right else 0f
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.isEmpty()) return
        try {
            when (event.sensor.type) {
                Sensor.TYPE_PRESSURE -> {
                    val v = event.values[0].toDouble()
                    if (v.isFinite() && v > 0.0) pressureHpa = v
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    if (event.values.size >= 3) {
                        val x = event.values[0].toDouble()
                        val y = event.values[1].toDouble()
                        val z = event.values[2].toDouble()
                        if (x.isFinite() && y.isFinite() && z.isFinite()) {
                            magneticFluxUt = Math.sqrt(x * x + y * y + z * z)
                        }
                    }
                }
                Sensor.TYPE_LIGHT -> {
                    val v = event.values[0].toDouble()
                    if (v.isFinite() && v >= 0.0) lightLux = v
                }
            }
        } catch (_: Exception) {
            // Guard against concurrent hardware event glitches
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @JavascriptInterface
    fun getPressureHpa(): Double = pressureHpa

    @JavascriptInterface
    fun getMagneticFluxUt(): Double = magneticFluxUt

    @JavascriptInterface
    fun getLightLux(): Double = lightLux

    @JavascriptInterface
    fun getSafeAreaTop(): Float = safeAreaTop

    @JavascriptInterface
    fun getSafeAreaBottom(): Float = safeAreaBottom

    @JavascriptInterface
    fun getSafeAreaLeft(): Float = safeAreaLeft

    @JavascriptInterface
    fun getSafeAreaRight(): Float = safeAreaRight

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Exception) {
        null
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
        if (durationMs <= 0L) return
        val safeDuration = durationMs.coerceAtMost(5000L)
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(safeDuration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(safeDuration)
                }
            }
        } catch (_: Exception) {
            // Guard against security or driver exceptions
        }
    }
}
