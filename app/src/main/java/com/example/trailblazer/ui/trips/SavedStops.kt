package com.example.trailblazer.ui.trips

import android.os.Bundle
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.TripRules

/**
 * The editor's stops as they go into a SavedStateHandle, so unsaved edits survive the process being killed.
 *
 * The saved-instance-state Bundle shares a ~1 MB transaction budget with the whole app, so this form is bounded:
 * at most [TripRules.MAX_STOPS] stops, each with capped text, a few hundred bytes apiece. What comes back is
 * untrusted (the Bundle outlived the process that wrote it), so decoding drops anything malformed rather than
 * failing, and re-caps the list.
 */
internal object SavedStops {
    const val NAME_MAX = 80
    const val NOTE_MAX = 1_000

    fun encode(stops: List<Stop>): ArrayList<Bundle> = ArrayList(
        stops.take(TripRules.MAX_STOPS).map { s ->
            Bundle().apply {
                putString("id", s.id)
                putString("name", s.name.take(NAME_MAX))
                putString("kind", s.kind.name)
                putDouble("lat", s.position.lat)
                putDouble("lon", s.position.lon)
                s.plannedDay?.let { putLong("day", it) }
                s.note?.let { putString("note", it.take(NOTE_MAX)) }
            }
        },
    )

    fun decode(saved: Any?): List<Stop> {
        val list = saved as? List<*> ?: return emptyList()
        return list.asSequence()
            .filterIsInstance<Bundle>()
            .mapNotNull(::decodeOne)
            .take(TripRules.MAX_STOPS)
            .toList()
    }

    private fun decodeOne(b: Bundle): Stop? {
        val id = b.getString("id")?.takeIf { it.isNotEmpty() } ?: return null
        val pos = LatLon.of(b.getDouble("lat", Double.NaN), b.getDouble("lon", Double.NaN)) ?: return null
        val kind = b.getString("kind")?.let { n -> StopKind.entries.firstOrNull { it.name == n } } ?: StopKind.Visit
        return Stop(
            id = id,
            name = (b.getString("name") ?: "").take(NAME_MAX),
            kind = kind,
            position = pos,
            plannedDay = if (b.containsKey("day")) b.getLong("day") else null,
            note = b.getString("note")?.take(NOTE_MAX),
        )
    }
}
