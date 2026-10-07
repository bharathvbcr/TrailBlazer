package com.trailblazer.core.io

import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.formatDecimals
import com.trailblazer.core.time.Iso8601
import com.trailblazer.core.track.TrackPoint

/** A named position: a GPX waypoint / route point, or a KML Point placemark. */
data class NamedPoint(
    val position: LatLon,
    val name: String? = null,
    val description: String? = null,
    val elevationM: Double? = null,
    val epochMs: Long? = null,
)

data class Route(val name: String?, val points: List<NamedPoint>)

data class TrackData(val name: String?, val segments: List<List<TrackPoint>>)

data class GeoDocument(val waypoints: List<NamedPoint>, val routes: List<Route>, val tracks: List<TrackData>) {
    val isEmpty: Boolean get() = waypoints.isEmpty() && routes.isEmpty() && tracks.all { t -> t.segments.all { it.isEmpty() } }
}

private class RawTrkpt(val pos: LatLon, val ele: Double?, val time: Long?, val speed: Double?)

private fun local(name: String) = name.substringAfter(':')
private fun f6(v: Double) = formatDecimals(v, 7).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
private fun f1(v: Double) = formatDecimals(v, 1)

/** Reads GPX 1.0/1.1 waypoints, routes and tracks. Points with invalid coordinates are skipped, not fabricated. */
object GpxReader {
    fun read(reader: Reader, maxPoints: Int = 1_000_000): GeoDocument {
        val xml = XmlPull(reader)
        val wpts = ArrayList<NamedPoint>()
        val routes = ArrayList<Route>()
        val tracks = ArrayList<TrackData>()
        var total = 0
        var sawRoot = false

        var curRoutePts: MutableList<NamedPoint>? = null
        var curRouteName: String? = null
        var curTrackName: String? = null
        var curSegments: MutableList<List<RawTrkpt>>? = null
        var curSeg: MutableList<RawTrkpt>? = null

        var ptLat: Double? = null
        var ptLon: Double? = null
        var ptName: String? = null
        var ptDesc: String? = null
        var ptEle: Double? = null
        var ptTime: Long? = null
        var ptSpeed: Double? = null
        var inPoint: String? = null
        val text = StringBuilder()
        val stack = ArrayList<String>()

        while (true) {
            when (val e = xml.next()) {
                is XmlPull.Event.EndDocument -> break
                is XmlPull.Event.Text -> text.append(e.text)
                is XmlPull.Event.Start -> {
                    val n = local(e.name)
                    stack += n
                    text.setLength(0)
                    if (n == "gpx") sawRoot = true
                    when (n) {
                        "wpt", "rtept", "trkpt" -> {
                            if (++total > maxPoints) throw XmlFormatException("more than $maxPoints points")
                            inPoint = n
                            ptLat = e.attrs["lat"]?.trim()?.toDoubleOrNull()
                            ptLon = e.attrs["lon"]?.trim()?.toDoubleOrNull()
                            ptName = null; ptDesc = null; ptEle = null; ptTime = null; ptSpeed = null
                        }
                        "rte" -> { curRoutePts = ArrayList(); curRouteName = null }
                        "trk" -> { curSegments = ArrayList(); curTrackName = null }
                        "trkseg" -> curSeg = ArrayList()
                    }
                }
                is XmlPull.Event.End -> {
                    val n = local(e.name)
                    val parent = stack.getOrNull(stack.size - 2)
                    val value = text.toString().trim()
                    when (n) {
                        "name" -> when {
                            inPoint != null -> ptName = value.ifEmpty { null }
                            parent == "rte" -> curRouteName = value.ifEmpty { null }
                            parent == "trk" -> curTrackName = value.ifEmpty { null }
                        }
                        "desc", "cmt" -> if (inPoint != null && ptDesc == null) ptDesc = value.ifEmpty { null }
                        "ele" -> if (inPoint != null) ptEle = value.toDoubleOrNull()?.takeIf { it.isFinite() }
                        "time" -> if (inPoint != null) ptTime = Iso8601.parse(value)
                        "speed" -> if (inPoint != null) ptSpeed = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                        "wpt", "rtept", "trkpt" -> {
                            val lat = ptLat
                            val lon = ptLon
                            val pos = if (lat != null && lon != null && kotlin.math.abs(lon) <= 180) LatLon.of(lat, lon) else null
                            if (pos != null) {
                                when (n) {
                                    "wpt" -> wpts += NamedPoint(pos, ptName, ptDesc, ptEle, ptTime)
                                    "rtept" -> curRoutePts?.add(NamedPoint(pos, ptName, ptDesc, ptEle, ptTime))
                                    "trkpt" -> curSeg?.add(RawTrkpt(pos, ptEle, ptTime, ptSpeed))
                                }
                            }
                            inPoint = null
                        }
                        "rte" -> { curRoutePts?.let { routes += Route(curRouteName, it) }; curRoutePts = null }
                        "trkseg" -> { curSeg?.let { if (it.isNotEmpty()) curSegments?.add(it) }; curSeg = null }
                        "trk" -> {
                            curSegments?.let { segs ->
                                // A track is only a track if every point is timed; otherwise it is a path, kept as a
                                // route rather than inventing timestamps.
                                if (segs.all { seg -> seg.all { it.time != null } }) {
                                    tracks += TrackData(curTrackName, segs.map { seg -> seg.map { TrackPoint(it.time!!, it.pos, it.ele, null, it.speed) } })
                                } else {
                                    routes += Route(curTrackName, segs.flatten().map { NamedPoint(it.pos, elevationM = it.ele, epochMs = it.time) })
                                }
                            }
                            curSegments = null
                        }
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    text.setLength(0)
                }
            }
        }
        if (!sawRoot) throw XmlFormatException("not a GPX file")
        return GeoDocument(wpts, routes, tracks)
    }
}

/** Streaming GPX 1.1 writer: points are written as they are supplied, so exports use constant memory. */
class GpxWriter(private val out: Writer, creator: String = "TrailBlazer") {
    init {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<gpx version=\"1.1\" creator=\"${XmlPull.escape(creator)}\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    }

    private fun pointBody(p: NamedPoint, indent: String) {
        p.elevationM?.takeIf { it.isFinite() }?.let { out.write("$indent<ele>${f1(it)}</ele>\n") }
        p.epochMs?.let { out.write("$indent<time>${Iso8601.format(it)}</time>\n") }
        p.name?.let { out.write("$indent<name>${XmlPull.escape(it)}</name>\n") }
        p.description?.let { out.write("$indent<desc>${XmlPull.escape(it)}</desc>\n") }
    }

    fun waypoint(p: NamedPoint) {
        out.write("  <wpt lat=\"${f6(p.position.lat)}\" lon=\"${f6(p.position.lon)}\">\n")
        pointBody(p, "    ")
        out.write("  </wpt>\n")
    }

    fun route(r: Route) {
        out.write("  <rte>\n")
        r.name?.let { out.write("    <name>${XmlPull.escape(it)}</name>\n") }
        for (p in r.points) {
            out.write("    <rtept lat=\"${f6(p.position.lat)}\" lon=\"${f6(p.position.lon)}\">\n")
            pointBody(p, "      ")
            out.write("    </rtept>\n")
        }
        out.write("  </rte>\n")
    }

    fun beginTrack(name: String?) {
        out.write("  <trk>\n")
        name?.let { out.write("    <name>${XmlPull.escape(it)}</name>\n") }
    }

    fun beginSegment() = out.write("    <trkseg>\n")

    fun trackPoint(p: TrackPoint) {
        out.write("      <trkpt lat=\"${f6(p.position.lat)}\" lon=\"${f6(p.position.lon)}\">")
        p.elevationM?.takeIf { it.isFinite() }?.let { out.write("<ele>${f1(it)}</ele>") }
        out.write("<time>${Iso8601.format(p.epochMs)}</time>")
        out.write("</trkpt>\n")
    }

    fun endSegment() = out.write("    </trkseg>\n")
    fun endTrack() = out.write("  </trk>\n")

    fun finish() {
        out.write("</gpx>\n")
        out.flush()
    }
}

/** Reads KML Point placemarks as waypoints, LineStrings as routes, and gx:Track as tracks. */
object KmlReader {
    fun read(reader: Reader, maxPoints: Int = 1_000_000): GeoDocument {
        val xml = XmlPull(reader)
        val wpts = ArrayList<NamedPoint>()
        val routes = ArrayList<Route>()
        val tracks = ArrayList<TrackData>()
        var sawRoot = false
        var total = 0
        var pmName: String? = null
        var pmDesc: String? = null
        var geometry: String? = null
        var coords: String? = null
        val whens = ArrayList<Long?>()
        val gxCoords = ArrayList<String>()
        val text = StringBuilder()
        val stack = ArrayList<String>()

        fun parseCoord(tuple: String, sep: Regex): Pair<LatLon, Double?>? {
            val parts = tuple.trim().split(sep)
            if (parts.size < 2) return null
            val lon = parts[0].toDoubleOrNull() ?: return null
            val lat = parts[1].toDoubleOrNull() ?: return null
            if (kotlin.math.abs(lon) > 180) return null
            val pos = LatLon.of(lat, lon) ?: return null
            return Pair(pos, parts.getOrNull(2)?.toDoubleOrNull()?.takeIf { it.isFinite() })
        }

        while (true) {
            when (val e = xml.next()) {
                is XmlPull.Event.EndDocument -> break
                is XmlPull.Event.Text -> text.append(e.text)
                is XmlPull.Event.Start -> {
                    val n = local(e.name)
                    stack += n
                    text.setLength(0)
                    when (n) {
                        "kml" -> sawRoot = true
                        "Placemark" -> { pmName = null; pmDesc = null; geometry = null; coords = null; whens.clear(); gxCoords.clear() }
                        "Point", "LineString", "Track" -> geometry = n
                    }
                }
                is XmlPull.Event.End -> {
                    val n = local(e.name)
                    val parent = stack.getOrNull(stack.size - 2)
                    val value = text.toString().trim()
                    when (n) {
                        "name" -> if (parent == "Placemark") pmName = value.ifEmpty { null }
                        "description" -> if (parent == "Placemark") pmDesc = value.ifEmpty { null }
                        "coordinates" -> coords = value
                        "when" -> whens += Iso8601.parse(value)
                        "coord" -> gxCoords += value
                        "Placemark" -> {
                            when (geometry) {
                                "Point" -> coords?.let { c ->
                                    parseCoord(c.split(Regex("\\s+")).first(), Regex(","))?.let { (p, ele) ->
                                        if (++total > maxPoints) throw XmlFormatException("more than $maxPoints points")
                                        wpts += NamedPoint(p, pmName, pmDesc, ele)
                                    }
                                }
                                "LineString" -> coords?.let { c ->
                                    val pts = c.split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { t ->
                                        if (++total > maxPoints) throw XmlFormatException("more than $maxPoints points")
                                        parseCoord(t, Regex(","))?.let { (p, ele) -> NamedPoint(p, elevationM = ele) }
                                    }
                                    if (pts.isNotEmpty()) routes += Route(pmName, pts)
                                }
                                "Track" -> {
                                    val pts = gxCoords.mapIndexedNotNull { i, t ->
                                        if (++total > maxPoints) throw XmlFormatException("more than $maxPoints points")
                                        val time = whens.getOrNull(i) ?: return@mapIndexedNotNull null
                                        parseCoord(t, Regex("\\s+"))?.let { (p, ele) -> TrackPoint(time, p, ele) }
                                    }
                                    if (pts.isNotEmpty()) tracks += TrackData(pmName, listOf(pts))
                                }
                            }
                        }
                    }
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    text.setLength(0)
                }
            }
        }
        if (!sawRoot) throw XmlFormatException("not a KML file")
        return GeoDocument(wpts, routes, tracks)
    }
}

/** Streaming KML 2.2 writer. */
class KmlWriter(private val out: Writer, name: String?) {
    init {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">\n<Document>\n")
        name?.let { out.write("<name>${XmlPull.escape(it)}</name>\n") }
    }

    private fun coord(p: LatLon, ele: Double?, sep: String) =
        f6(p.lon) + sep + f6(p.lat) + (ele?.takeIf { it.isFinite() }?.let { sep + f1(it) } ?: "")

    fun placemark(p: NamedPoint) {
        out.write("<Placemark>")
        p.name?.let { out.write("<name>${XmlPull.escape(it)}</name>") }
        p.description?.let { out.write("<description>${XmlPull.escape(it)}</description>") }
        out.write("<Point><coordinates>${coord(p.position, p.elevationM, ",")}</coordinates></Point></Placemark>\n")
    }

    fun lineString(r: Route) {
        out.write("<Placemark>")
        r.name?.let { out.write("<name>${XmlPull.escape(it)}</name>") }
        out.write("<LineString><tessellate>1</tessellate><coordinates>\n")
        for (p in r.points) out.write(coord(p.position, p.elevationM, ",") + "\n")
        out.write("</coordinates></LineString></Placemark>\n")
    }

    /** Tracks are streamed as a LineString; only GPX export preserves per-point timestamps. */
    fun beginTrack(name: String?) {
        out.write("<Placemark>")
        name?.let { out.write("<name>${XmlPull.escape(it)}</name>") }
        out.write("<LineString><tessellate>1</tessellate><coordinates>\n")
    }

    fun trackPoint(p: TrackPoint) = out.write(coord(p.position, p.elevationM, ",") + "\n")

    fun endTrack() = out.write("</coordinates></LineString></Placemark>\n")

    fun finish() {
        out.write("</Document>\n</kml>\n")
        out.flush()
    }
}
