package com.example.trailblazer.data

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.trailblazer.core.io.GeoDocument
import com.trailblazer.core.io.GpxReader
import com.trailblazer.core.io.GpxWriter
import com.trailblazer.core.io.KmlReader
import com.trailblazer.core.io.KmlWriter
import com.trailblazer.core.io.NamedPoint
import com.trailblazer.core.io.Route
import com.trailblazer.core.io.XmlFormatException
import com.trailblazer.core.track.DouglasPeucker
import com.trailblazer.core.track.TrackStats
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import com.trailblazer.core.trip.TripRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PushbackReader

enum class GeoFormat(val mime: String, val extension: String) {
    Gpx("application/gpx+xml", "gpx"),
    Kml("application/vnd.google-earth.kml+xml", "kml"),
}

data class ImportSummary(val waypoints: Int, val trips: Int, val tracks: Int, val simplifiedRoutes: Int)

sealed interface IoResult<out T> {
    data class Ok<T>(val value: T) : IoResult<T>
    data class Failed(val message: String) : IoResult<Nothing>
}

/**
 * GPX/KML import and export through the Storage Access Framework (no storage permission).
 * Exports stream straight to the chosen file; imports are bounded by the XML reader's limits.
 */
class ImportExport(
    private val resolver: ContentResolver,
    private val db: TrailDb,
    private val waypoints: WaypointRepository,
    private val trips: TripRepository,
    private val tracks: TrackRepository,
) {
    suspend fun import(uri: Uri): IoResult<ImportSummary> = withContext(Dispatchers.IO) {
        try {
            val doc = resolver.openInputStream(uri)?.use { input ->
                val reader = PushbackReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8)), 4096)
                val head = CharArray(4096)
                val n = reader.read(head).coerceAtLeast(0)
                reader.unread(head, 0, n)
                val sniff = String(head, 0, n)
                when {
                    sniff.contains("<kml") -> KmlReader.read(reader)
                    sniff.contains("<gpx") -> GpxReader.read(reader)
                    else -> throw XmlFormatException("not a GPX or KML file")
                }
            } ?: return@withContext IoResult.Failed("Could not open the file")
            if (doc.isEmpty) return@withContext IoResult.Failed("The file has no waypoints, routes or tracks")
            IoResult.Ok(store(doc))
        } catch (e: XmlFormatException) {
            IoResult.Failed("Invalid file: ${e.message}")
        } catch (e: IOException) {
            IoResult.Failed("Read error: ${e.message}")
        } catch (e: SecurityException) {
            IoResult.Failed("No access to that file")
        }
    }

    private suspend fun store(doc: GeoDocument): ImportSummary {
        val w = if (doc.waypoints.isNotEmpty()) waypoints.importAll(doc.waypoints) else 0
        var simplified = 0
        for (r in doc.routes) {
            val pts = if (r.points.size > TripRules.MAX_STOPS) {
                simplified++
                DouglasPeucker.simplifyToMax(r.points, TripRules.MAX_STOPS) { it.position }
            } else r.points
            if (pts.size < 2) continue
            val stops = pts.mapIndexed { i, p ->
                val kind = when (i) { 0 -> StopKind.Start; pts.size - 1 -> StopKind.End; else -> StopKind.Visit }
                Stop(newId(), p.name ?: "Point ${i + 1}", kind, p.position, null, p.description)
            }
            trips.save(Trip(newId(), r.name ?: "Imported route", stops))
        }
        var t = 0
        for (track in doc.tracks) {
            val points = track.segments.flatten().sortedBy { it.epochMs }
            if (points.isEmpty()) continue
            val stats = TrackStats().also { s -> points.forEach { s.add(it) } }
            val id = newId()
            val entity = TrackEntity(
                id, (track.name ?: "Imported track").take(80), points.first().epochMs, points.last().epochMs, TrackState.Finished,
                stats.distanceM, stats.gainM, stats.lossM, stats.movingMs, stats.maxSpeedMps, points.size,
            )
            val dao = db.tracks()
            // All-or-nothing: a failed import never leaves a half-written track behind.
            db.withTransaction {
                dao.insert(entity)
                points.chunked(1_000).forEachIndexed { chunkIndex, chunk ->
                    dao.insertPoints(chunk.mapIndexed { i, p ->
                        TrackPointEntity(id, chunkIndex * 1_000 + i, p.epochMs, p.position.lat, p.position.lon, p.elevationM, p.accuracyM, p.speedMps)
                    })
                }
            }
            t++
        }
        return ImportSummary(w, doc.routes.count { it.points.size >= 2 }, t, simplified)
    }

    private suspend fun <T> write(uri: Uri, block: suspend (BufferedWriter) -> T): IoResult<T> = withContext(Dispatchers.IO) {
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: return@withContext IoResult.Failed("Could not create the file")
            val result = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { block(it) }
            IoResult.Ok(result)
        } catch (e: IOException) {
            IoResult.Failed("Write error: ${e.message}")
        } catch (e: SecurityException) {
            IoResult.Failed("No access to that location")
        }
    }

    suspend fun exportWaypoints(uri: Uri, format: GeoFormat): IoResult<Int> {
        val list = waypoints.snapshot()
        return write(uri) { w ->
            val points = list.map { NamedPoint(it.position, it.name, it.note, it.elevationM, it.createdMs) }
            when (format) {
                GeoFormat.Gpx -> GpxWriter(w).apply { points.forEach { waypoint(it) }; finish() }
                GeoFormat.Kml -> KmlWriter(w, "TrailBlazer waypoints").apply { points.forEach { placemark(it) }; finish() }
            }
            points.size
        }
    }

    suspend fun exportTrip(trip: Trip, uri: Uri, format: GeoFormat): IoResult<Int> = write(uri) { w ->
        val pts = trip.stops.map { NamedPoint(it.position, it.name, listOfNotNull(it.kind.name, it.note).joinToString(" · ")) }
        when (format) {
            GeoFormat.Gpx -> GpxWriter(w).apply { pts.forEach { waypoint(it) }; route(Route(trip.name, pts)); finish() }
            GeoFormat.Kml -> KmlWriter(w, trip.name).apply { pts.forEach { placemark(it) }; lineString(Route(trip.name, pts)); finish() }
        }
        pts.size
    }

    suspend fun exportTrack(id: String, uri: Uri, format: GeoFormat): IoResult<Int> {
        val t = db.tracks().get(id) ?: return IoResult.Failed("Track not found")
        return write(uri) { w ->
            var n = 0
            when (format) {
                GeoFormat.Gpx -> GpxWriter(w).apply {
                    beginTrack(t.name); beginSegment()
                    tracks.forEachPoint(id) { trackPoint(it); n++ }
                    endSegment(); endTrack(); finish()
                }
                GeoFormat.Kml -> KmlWriter(w, t.name).apply {
                    beginTrack(t.name)
                    tracks.forEachPoint(id) { trackPoint(it); n++ }
                    endTrack(); finish()
                }
            }
            n
        }
    }
}
