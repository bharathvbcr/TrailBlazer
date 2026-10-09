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

    val availableMaps = MutableStateFlow<List<com.example.trailblazer.data.OfflineMapInfo>>(emptyList())
    val activeTileSource = MutableStateFlow<com.example.trailblazer.data.OfflineTileSource?>(null)
    val trackPaths = MutableStateFlow<List<List<com.trailblazer.core.geo.LatLon>>>(emptyList())

    /** One-line result of the last import/export, shown until dismissed. */
    val message = MutableStateFlow<String?>(null)

    init {
        refreshMaps()
        viewModelScope.launch {
            tracks.collect { summaries ->
                loadTrackPaths(summaries)
            }
        }
    }

    fun refreshMaps() {
        availableMaps.value = c.offlineMaps.listMaps()
        val old = activeTileSource.value
        val next = c.offlineMaps.openActiveSource()
        if (old?.file?.absolutePath != next?.file?.absolutePath) {
            old?.close()
            activeTileSource.value = next
        }
    }

    private suspend fun loadTrackPaths(summaries: List<TrackSummary>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val paths = mutableListOf<List<com.trailblazer.core.geo.LatLon>>()
        summaries.take(20).forEach { summary ->
            val pts = mutableListOf<com.trailblazer.core.geo.LatLon>()
            var idx = 0
            val stride = (summary.pointCount / 300).coerceAtLeast(1)
            c.tracks.forEachPoint(summary.id) { pt ->
                if (idx++ % stride == 0) pts.add(pt.position)
            }
            if (pts.size >= 2) paths.add(pts)
        }
        trackPaths.value = paths
    }

    fun importMap(uri: Uri) = viewModelScope.launch {
        val result = c.offlineMaps.importMap(uri)
        if (result.isSuccess) {
            val info = result.getOrThrow()
            message.value = "Imported map “${info.name}” (${info.formattedSize})"
            refreshMaps()
        } else {
            message.value = "Import failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
        }
    }

    fun selectMap(file: java.io.File) = viewModelScope.launch {
        c.prefs.update { it.copy(activeOfflineMapPath = file.absolutePath) }
        refreshMaps()
    }

    fun deleteMap(file: java.io.File) = viewModelScope.launch {
        val name = file.name
        c.offlineMaps.deleteMap(file)
        message.value = "Deleted map “$name”"
        refreshMaps()
    }

    override fun onCleared() {
        super.onCleared()
        activeTileSource.value?.close()
    }

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
