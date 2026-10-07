package com.trailblazer.core.io

import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail


class GeoFormatsTest {
    private val nasty = "Camp <script>alert(\"x\")</script> & 'Bob's' \u0001 ☀"

    @Test
    fun gpxRoundTripEscapesAndPreservesData() {
        val sw = StringWriter()
        val w = GpxWriter(sw)
        w.waypoint(NamedPoint(LatLon(46.5582, 7.8352), nasty, "desc & more", 2061.5, 1_700_000_000_000))
        w.route(com.trailblazer.core.io.Route("Plan", listOf(NamedPoint(LatLon(1.0, 2.0), "A"), NamedPoint(LatLon(3.0, 4.0), "B"))))
        w.beginTrack("Walk")
        w.beginSegment()
        for (i in 0 until 5) w.trackPoint(TrackPoint(1_700_000_000_000 + i * 1000L, LatLon(46.0 + i * 1e-4, 7.0), 1000.0 + i))
        w.endSegment()
        w.endTrack()
        w.finish()
        val xml = sw.toString()
        assertFalse(xml.contains("<script>"))
        assertTrue(xml.contains("&lt;script&gt;"))
        assertFalse(xml.contains('\u0001'))

        val doc = GpxReader.read(StringReader(xml))
        val wp = doc.waypoints.single()
        assertEquals("Camp <script>alert(\"x\")</script> & 'Bob's'  ☀", wp.name)
        assertEquals(46.5582, wp.position.lat, 1e-7)
        assertEquals(2061.5, wp.elevationM!!, 1e-9)
        assertEquals(1_700_000_000_000, wp.epochMs)
        assertEquals(listOf("A", "B"), doc.routes.single().points.map { it.name })
        val seg = doc.tracks.single().segments.single()
        assertEquals(5, seg.size)
        assertEquals(1_700_000_004_000, seg.last().epochMs)
        assertEquals(1004.0, seg.last().elevationM!!, 1e-9)
    }

    @Test
    fun untimedTrackImportsAsRouteNotFakeTimes() {
        val gpx = """<gpx><trk><name>Old</name><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.1" lon="2.1"><ele>5</ele></trkpt></trkseg></trk></gpx>"""
        val doc = GpxReader.read(StringReader(gpx))
        assertTrue(doc.tracks.isEmpty())
        assertEquals("Old", doc.routes.single().name)
        assertEquals(2, doc.routes.single().points.size)
    }

    @Test
    fun kmlRoundTrip() {
        val sw = StringWriter()
        val w = KmlWriter(sw, "Trip & Co")
        w.placemark(NamedPoint(LatLon(-33.8688, 151.2093), "Opera <House>", elevationM = 5.0))
        w.lineString(com.trailblazer.core.io.Route("Leg", listOf(NamedPoint(LatLon(1.0, 2.0)), NamedPoint(LatLon(3.0, 4.0)))))
        w.beginTrack("T")
        w.trackPoint(TrackPoint(0, LatLon(5.0, 6.0)))
        w.trackPoint(TrackPoint(1, LatLon(5.1, 6.1)))
        w.endTrack()
        w.finish()
        val doc = KmlReader.read(StringReader(sw.toString()))
        assertEquals("Opera <House>", doc.waypoints.single().name)
        assertEquals(151.2093, doc.waypoints.single().position.lon, 1e-7)
        assertEquals(listOf("Leg", "T"), doc.routes.map { it.name })
    }

    @Test
    fun kmlGxTrackImport() {
        val kml = """<kml xmlns:gx="http://www.google.com/kml/ext/2.2"><Placemark><name>G</name><gx:Track>
            <when>2026-01-01T00:00:00Z</when><when>2026-01-01T00:00:10Z</when>
            <gx:coord>7.0 46.0 100</gx:coord><gx:coord>7.1 46.1 110</gx:coord></gx:Track></Placemark></kml>"""
        val t = KmlReader.read(StringReader(kml)).tracks.single().segments.single()
        assertEquals(2, t.size)
        assertEquals(110.0, t[1].elevationM!!, 1e-9)
    }

    @Test
    fun xxeAndEntityExpansionAreInert() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd"><!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;">]>
            <gpx><wpt lat="1" lon="2"><name>&xxe;</name></wpt></gpx>"""
        try {
            GpxReader.read(StringReader(xxe))
            fail("undeclared entity must be rejected")
        } catch (e: XmlFormatException) {
            assertTrue(e.message!!.contains("entity"))
        }
    }

    @Test
    fun malformedAndHostileInputFailsLoudly() {
        val cases = listOf(
            "", "not xml", "<gpx><wpt lat=1 lon=2></wpt></gpx>", "<gpx><wpt lat=\"1\"", "<kml>", "<!-- unterminated",
            "<gpx><name>&#xD800;</name></gpx>",
        )
        for (c in cases) {
            try {
                GpxReader.read(StringReader(c))
                fail("expected failure for: $c")
            } catch (_: XmlFormatException) {
            }
        }
    }

    @Test
    fun invalidCoordinatesAreSkippedNotZeroed() {
        val gpx = """<gpx><wpt lat="999" lon="2"/><wpt lat="abc" lon="2"/><wpt lat="1" lon="200"/><wpt lat="1" lon="2"><name>ok</name></wpt></gpx>"""
        assertEquals(listOf("ok"), GpxReader.read(StringReader(gpx)).waypoints.map { it.name })
    }

    @Test
    fun pointLimitIsEnforced() {
        val many = "<gpx>" + "<wpt lat=\"1\" lon=\"1\"/>".repeat(101) + "</gpx>"
        try {
            GpxReader.read(StringReader(many), maxPoints = 100)
            fail()
        } catch (_: XmlFormatException) {
        }
    }

    @Test
    fun streamingExportOfHundredThousandPointsUsesAWriter() {
        var chars = 0L
        val counting = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) { chars += len }
            override fun flush() {}
            override fun close() {}
        }
        val w = GpxWriter(counting)
        w.beginTrack("big")
        w.beginSegment()
        for (i in 0 until 100_000) w.trackPoint(TrackPoint(i * 1000L, LatLon(46.0 + i * 1e-6, 7.0), 1000.0))
        w.endSegment(); w.endTrack(); w.finish()
        assertTrue(chars > 5_000_000)
    }

    @Test
    fun commentsCdataAndPrefixedNames() {
        val gpx = """<?xml version="1.0"?><!-- a --- b --><gpx:gpx xmlns:gpx="x"><gpx:wpt lat="1" lon="2"><gpx:name><![CDATA[A & <B>]]></gpx:name></gpx:wpt></gpx:gpx>"""
        assertEquals("A & <B>", GpxReader.read(StringReader(gpx) as Reader).waypoints.single().name)
    }
}
