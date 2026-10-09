package com.example.trailblazer.ui.trips

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.GeoFormat
import com.example.trailblazer.data.IoResult
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.TrackSummary
import com.example.trailblazer.data.Waypoint
import com.example.trailblazer.location.Fix
import com.trailblazer.core.sensors.Reading
import com.example.trailblazer.tracking.InterruptReason
import com.trailblazer.core.trip.Trip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TripsViewModel(private val c: AppContainer) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)
    val settings: StateFlow<Settings?> = c.prefs.settings.stateIn(viewModelScope, started, null)
    val trips: StateFlow<List<Trip>> = c.trips.all.stateIn(viewModelScope, started, emptyList())
    val waypoints: StateFlow<List<Waypoint>> = c.waypoints.all.stateIn(viewModelScope, started, emptyList())
    val tracks: StateFlow<List<TrackSummary>> = c.tracks.all.stateIn(viewModelScope, started, emptyList())
    val openTrack: StateFlow<TrackSummary?> = c.tracking.open.stateIn(viewModelScope, started, null)
    val recording: StateFlow<Boolean> = c.tracking.running
    val interrupted: StateFlow<InterruptReason?> = c.tracking.interrupted
    val fix: StateFlow<Reading<Fix>> = c.location.fix

    /** One-line result of the last import/export, shown until dismissed. */
    val message = MutableStateFlow<String?>(null)

    fun startRecording(): Boolean = c.tracking.start()
    fun pauseRecording() = c.tracking.pause()
    fun resumeRecording(): Boolean = c.tracking.resume()
    fun stopRecording() = c.tracking.stop()

    fun deleteTrip(id: String) = viewModelScope.launch { c.trips.delete(id) }
    fun deleteWaypoint(id: String) = viewModelScope.launch {
        c.waypoints.delete(id)
        if (settings.value?.targetWaypointId == id) c.prefs.update { it.copy(targetWaypointId = null) }
    }
    fun renameWaypoint(id: String, name: String) = viewModelScope.launch { c.waypoints.rename(id, name) }
    fun navigateTo(id: String) = viewModelScope.launch { c.prefs.update { it.copy(targetWaypointId = id) } }
    fun deleteTrack(id: String) = viewModelScope.launch { c.tracks.delete(id) }

    fun import(uri: Uri) = viewModelScope.launch {
        message.value = when (val r = c.importExport.import(uri)) {
            is IoResult.Ok -> with(r.value) {
                buildString {
                    append("Imported $waypoints waypoints, $trips trips, $tracks tracks")
                    if (simplifiedRoutes > 0) append(" ($simplifiedRoutes long routes simplified to 50 stops)")
                }
            }
            is IoResult.Failed -> r.message
        }
    }

    fun exportWaypoints(uri: Uri, format: GeoFormat) = viewModelScope.launch {
        message.value = when (val r = c.importExport.exportWaypoints(uri, format)) {
            is IoResult.Ok -> "Exported ${r.value} waypoints"
            is IoResult.Failed -> r.message
        }
    }
}
