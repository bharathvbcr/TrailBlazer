package com.example.trailblazer.sensors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Why a reading has no value. The UI shows a different message and action for each. */
enum class UnavailableReason { NoHardware, PermissionDenied, Disabled }

/** Sensor accuracy as reported by Android (SensorManager.SENSOR_STATUS_*), or Unknown when not reported. */
enum class Accuracy { Unreliable, Low, Medium, High, Unknown }

/**
 * The only way a sensor value reaches the UI. There is no numeric fallback: a missing sensor is
 * [Unavailable], a sensor that has not produced data yet is [Acquiring], and a value kept from before
 * the screen paused is marked [Value.stale] until a fresh sample arrives.
 */
sealed interface Reading<out T> {
    data class Unavailable(val reason: UnavailableReason) : Reading<Nothing>
    data object Acquiring : Reading<Nothing>
    data class Value<out T>(
        val value: T,
        val accuracy: Accuracy = Accuracy.Unknown,
        val timestampMs: Long,
        val stale: Boolean = false,
    ) : Reading<T>
}

inline fun <T, R> Reading<T>.map(transform: (T) -> R): Reading<R> = when (this) {
    is Reading.Value -> Reading.Value(transform(value), accuracy, timestampMs, stale)
    is Reading.Unavailable -> this
    Reading.Acquiring -> Reading.Acquiring
}

fun <T> Reading<T>.valueOrNull(): T? = (this as? Reading.Value)?.value

/**
 * Hot [StateFlow] that stops the sensor 5 s after the last subscriber leaves. On restart the last value is
 * replayed marked stale and stays on screen until a fresh value (or an Unavailable) replaces it — the
 * upstream's initial Acquiring is not allowed to blank it.
 */
fun <T> Flow<Reading<T>>.shareReading(scope: CoroutineScope): StateFlow<Reading<T>> {
    val upstream = this
    var last: Reading<T> = Reading.Acquiring
    return flow {
        val cached = last
        if (cached is Reading.Value) emit(cached.copy(stale = true))
        emitAll(upstream.filter { !(cached is Reading.Value && it == Reading.Acquiring) }.onEach { last = it })
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), Reading.Acquiring)
}

fun accuracyOf(status: Int): Accuracy = when (status) {
    android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE -> Accuracy.Unreliable
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> Accuracy.Low
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Accuracy.Medium
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> Accuracy.High
    else -> Accuracy.Unknown
}
