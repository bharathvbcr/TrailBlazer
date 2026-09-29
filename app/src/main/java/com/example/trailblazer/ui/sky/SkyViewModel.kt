package com.example.trailblazer.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.Settings
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.ui.LocalDays
import com.example.trailblazer.weather.ForecastResult
import com.trailblazer.core.astro.DaylightStatus
import com.trailblazer.core.astro.Horizontal
import com.trailblazer.core.astro.LunarDay
import com.trailblazer.core.astro.LunarEvents
import com.trailblazer.core.astro.LunarPhase
import com.trailblazer.core.astro.LunarPhaseInfo
import com.trailblazer.core.astro.LunarPosition
import com.trailblazer.core.astro.SolarDay
import com.trailblazer.core.astro.SolarEvents
import com.trailblazer.core.astro.SolarPosition
import com.trailblazer.core.atmo.Isa
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.weather.PressureSample
import com.trailblazer.core.weather.PressureTrend
import com.trailblazer.core.weather.StormAlert
import com.trailblazer.core.weather.TrendResult
import com.trailblazer.core.weather.Zambretti
import com.trailblazer.core.weather.ZambrettiForecast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the Sky screen computes for: the live fix, or a place the user entered. */
data class SkyPlace(val position: LatLon, val label: String?, val manual: Boolean)

data class SkyState(
    val place: SkyPlace,
    val epochDay: Long,
    val isToday: Boolean,
    val nowMs: Long,
    val sun: SolarDay,
    val moon: LunarDay,
    val phase: LunarPhaseInfo,
    val nextNewMs: Long?,
    val nextFullMs: Long?,
    val sunNow: Horizontal,
    val moonNow: Horizontal,
    val status: DaylightStatus,
)

/** Sea-level pressure (QNH) now, and how it was obtained. */
data class Qnh(val hpa: Double, val approximate: Boolean)

data class WeatherState(
    val stationHpa: Double?,
    val trend: TrendResult,
    val history: List<PressureSample>,
    val qnh: Qnh?,
    val zambretti: ZambrettiForecast?,
    val stormAlert: Boolean,
)

class SkyViewModel(private val c: AppContainer) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)
    val settings: StateFlow<Settings?> = c.prefs.settings.stateIn(viewModelScope, started, null)

    val dayOffset = MutableStateFlow(0)
    val manualPlace = MutableStateFlow<SkyPlace?>(null)

    val fix: StateFlow<Reading<Fix>> = c.location.fix

    private val minuteTicker = flow {
        while (true) {
            emit(c.clock.nowMs())
            delay(60_000L - c.clock.nowMs() % 60_000L)
        }
    }

    private val livePlace = fix.map { r -> (r as? Reading.Value)?.value?.position?.let { SkyPlace(roundTo(it), null, false) } }
        .distinctUntilChanged()

    /** Positions rounded to ~100 m so a jittering fix does not recompute the whole sky every second. */
    private fun roundTo(p: LatLon) = LatLon(Math.round(p.lat * 1000) / 1000.0, Math.round(p.lon * 1000) / 1000.0)

    val place: StateFlow<SkyPlace?> = combine(manualPlace, livePlace) { m, l -> m ?: l }.stateIn(viewModelScope, started, null)

    val sky: StateFlow<SkyState?> = combine(place, dayOffset, minuteTicker) { p, off, now -> Triple(p, off, now) }
        .map { (p, off, now) -> p?.let { compute(it, off, now) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, started, null)

    private fun compute(p: SkyPlace, offset: Int, now: Long): SkyState {
        val today = LocalDays.today(now)
        val day = today + offset
        val (start, end) = LocalDays.window(day)
        val sun = SolarEvents.day(p.position.lat, p.position.lon, start, end)
        val moon = LunarEvents.day(p.position.lat, p.position.lon, start, end)
        val ref = if (offset == 0) now else start + (end - start) / 2
        return SkyState(
            place = p,
            epochDay = day,
            isToday = offset == 0,
            nowMs = now,
            sun = sun,
            moon = moon,
            phase = LunarPhase.at(ref),
            nextNewMs = LunarPhase.next(ref, 0.0),
            nextFullMs = LunarPhase.next(ref, 180.0),
            sunNow = SolarPosition.horizontal(now, p.position.lat, p.position.lon),
            moonNow = LunarPosition.horizontal(now, p.position.lat, p.position.lon),
            status = SolarEvents.status(sun, now),
        )
    }

    private var stormActive = false

    val weather: StateFlow<WeatherState?> = combine(
        c.pressureHistory.recent(24 * 3_600_000L),
        c.barometer.pressureHpa,
        fix,
        minuteTicker,
    ) { history, p, f, now ->
        val station = (p as? Reading.Value)?.value
        val trend = PressureTrend.compute(history, now)
        val fixV = (f as? Reading.Value)?.takeIf { !it.stale && now - it.value.timeMs < 120_000 }?.value
        val qnh = if (station != null && fixV?.altitudeM != null) {
            Isa.qnhHpa(station, fixV.altitudeM)?.let { Qnh(it, fixV.altitudeDatum != AltitudeDatum.SeaLevel) }
        } else null
        val z = if (qnh != null && trend is TrendResult.Trend) Zambretti.forecast(qnh.hpa, trend.hpaPer3h) else null
        stormActive = StormAlert.next(stormActive, trend)
        WeatherState(station, trend, history, qnh, z, stormActive)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, started, null)

    val environment = c.environment

    val forecast = MutableStateFlow<ForecastResult?>(null)
    val forecastLoading = MutableStateFlow(false)

    fun loadForecast(force: Boolean) {
        val p = place.value?.position ?: return
        val consent = settings.value?.forecastConsent == true
        viewModelScope.launch {
            forecastLoading.value = true
            forecast.value = c.weather.forecast(p, consent, force)
            forecastLoading.value = false
        }
    }

    fun setConsent(on: Boolean) = viewModelScope.launch {
        c.prefs.update { it.copy(forecastConsent = on) }
        if (!on) {
            c.weather.clearCache()
            forecast.value = null
        }
    }

    fun step(days: Int) {
        dayOffset.value = (dayOffset.value + days).coerceIn(-366, 366)
    }
}
