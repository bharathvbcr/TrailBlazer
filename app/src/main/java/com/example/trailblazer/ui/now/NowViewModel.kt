package com.example.trailblazer.ui.now

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.NorthReference
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.Waypoint
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Declination
import com.example.trailblazer.sensors.Heading
import com.example.trailblazer.sensors.Hold
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.sensors.map
import com.trailblazer.core.alerts.SpeedAlert
import com.trailblazer.core.astro.LunarPosition
import com.trailblazer.core.astro.SolarPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Sun and Moon bearings for the dial, from the current position. */
data class SkyMarks(val sunAzDeg: Double, val sunAltDeg: Double, val moonAzDeg: Double, val moonAltDeg: Double)

@OptIn(ExperimentalCoroutinesApi::class)
class NowViewModel(private val c: AppContainer) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)

    val settings: StateFlow<Settings?> = c.prefs.settings.stateIn(viewModelScope, started, null)
    val fix: StateFlow<Reading<Fix>> = c.location.fix
    val gnss = c.gnss.status
    val pressure = c.barometer.pressureHpa
    val magneticField = c.magnetic.fieldMicroTesla
    val headingSource = c.orientation.headingSource

    /** Heading combined with declination from the latest fix and level calibration offsets from settings. */
    val heading: StateFlow<Reading<Heading>> = combine(c.orientation.orientation(Hold.Auto), fix, settings) { o, f, s ->
        val decl = (f as? Reading.Value)?.value?.let { Declination.degrees(it) }
        val pOff = s?.levelPitchOffsetDeg ?: 0.0
        val rOff = s?.levelRollOffsetDeg ?: 0.0
        o.map { Heading(it.azimuthDeg, decl, it.source, it.headingAccuracyDeg, it.upright, it.pitchDeg, it.rollDeg, pOff, rOff) }
    }.stateIn(viewModelScope, started, Reading.Acquiring)

    val waypoints: StateFlow<List<Waypoint>> = c.waypoints.all.stateIn(viewModelScope, started, emptyList())

    val target: StateFlow<Waypoint?> = combine(settings, waypoints) { s, list ->
        s?.targetWaypointId?.let { id -> list.firstOrNull { it.id == id } }
    }.stateIn(viewModelScope, started, null)

    val sky: StateFlow<SkyMarks?> = fix.map { r ->
        val f = (r as? Reading.Value)?.value ?: return@map null
        val now = c.clock.nowMs()
        val s = SolarPosition.horizontal(now, f.position.lat, f.position.lon)
        val m = LunarPosition.horizontal(now, f.position.lat, f.position.lon)
        SkyMarks(s.azimuthDeg, s.apparentAltitudeDeg, m.azimuthDeg, m.altitudeDeg)
    }.stateIn(viewModelScope, started, null)

    /**
     * Steps since counting was turned on, or null when the feature is off. The sensor is only subscribed while
     * enabled; a reboot resets the hardware counter, which is detected (value below baseline) and re-baselined.
     */
    val steps: StateFlow<Reading<Long>?> = settings.map { it?.stepsEnabled == true }.distinctUntilChanged().flatMapLatest { on ->
        if (!on) flowOf(null) else combine(c.steps.stepsSinceBoot, settings) { r, s ->
            if (r !is Reading.Value) return@combine r
            val base = s?.stepsBaseline
            if (base == null || r.value < base) {
                viewModelScope.launch { c.prefs.update { it.copy(stepsBaseline = r.value) } }
                r.copy(value = 0L)
            } else r.copy(value = r.value - base)
        }
    }.stateIn(viewModelScope, started, null)

    /**
     * Emits the speed (m/s) each time the overspeed alert fires. Cold: it listens to location only while collected
     * and only while the alert is on, so a ViewModel left alive in the background never keeps GPS running. The screen
     * collects it while started; the hysteresis state lives in the collection and re-arms on each start.
     */
    val speedAlerts: Flow<Double> = settings
        .map { s -> s?.takeIf { it.speedAlertEnabled }?.speedAlertLimitMps }
        .distinctUntilChanged()
        .flatMapLatest { limit ->
            if (limit == null) emptyFlow() else flow {
                val alert = SpeedAlert(limit)
                fix.collect { f ->
                    val v = f as? Reading.Value ?: return@collect
                    if (!v.stale && alert.update(v.value.speedMps)) emit(v.value.speedMps ?: 0.0)
                }
            }
        }

    fun setCompactPosition(on: Boolean) = viewModelScope.launch { c.prefs.update { it.copy(compactPosition = on) } }

    fun toggleNorth() = viewModelScope.launch {
        c.prefs.update { it.copy(north = if (it.north == NorthReference.True) NorthReference.Magnetic else NorthReference.True) }
    }

    fun setTarget(id: String?) = viewModelScope.launch { c.prefs.update { it.copy(targetWaypointId = id) } }

    fun setLevelZero(pitch: Double, roll: Double) = viewModelScope.launch {
        c.prefs.update { it.copy(levelPitchOffsetDeg = pitch, levelRollOffsetDeg = roll) }
    }

    fun resetLevelZero() = viewModelScope.launch {
        c.prefs.update { it.copy(levelPitchOffsetDeg = 0.0, levelRollOffsetDeg = 0.0) }
    }

    /** Saves the current fix as a waypoint. Returns false when there is no fresh fix. */
    fun markWaypoint(name: String, onDone: (Boolean) -> Unit) {
        val f = fix.value as? Reading.Value
        if (f == null || f.stale) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            c.waypoints.add(name, f.value.position, f.value.altitudeM)
            onDone(true)
        }
    }
}
