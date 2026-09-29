package com.example.trailblazer.weather

import com.example.trailblazer.data.PressureHistory
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Clock
import com.example.trailblazer.sensors.Reading
import kotlinx.coroutines.flow.StateFlow

/**
 * Stores a barometer sample every [PressureHistory.INTERVAL_MS] while the app is in the foreground.
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
        pressure.collect { r ->
            if (r !is Reading.Value || r.stale) return@collect
            val now = clock.nowMs()
            val last = lastMs
            if (last != null && now - last < PressureHistory.INTERVAL_MS && now >= last) return@collect
            val f = (fix.value as? Reading.Value)?.takeIf { !it.stale && now - it.value.timeMs < FIX_MAX_AGE_MS }?.value
            history.record(r.value, f?.altitudeM)
            lastMs = now
        }
    }

    companion object {
        const val FIX_MAX_AGE_MS = 120_000L
    }
}
