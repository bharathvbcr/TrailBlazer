package com.example.trailblazer.weather

import com.example.trailblazer.data.PressureHistory
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.sensors.Reading
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stores a barometer sample every [PressureHistory.INTERVAL_MS] while the app is in the foreground.
 * Rather than holding an active sensor subscription continuously, it samples on a schedule so
 * the barometer hardware can sleep between samples when no UI screen is observing it.
 * The elevation comes from the latest location fix *if one is already live* (it reads the value without
 * subscribing), so sampling never turns on GPS by itself.
 */
class PressureSampler(
    private val pressure: StateFlow<Reading<Double>>,
    private val fix: StateFlow<Reading<Fix>>,
    private val history: PressureHistory,
    private val clock: Clock,
) {
    private var lastMs: Long? = null

    suspend fun run() {
        if (lastMs == null) lastMs = history.lastSampleMs()
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMs()
            val last = lastMs
            if (last != null && now - last < PressureHistory.INTERVAL_MS && now >= last) {
                val waitMs = (PressureHistory.INTERVAL_MS - (now - last)).coerceAtLeast(1_000L)
                delay(waitMs)
                continue
            }
            val r = withTimeoutOrNull(10_000L) {
                pressure.filter { it is Reading.Value && !it.stale }.first()
            }
            if (r is Reading.Value) {
                val sampleNow = clock.nowMs()
                val f = (fix.value as? Reading.Value)?.takeIf { !it.stale && sampleNow - it.value.timeMs < FIX_MAX_AGE_MS }?.value
                history.record(r.value, f?.altitudeM)
                lastMs = sampleNow
            }
            delay(PressureHistory.INTERVAL_MS)
        }
    }

    companion object {
        const val FIX_MAX_AGE_MS = 120_000L
    }
}
