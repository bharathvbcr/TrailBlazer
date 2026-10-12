package com.example.trailblazer.ui.trips

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.GeoFormat
import com.example.trailblazer.data.IoResult
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.newId
import com.example.trailblazer.location.Fix
import com.trailblazer.core.sensors.Reading
import com.trailblazer.core.time.LocalDays
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.ParsedPlace
import com.trailblazer.core.trip.ElevationProfile
import com.trailblazer.core.trip.DaylightPlanner
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import com.trailblazer.core.trip.TripRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/** A removal that can still be undone: the list before it, and the list it produced. */
data class RemovedStop(val stop: Stop, val before: List<Stop>, val after: List<Stop>)

/**
 * [saved] holds the unsaved edits (id, name, stops, dirty), so they come back when the process is killed and the
 * screen is restored. It is the screen's SavedStateHandle; the default is a private one for callers that have none.
 * The undo offer ([lastRemoved]) and transient [message]s are deliberately not kept.
 */
class TripEditViewModel(
    private val c: AppContainer,
    tripId: String?,
    seed: List<ParsedPlace> = emptyList(),
    private val saved: SavedStateHandle = SavedStateHandle(),
    /** How a route's elevation profile is built; tests replace it with a slow or observable one. Last, for a trailing lambda. */
    private val buildProfile: suspend (List<LatLon>) -> ElevationProfile? = { c.demTiles.profile(it) },
) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)
    val id: String = tripId ?: saved.get<String>(KEY_ID)?.takeIf { it.isNotEmpty() } ?: newId()
    val isNew = tripId == null

    val settings: StateFlow<Settings?> = c.prefs.settings.stateIn(viewModelScope, started, null)
    val fix: StateFlow<Reading<Fix>> = c.location.fix

    private val _name = MutableStateFlow(saved.get<String>(KEY_NAME)?.take(SavedStops.NAME_MAX) ?: "New trip")
    private val _stops = MutableStateFlow(SavedStops.decode(saved.get<Any>(KEY_STOPS)))
    private val _dirty = MutableStateFlow(saved.get<Boolean>(KEY_DIRTY) == true)
    val name: StateFlow<String> = _name.asStateFlow()
    val stops: StateFlow<List<Stop>> = _stops.asStateFlow()
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()
    val message = MutableStateFlow<String?>(null)
    val loaded = MutableStateFlow(tripId == null)

    /** The last removed stop, offered for Undo until any other edit. */
    val lastRemoved = MutableStateFlow<RemovedStop?>(null)

    val problems: StateFlow<List<TripRules.Problem>> = stops.map { TripRules.check(it) }.stateIn(viewModelScope, started, emptyList())

    /** Sun times at each dated stop, in the phone's time zone. */
    val daylight: StateFlow<List<DaylightPlanner.Row>> = stops
        .map { list -> DaylightPlanner.plan(list) { day -> LocalDays.window(day) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, started, emptyList())

    private fun setName(n: String) {
        _name.value = n
        saved[KEY_NAME] = n
    }

    private fun setStops(list: List<Stop>) {
        _stops.value = list
        saved[KEY_STOPS] = SavedStops.encode(list)
    }

    private fun setDirty(on: Boolean) {
        _dirty.value = on
        saved[KEY_DIRTY] = on
    }

    /**
     * Elevation along the route from the offline SRTM tiles. Recomputed only when a stop moves (not when one is renamed
     * or dated) or tiles are imported, and a newer edit cancels the profile still being built.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val elevation: StateFlow<TripElevation> = combine(
        stops.map { list -> list.map { it.position } }.distinctUntilChanged(),
        flow { emitAll(c.demTiles.revision) },
    ) { positions, _ -> positions }
        .transformLatest { positions ->
            if (positions.size < 2) {
                emit(TripElevation.NotEnoughStops)
            } else {
                emit(TripElevation.Loading)
                val profile = buildProfile(positions)
                emit(
                    if (profile == null) TripElevation.NotEnoughStops
                    else TripElevation.Ready(profile, profile.stats(), profile.extrema(), profile.steepSections(STEEP_WARNING_PERCENT)),
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, started, TripElevation.NotEnoughStops)

    /** Imports the picked `.hgt` files and says what happened. */
    fun importDemTiles(uris: List<Uri>) = viewModelScope.launch {
        message.value = c.demTiles.importAll(uris)
    }

    init {
        saved[KEY_ID] = id
        if (tripId != null) viewModelScope.launch {
            val t = c.trips.observe(tripId).first()
            if (!dirty.value) {
                if (t == null) message.value = "This trip is no longer on this phone."
                else {
                    setName(t.name)
                    setStops(t.stops)
                }
            }
            loaded.value = true
            // After the stops are in: seeding first would mark the trip dirty and the load above would skip them,
            // leaving a trip of only the shared place that Save would then write over the real one.
            addSeed(seed)
        } else addSeed(seed)
    }

    /**
     * A shared place joins an existing route before its destination (as a place to visit); a new trip just grows.
     * Done once per editor: a restored editor already holds the place, so adding it again would duplicate it.
     */
    private fun addSeed(seed: List<ParsedPlace>) {
        if (saved.get<Boolean>(KEY_SEEDED) == true) return
        saved[KEY_SEEDED] = true
        if (seed.isEmpty()) return
        if (stops.value.size >= 2) {
            val added = insert(seed, beforeDestination = true)
            // Only say what happened: a full trip gets the "not added" message from the insert, not "Added".
            if (added == 1) message.value = "Added “${stops.value[stops.value.size - 2].name}” before the destination. Move it with the arrows."
            else if (added > 1 && added == seed.size) message.value = "Added $added places before the destination."
        } else addPlaces(seed)
    }

    private fun edit(block: (List<Stop>) -> List<Stop>) {
        lastRemoved.value = null
        val current = _stops.value
        val next = normalizeKinds(block(current))
        if (next != current) {
            setStops(next)
            setDirty(true)
        }
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
        lastRemoved.value = null
        setName(n.take(SavedStops.NAME_MAX))
        setDirty(true)
    }

    fun addPlaces(places: List<ParsedPlace>) {
        insert(places, beforeDestination = false)
    }

    /**
     * Adds [places] at the end, or just before the destination. Returns how many went in; the rest did not fit on
     * the trip and are reported in [message].
     */
    private fun insert(places: List<ParsedPlace>, beforeDestination: Boolean): Int {
        if (places.isEmpty()) return 0
        var dropped = 0
        edit { cur ->
            val room = (TripRules.MAX_STOPS - cur.size).coerceAtLeast(0)
            val accepted = places.take(room)
            dropped = places.size - accepted.size
            val made = accepted.mapIndexed { i, p -> Stop(newId(), p.label ?: "Stop ${cur.size + i + 1}", StopKind.Visit, p.position) }
            if (beforeDestination && cur.size >= 2) cur.dropLast(1) + made + cur.last() else cur + made
        }
        if (dropped > 0) message.value = "Only ${TripRules.MAX_STOPS} stops fit on a trip. $dropped were not added."
        return places.size - dropped
    }

    fun addHere(): Boolean {
        val f = (fix.value as? Reading.Value)?.value ?: return false
        addPlaces(listOf(ParsedPlace(f.position, "My location")))
        return true
    }

    fun move(index: Int, delta: Int) = edit { cur ->
        val j = index + delta
        if (index !in cur.indices || j !in cur.indices) cur
        else cur.toMutableList().also { val t = it[index]; it[index] = it[j]; it[j] = t }
    }

    fun remove(index: Int) {
        val before = stops.value
        val stop = before.getOrNull(index) ?: return
        edit { cur -> cur.filterIndexed { i, _ -> i != index } }
        lastRemoved.value = RemovedStop(stop, before, stops.value)
    }

    /**
     * Puts the last removed stop back, with every stop's kind as it was (removing the start re-labels its neighbour).
     * Does nothing once the trip has changed since, so a stale undo can never overwrite newer edits.
     */
    fun undoRemove() {
        val r = lastRemoved.value ?: return
        lastRemoved.value = null
        if (stops.value != r.after) return
        setStops(r.before)
        setDirty(true)
    }

    fun setKind(index: Int, kind: StopKind) = edit { cur -> cur.mapIndexed { i, s -> if (i == index) s.copy(kind = kind) else s } }

    fun setDay(index: Int, day: Long?) = edit { cur -> cur.mapIndexed { i, s -> if (i == index) s.copy(plannedDay = day) else s } }

    fun renameStop(index: Int, n: String) = edit { cur -> cur.mapIndexed { i, s -> if (i == index) s.copy(name = n.take(80)) else s } }

    fun trip(): Trip = Trip(id, name.value.ifBlank { "Trip" }, stops.value)

    fun save(onDone: () -> Unit = {}) = viewModelScope.launch {
        if (TripRules.check(stops.value).isNotEmpty()) {
            message.value = "Fix the problems on this trip before saving."
            return@launch
        }
        c.trips.save(trip())
        setDirty(false)
        onDone()
    }

    fun export(uri: Uri, format: GeoFormat) = viewModelScope.launch {
        message.value = when (val r = c.importExport.exportTrip(trip(), uri, format)) {
            is IoResult.Ok -> "Exported ${r.value} stops"
            is IoResult.Failed -> r.message
        }
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_STOPS = "stops"
        const val KEY_DIRTY = "dirty"
        const val KEY_SEEDED = "seeded"
    }
}
