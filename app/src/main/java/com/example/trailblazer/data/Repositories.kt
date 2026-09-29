package com.example.trailblazer.data

import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.io.NamedPoint
import com.trailblazer.core.track.TrackPoint
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import com.trailblazer.core.weather.PressureSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

data class Waypoint(
    val id: String,
    val name: String,
    val position: LatLon,
    val elevationM: Double?,
    val createdMs: Long,
    val note: String?,
)

fun newId(): String = UUID.randomUUID().toString()

/** Rows with coordinates that are no longer valid (e.g. edited by hand) are skipped rather than shown at 0,0. */
private fun WaypointEntity.toModel(): Waypoint? = LatLon.of(lat, lon)?.let { Waypoint(id, name, it, elevationM, createdMs, note) }

class WaypointRepository(private val dao: WaypointDao, private val clock: Clock) {
    val all: Flow<List<Waypoint>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun get(id: String): Waypoint? = dao.get(id)?.toModel()

    suspend fun add(name: String, position: LatLon, elevationM: Double?, note: String? = null): Waypoint {
        val w = Waypoint(newId(), name.trim().ifEmpty { "Waypoint" }.take(NAME_MAX), position, elevationM?.takeIf { it.isFinite() }, clock.nowMs(), note?.take(NOTE_MAX))
        dao.upsert(WaypointEntity(w.id, w.name, position.lat, position.lon, w.elevationM, w.createdMs, w.note))
        return w
    }

    suspend fun rename(id: String, name: String) {
        val e = dao.get(id) ?: return
        dao.upsert(e.copy(name = name.trim().ifEmpty { e.name }.take(NAME_MAX)))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun importAll(points: List<NamedPoint>): Int {
        val now = clock.nowMs()
        val rows = points.mapIndexed { i, p ->
            WaypointEntity(newId(), (p.name ?: "Imported ${i + 1}").take(NAME_MAX), p.position.lat, p.position.lon, p.elevationM, p.epochMs ?: now, p.description?.take(NOTE_MAX))
        }
        dao.upsertAll(rows)
        return rows.size
    }

    suspend fun snapshot(): List<Waypoint> = dao.all().mapNotNull { it.toModel() }

    companion object {
        const val NAME_MAX = 80
        const val NOTE_MAX = 2000
    }
}

private fun StopEntity.toModel(): Stop? {
    val pos = LatLon.of(lat, lon) ?: return null
    val k = StopKind.entries.firstOrNull { it.name == kind } ?: StopKind.Visit
    return Stop(id, name, k, pos, plannedDay, note)
}

private fun TripWithStops.toModel() = Trip(trip.id, trip.name, stops.sortedBy { it.ordinal }.mapNotNull { it.toModel() })

class TripRepository(private val dao: TripDao, private val clock: Clock) {
    val all: Flow<List<Trip>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    fun observe(id: String): Flow<Trip?> = dao.observe(id).map { it?.toModel() }

    suspend fun save(trip: Trip, createdMs: Long? = null) {
        val now = clock.nowMs()
        val entity = TripEntity(trip.id, trip.name.trim().ifEmpty { "Trip" }.take(80), createdMs ?: now, now)
        val stops = trip.stops.mapIndexed { i, s ->
            StopEntity(s.id, trip.id, i, s.name.take(80), s.kind.name, s.position.lat, s.position.lon, s.plannedDay, s.note?.take(2000))
        }
        dao.save(entity, stops)
    }

    suspend fun delete(id: String) = dao.delete(id)
}

data class TrackSummary(
    val id: String,
    val name: String,
    val startedMs: Long,
    val endedMs: Long?,
    val state: TrackState,
    val distanceM: Double,
    val gainM: Double,
    val lossM: Double,
    val movingMs: Long,
    val maxSpeedMps: Double?,
    val pointCount: Int,
)

private fun TrackEntity.toSummary() = TrackSummary(id, name, startedMs, endedMs, state, distanceM, gainM, lossM, movingMs, maxSpeedMps, pointCount)

class TrackRepository(private val dao: TrackDao) {
    val all: Flow<List<TrackSummary>> = dao.observeAll().map { rows -> rows.map { it.toSummary() } }
    val open: Flow<TrackSummary?> = dao.observeOpen().map { it?.toSummary() }

    fun observe(id: String): Flow<TrackSummary?> = dao.observe(id).map { it?.toSummary() }

    suspend fun rename(id: String, name: String) {
        val t = dao.get(id) ?: return
        dao.update(t.copy(name = name.trim().ifEmpty { t.name }.take(80)))
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** Streams a track's points in pages so exports and charts never hold 100 000 rows at once. */
    suspend fun forEachPoint(id: String, pageSize: Int = 2_000, block: suspend (TrackPoint) -> Unit) {
        var after = -1
        while (true) {
            val page = dao.pointsAfter(id, after, pageSize)
            if (page.isEmpty()) return
            for (p in page) {
                val pos = LatLon.of(p.lat, p.lon) ?: continue
                block(TrackPoint(p.timeMs, pos, p.elevationM, p.accuracyM, p.speedMps))
            }
            after = page.last().seq
        }
    }
}

/** Barometer history for the pressure trend. Samples older than 7 days are pruned. */
class PressureHistory(private val dao: PressureDao, private val clock: Clock) {
    fun recent(windowMs: Long): Flow<List<PressureSample>> =
        dao.observeSince(clock.nowMs() - windowMs).map { rows -> rows.map { PressureSample(it.timeMs, it.stationHpa, it.elevationM) } }

    suspend fun lastSampleMs(): Long? = dao.latestTime()

    suspend fun record(stationHpa: Double, elevationM: Double?) {
        if (!stationHpa.isFinite() || stationHpa !in 300.0..1100.0) return
        val now = clock.nowMs()
        dao.insert(PressureSampleEntity(now, stationHpa, elevationM?.takeIf { it.isFinite() }))
        dao.prune(now - RETENTION_MS)
    }

    companion object {
        const val RETENTION_MS = 7 * 24 * 3_600_000L
        const val INTERVAL_MS = 10 * 60_000L
    }
}
