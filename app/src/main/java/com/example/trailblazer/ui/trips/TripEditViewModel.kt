package com.example.trailblazer.ui.trips

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.GeoFormat
import com.example.trailblazer.data.IoResult
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.Waypoint
import com.example.trailblazer.data.newId
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.ui.LocalDays
import com.trailblazer.core.geo.ParsedPlace
import com.trailblazer.core.trip.DaylightPlanner
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import com.trailblazer.core.trip.TripRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TripEditViewModel(private val c: AppContainer, tripId: String?) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)
    val id: String = tripId ?: newId()
    val isNew = tripId == null

    val settings: StateFlow<Settings?> = c.prefs.settings.stateIn(viewModelScope, started, null)
    val waypoints: StateFlow<List<Waypoint>> = c.waypoints.all.stateIn(viewModelScope, started, emptyList())
    val fix: StateFlow<Reading<Fix>> = c.location.fix

    val name = MutableStateFlow("New trip")
    val stops = MutableStateFlow<List<Stop>>(emptyList())
    val dirty = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val loaded = MutableStateFlow(tripId == null)

    val problems: StateFlow<List<TripRules.Problem>> = stops.map { TripRules.check(it) }.stateIn(viewModelScope, started, emptyList())

    /** Sun times at each dated stop, in the phone's time zone. */
    val daylight: StateFlow<List<DaylightPlanner.Row>> = stops
        .map { list -> DaylightPlanner.plan(list) { day -> LocalDays.window(day) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, started, emptyList())

    init {
        if (tripId != null) viewModelScope.launch {
            c.trips.observe(tripId).first()?.let { t ->
                name.value = t.name
                stops.value = t.stops
            }
            loaded.value = true
        }
    }

    private fun edit(block: (List<Stop>) -> List<Stop>) {
        stops.update { normalizeKinds(block(it)) }
        dirty.value = true
    }

    /** Keeps Start first and End last, so reordering never produces an End in the middle. */
    private fun normalizeKinds(list: List<Stop>): List<Stop> = list.mapIndexed { i, s ->
        when {
            list.size >= 2 && i == 0 -> s.copy(kind = StopKind.Start)
            list.size >= 2 && i == list.size - 1 -> s.copy(kind = StopKind.End)
            s.kind == StopKind.Start || s.kind == StopKind.End -> s.copy(kind = StopKind.Visit)
            else -> s
        }
    }

    fun rename(n: String) {
        name.value = n.take(80)
        dirty.value = true
    }

    fun addPlaces(places: List<ParsedPlace>) = edit { cur ->
        cur + places.mapIndexed { i, p -> Stop(newId(), p.label ?: "Stop ${cur.size + i + 1}", StopKind.Visit, p.position) }
    }

    fun addHere(): Boolean {
        val f = (fix.value as? Reading.Value)?.value ?: return false
        addPlaces(listOf(ParsedPlace(f.position, "My location")))
        return true
    }

    fun addWaypoint(w: Waypoint) = addPlaces(listOf(ParsedPlace(w.position, w.name)))

    fun move(index: Int, delta: Int) = edit { cur ->
        val j = index + delta
        if (index !in cur.indices || j !in cur.indices) cur
        else cur.toMutableList().also { val t = it[index]; it[index] = it[j]; it[j] = t }
    }

    fun remove(index: Int) = edit { cur -> cur.filterIndexed { i, _ -> i != index } }

    fun setKind(index: Int, kind: StopKind) = edit { cur -> cur.mapIndexed { i, s -> if (i == index) s.copy(kind = kind) else s } }

    fun setDay(index: Int, day: Long?) = edit { cur -> cur.mapIndexed { i, s -> if (i == index) s.copy(plannedDay = day) else s } }

    fun renameStop(index: Int, n: String) = edit { cur -> cur.mapIndexed { i, s -> if (i == index) s.copy(name = n.take(80)) else s } }

    fun trip(): Trip = Trip(id, name.value.ifBlank { "Trip" }, stops.value)

    fun save(onDone: () -> Unit = {}) = viewModelScope.launch {
        c.trips.save(trip())
        dirty.value = false
        onDone()
    }

    fun export(uri: Uri, format: GeoFormat) = viewModelScope.launch {
        message.value = when (val r = c.importExport.exportTrip(trip(), uri, format)) {
            is IoResult.Ok -> "Exported ${r.value} stops"
            is IoResult.Failed -> r.message
        }
    }
}
