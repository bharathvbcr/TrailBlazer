package com.example.trailblazer.data

import android.content.ContentResolver
import android.database.sqlite.SQLiteException
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
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PushbackReader
import java.nio.charset.Charset

enum class GeoFormat(val mime: String, val extension: String) {
    Gpx("application/gpx+xml", "gpx"),
    Kml("application/vnd.google-earth.kml+xml", "kml"),
}

data class ImportSummary(val waypoints: Int, val trips: Int, val tracks: Int, val simplifiedRoutes: Int)

sealed interface IoResult<out T> {
    data class Ok<T>(val value: T) : IoResult<T>
    data class Failed(val message: String) : IoResult<Nothing>
}

internal fun openXmlReader(input: InputStream): PushbackReader {
    val bis = if (input is BufferedInputStream) input else BufferedInputStream(input, 8192)
    bis.mark(8192)
    val headerBytes = ByteArray(4096)
    val readCount = bis.read(headerBytes).coerceAtLeast(0)
    bis.reset()

    var charset = Charsets.UTF_8
    var bomSkip = 0

    if (readCount >= 4 && headerBytes[0] == 0x00.toByte() && headerBytes[1] == 0x00.toByte() && headerBytes[2] == 0xFE.toByte() && headerBytes[3] == 0xFF.toByte()) {
        charset = Charset.forName("UTF-32BE")
        bomSkip = 4
    } else if (readCount >= 4 && headerBytes[0] == 0xFF.toByte() && headerBytes[1] == 0xFE.toByte() && headerBytes[2] == 0x00.toByte() && headerBytes[3] == 0x00.toByte()) {
        charset = Charset.forName("UTF-32LE")
        bomSkip = 4
    } else if (readCount >= 3 && headerBytes[0] == 0xEF.toByte() && headerBytes[1] == 0xBB.toByte() && headerBytes[2] == 0xBF.toByte()) {
        charset = Charsets.UTF_8
        bomSkip = 3
    } else if (readCount >= 2 && headerBytes[0] == 0xFE.toByte() && headerBytes[1] == 0xFF.toByte()) {
        charset = Charsets.UTF_16BE
        bomSkip = 2
    } else if (readCount >= 2 && headerBytes[0] == 0xFF.toByte() && headerBytes[1] == 0xFE.toByte()) {
        charset = Charsets.UTF_16LE
        bomSkip = 2
    } else if (readCount >= 4 && headerBytes[0] == 0x00.toByte() && headerBytes[1] == 0x3C.toByte() && headerBytes[2] == 0x00.toByte() && headerBytes[3] == 0x3F.toByte()) {
        charset = Charsets.UTF_16BE
    } else if (readCount >= 4 && headerBytes[0] == 0x3C.toByte() && headerBytes[1] == 0x00.toByte() && headerBytes[2] == 0x3F.toByte() && headerBytes[3] == 0x00.toByte()) {
        charset = Charsets.UTF_16LE
    } else {
        val asciiHeader = String(headerBytes, 0, readCount, Charsets.ISO_8859_1)
        val xmlDeclMatch = Regex("""<\?xml\s+[^>]*\?>""").find(asciiHeader)
        if (xmlDeclMatch != null) {
            val decl = xmlDeclMatch.value
            val encMatch = Regex("""encoding\s*=\s*["']([^"']+)["']""").find(decl)
            if (encMatch != null) {
                val encName = encMatch.groupValues[1]
                try {
                    charset = Charset.forName(encName)
                } catch (_: Exception) {
                    charset = Charsets.UTF_8
                }
            }
        }
    }

    if (bomSkip > 0) {
        var skipped = 0L
        while (skipped < bomSkip) {
            val s = bis.skip(bomSkip.toLong() - skipped)
            if (s <= 0) {
                if (bis.read() == -1) break
                skipped++
            } else {
                skipped += s
            }
        }
    }

    return PushbackReader(BufferedReader(InputStreamReader(bis, charset)), 8192)
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
                val reader = openXmlReader(input)
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
        } catch (e: SQLiteException) {
            IoResult.Failed("Database error: ${e.message}")
        }
    }

    private suspend fun store(doc: GeoDocument): ImportSummary = db.withTransaction {
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
        val dao = db.tracks()
        for (track in doc.tracks) {
            val points = track.segments.flatten().sortedBy { it.epochMs }
            if (points.isEmpty()) continue
            val stats = TrackStats().also { s -> points.forEach { s.add(it) } }
            val id = newId()
            val entity = TrackEntity(
                id, (track.name ?: "Imported track").take(NAME_MAX), points.first().epochMs, points.last().epochMs, TrackState.Finished,
                stats.distanceM, stats.gainM, stats.lossM, stats.movingMs, stats.maxSpeedMps, points.size,
            )
            // All-or-nothing: the outer withTransaction ensures whole-import atomicity.
            dao.insert(entity)
            points.chunked(1_000).forEachIndexed { chunkIndex, chunk ->
                dao.insertPoints(chunk.mapIndexed { i, p ->
                    TrackPointEntity(id, chunkIndex * 1_000 + i, p.epochMs, p.position.lat, p.position.lon, p.elevationM, p.accuracyM, p.speedMps)
                })
            }
            t++
        }
        ImportSummary(w, doc.routes.count { it.points.size >= 2 }, t, simplified)
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
