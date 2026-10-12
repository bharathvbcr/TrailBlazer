package com.trailblazer.core.io

import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.track.ElevationDatum
import com.trailblazer.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Segments and elevation datum in GPX/KML: what is read, and what is written. */
class GeoSegmentsTest {
    private fun pt(i: Int, segment: Int, ele: Double?, datum: ElevationDatum?) =
        TrackPoint(1_700_000_000_000 + i * 1000L, LatLon(46.0 + i * 1e-4, 7.0), ele, null, null, segment, datum)

    private fun gpx(points: List<TrackPoint>): String {
        val sw = StringWriter()
        val w = GpxWriter(sw)
        w.beginTrack("Walk")
        var seg = -1
        for (p in points) {
            if (p.segment != seg) {
                if (seg >= 0) w.endSegment()
                w.beginSegment()
                seg = p.segment
            }
            w.trackPoint(p)
        }
        if (seg >= 0) w.endSegment()
        w.endTrack()
        w.finish()
        return sw.toString()
    }

    @Test
    fun gpxReaderGivesEachTrksegItsOwnIndex() {
        val xml = """<gpx><trk><name>T</name>
            <trkseg><trkpt lat="46.0" lon="7.0"><ele>100</ele><time>2026-01-01T00:00:00Z</time></trkpt>
                    <trkpt lat="46.1" lon="7.1"><ele>110</ele><time>2026-01-01T00:01:00Z</time></trkpt></trkseg>
            <trkseg><trkpt lat="46.5" lon="7.5"><time>2026-01-01T02:00:00Z</time></trkpt></trkseg>
        </trk></gpx>"""
        val trk = GpxReader.read(StringReader(xml)).tracks.single()
        assertEquals(listOf(0, 0), trk.segments[0].map { it.segment })
        assertEquals(listOf(1), trk.segments[1].map { it.segment })
        // `<ele>` is read as sea level; a point without one has no datum to speak of.
        assertEquals(ElevationDatum.Msl, trk.segments[0][0].datum)
        assertNull(trk.segments[1][0].datum)
    }

    @Test
    fun kmlGxMultiTrackGivesEachTrackItsOwnIndex() {
        val kml = """<kml xmlns:gx="http://www.google.com/kml/ext/2.2"><Placemark><name>M</name><gx:MultiTrack>
            <gx:Track><when>2026-01-01T00:00:00Z</when><gx:coord>7.0 46.0 100</gx:coord></gx:Track>
            <gx:Track><when>2026-01-01T01:00:00Z</when><gx:coord>7.2 46.2 120</gx:coord></gx:Track>
        </gx:MultiTrack></Placemark></kml>"""
        val trk = KmlReader.read(StringReader(kml)).tracks.single()
        assertEquals(listOf(0), trk.segments[0].map { it.segment })
        assertEquals(listOf(1), trk.segments[1].map { it.segment })
    }

    @Test
    fun gpxWritesOneTrksegPerSegmentAndReadsBackTheSame() {
        val points = listOf(pt(0, 0, 10.0, ElevationDatum.Msl), pt(1, 0, 11.0, ElevationDatum.Msl), pt(5, 1, 12.0, ElevationDatum.Msl))
        val xml = gpx(points)
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
        val trk = GpxReader.read(StringReader(xml)).tracks.single()
        assertEquals(listOf(2, 1), trk.segments.map { it.size })
    }

    @Test
    fun gpxOmitsEleForEllipsoidAndUnknownDatumAndKeepsItForSeaLevel() {
        val xml = gpx(listOf(pt(0, 0, 10.0, ElevationDatum.Msl), pt(1, 0, 57.0, ElevationDatum.Ellipsoid), pt(2, 0, 12.0, null)))
        val pts = GpxReader.read(StringReader(xml)).tracks.single().segments.single()
        assertEquals(10.0, pts[0].elevationM!!, 1e-9)
        assertNull(pts[1].elevationM, "an ellipsoid height must not be written as sea level")
        assertNull(pts[2].elevationM, "an unknown datum must not be written as sea level")
        assertFalse(xml.contains("57.0"))
    }

    private fun kml(points: List<TrackPoint>): String {
        val sw = StringWriter()
        val w = KmlWriter(sw, "Doc")
        w.beginTrack("Walk")
        var seg = -1
        for (p in points) {
            if (p.segment != seg) {
                w.beginSegment()
                seg = p.segment
            }
            w.trackPoint(p)
        }
        w.endTrack()
        w.finish()
        return sw.toString()
    }

    @Test
    fun kmlWritesOneLineStringPerSegment() {
        val xml = kml(listOf(pt(0, 0, 10.0, ElevationDatum.Msl), pt(1, 0, 11.0, ElevationDatum.Msl), pt(5, 1, 12.0, ElevationDatum.Msl)))
        assertEquals(2, Regex("<LineString>").findAll(xml).count())
        assertEquals(1, Regex("<MultiGeometry>").findAll(xml).count())
        val routes = KmlReader.read(StringReader(xml)).routes
        assertEquals(listOf(2, 1), routes.map { it.points.size })
        assertEquals(10.0, routes[0].points[0].elevationM!!, 1e-9)
    }

    @Test
    fun kmlOmitsAltitudeForEllipsoidAndUnknownDatum() {
        val xml = kml(listOf(pt(0, 0, 10.0, ElevationDatum.Msl), pt(1, 0, 57.0, ElevationDatum.Ellipsoid), pt(2, 0, 12.0, null)))
        val pts = KmlReader.read(StringReader(xml)).routes.single().points
        assertEquals(10.0, pts[0].elevationM!!, 1e-9)
        assertNull(pts[1].elevationM)
        assertNull(pts[2].elevationM)
    }

    @Test
    fun kmlTrackWithNoPointsIsStillWellFormed() {
        val sw = StringWriter()
        val w = KmlWriter(sw, "Doc")
        w.beginTrack("Empty")
        w.endTrack()
        w.finish()
        assertTrue(KmlReader.read(StringReader(sw.toString())).routes.isEmpty())
    }
}
