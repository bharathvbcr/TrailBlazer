package com.example.trailblazer.testing

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows.shadowOf

/**
 * Waits until the app has a listener registered for [sensor] on [manager]'s Robolectric shadow, so the events a test sends
 * next are not dropped. Robolectric delivers an event only to listeners that exist at that instant.
 *
 * `waitForIdle()` is not this wait: it covers Compose and the main looper, not the DataStore read on Dispatchers.IO that a
 * screen may be gated on before it subscribes to the sensor. It advances both clocks while it polls: the compose clock
 * ([ComposeTestRule.waitUntil]), which drives the frame-based dispatcher the screen's collectors resume on, and the paused
 * main looper, which runs work that finished on another thread and posts back. Neither alone is enough. Throws on timeout,
 * so the test fails here and not later, with an empty recording.
 */
fun ComposeTestRule.awaitSensorListener(manager: SensorManager, sensor: Sensor, timeoutMs: Long = 20_000) {
    val shadow = shadowOf(manager)
    val looper = shadowOf(Looper.getMainLooper())
    waitUntil(timeoutMs) {
        looper.idle()
        shadow.listeners.any { shadow.hasListener(it, sensor) }
    }
}
