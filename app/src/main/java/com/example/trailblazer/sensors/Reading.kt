package com.example.trailblazer.sensors

import com.trailblazer.core.sensors.Accuracy

fun accuracyOf(status: Int): Accuracy = when (status) {
    android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE -> Accuracy.Unreliable
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> Accuracy.Low
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Accuracy.Medium
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> Accuracy.High
    else -> Accuracy.Unknown
}
