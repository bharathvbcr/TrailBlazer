package com.trailblazer.core.io

import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.time.Iso8601
import com.trailblazer.core.track.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun kmlMultiGeometryKeepsAllGeometries() {
        val kml = """<kml><Placemark><name>Multi</name><description>Mixed</description><MultiGeometry>
            <Point><coordinates>10.0,20.0,100</coordinates></Point>
            <LineString><coordinates>10.0,20.0,100 11.0,21.0,200</coordinates></LineString>
            <Point><coordinates>12.0,22.0,300</coordinates></Point>
        </MultiGeometry></Placemark></kml>"""
        val doc = KmlReader.read(StringReader(kml))
        assertEquals(2, doc.waypoints.size)
        assertEquals("Multi", doc.waypoints[0].name)
        assertEquals(20.0, doc.waypoints[0].position.lat, 1e-7)
        assertEquals(10.0, doc.waypoints[0].position.lon, 1e-7)
        assertEquals(100.0, doc.waypoints[0].elevationM!!, 1e-9)

        assertEquals("Multi", doc.waypoints[1].name)
        assertEquals(22.0, doc.waypoints[1].position.lat, 1e-7)
        assertEquals(12.0, doc.waypoints[1].position.lon, 1e-7)

        assertEquals(1, doc.routes.size)
        assertEquals("Multi", doc.routes.single().name)
        assertEquals(2, doc.routes.single().points.size)
    }

    @Test
    fun kmlGxMultiTrackKeepsAllSegments() {
        val kml = """<kml xmlns:gx="http://www.google.com/kml/ext/2.2"><Placemark><name>MultiTrackHike</name><gx:MultiTrack>
            <gx:Track>
                <when>2026-01-01T00:00:00Z</when><when>2026-01-01T00:01:00Z</when>
                <gx:coord>7.0 46.0 100</gx:coord><gx:coord>7.1 46.1 110</gx:coord>
            </gx:Track>
            <gx:Track>
                <when>2026-01-01T01:00:00Z</when><when>2026-01-01T01:01:00Z</when>
                <gx:coord>7.2 46.2 120</gx:coord><gx:coord>7.3 46.3 130</gx:coord>
            </gx:Track>
        </gx:MultiTrack></Placemark></kml>"""
        val doc = KmlReader.read(StringReader(kml))
        val trk = doc.tracks.single()
        assertEquals("MultiTrackHike", trk.name)
        assertEquals(2, trk.segments.size)
        assertEquals(2, trk.segments[0].size)
        assertEquals(2, trk.segments[1].size)
        assertEquals(130.0, trk.segments[1][1].elevationM!!, 1e-9)
    }

    @Test
    fun kmlLargeLineStringExceedingOneMillionCharsSucceeds() {
        val sb = StringBuilder()
        sb.append("<kml><Placemark><name>BigLine</name><LineString><coordinates>\n")
        // 40_000 points with ~30 chars each = ~1.2M chars, which exceeded the old 1_000_000 MAX_TEXT limit
        for (i in 0 until 40_000) {
            sb.append("7.123456,46.123456,1234.5\n")
        }
        sb.append("</coordinates></LineString></Placemark></kml>")
        val doc = KmlReader.read(StringReader(sb.toString()))
        assertEquals(1, doc.routes.size)
        assertEquals(40_000, doc.routes.single().points.size)
    }

    private fun lineStringKml(tuples: Int, perTuple: (Int) -> String = { "7.0,46.0,1.0\n" }): String = buildString {
        append("<kml><Placemark><name>Line</name><LineString><coordinates>\n")
        for (i in 0 until tuples) append(perTuple(i))
        append("</coordinates></LineString></Placemark></kml>")
    }

    @Test
    fun kmlCoordinateListLongerThanTheOldTextCapIsStreamed() {
        // 1_000 points, each followed by 5_000 spaces: five million characters in one <coordinates> element, which
        // the old single-event reader refused beyond one million. (GeoFormatsStreamingJvmTest goes past 40 million.)
        val padding = " ".repeat(5_000)
        val doc = KmlReader.read(StringReader(lineStringKml(1_000) { "7.0,46.0,1.0$padding" }))
        assertEquals(1_000, doc.routes.single().points.size)
    }

    @Test
    fun kmlTuplesSplitAcrossTextChunksAreNotCorrupted() {
        val n = 20_000 // about 400 kB of text: many 32 kB chunks, with tuples falling on the boundaries
        val doc = KmlReader.read(StringReader(lineStringKml(n) { "${7.0 + (it % 1000) * 0.001},${46.0 + (it % 90) * 0.01},${it % 500}\n" }))
        val pts = doc.routes.single().points
        assertEquals(n, pts.size)
        for (i in 0 until n) {
            assertEquals(7.0 + (i % 1000) * 0.001, pts[i].position.lon, 1e-9, "lon of point $i")
            assertEquals(46.0 + (i % 90) * 0.01, pts[i].position.lat, 1e-9, "lat of point $i")
            assertEquals((i % 500).toDouble(), pts[i].elevationM!!, 1e-9, "ele of point $i")
        }
    }

    @Test
    fun kmlPointLimitIsExactAndFailsClosedPastIt() {
        assertEquals(1_000, KmlReader.read(StringReader(lineStringKml(1_000)), maxPoints = 1_000).routes.single().points.size)
        val e = assertFailsWith<XmlFormatException> { KmlReader.read(StringReader(lineStringKml(1_001)), maxPoints = 1_000) }
        assertTrue(e.message!!.contains("more than 1000 points"), e.message)
        // The limit counts the whole document, not each geometry.
        val twoLines = "<kml><Placemark><MultiGeometry>" +
            List(2) { "<LineString><coordinates>" + "7.0,46.0\n".repeat(600) + "</coordinates></LineString>" }.joinToString("") +
            "</MultiGeometry></Placemark></kml>"
        assertFailsWith<XmlFormatException> { KmlReader.read(StringReader(twoLines), maxPoints = 1_000) }
        assertEquals(1_200, KmlReader.read(StringReader(twoLines), maxPoints = 1_200).routes.sumOf { it.points.size })
    }

    @Test
    fun kmlCoordinateLongerThanAnyRealTupleIsRefused() {
        val kml = "<kml><Placemark><LineString><coordinates>" + "7".repeat(300) + "</coordinates></LineString></Placemark></kml>"
        assertFailsWith<XmlFormatException> { KmlReader.read(StringReader(kml)) }
    }

    @Test
    fun kmlPointTakesOnlyItsFirstCoordinateAndOnlyValidOnesCount() {
        val kml = """<kml><Placemark><name>P</name><Point><coordinates> 7.0,46.0,5 9.0,47.0 </coordinates></Point></Placemark>
            <Placemark><name>Bad</name><Point><coordinates>999,999 7.0,46.0</coordinates></Point></Placemark></kml>"""
        val doc = KmlReader.read(StringReader(kml), maxPoints = 1)
        assertEquals(listOf("P"), doc.waypoints.map { it.name })
        assertEquals(46.0, doc.waypoints.single().position.lat, 1e-9)
    }

    @Test
    fun kmlTimeStampWhenIsNotATrackTime() {
        val kml = """<kml xmlns:gx="http://www.google.com/kml/ext/2.2"><Placemark><name>T</name>
            <TimeStamp><when>2020-06-01T00:00:00Z</when></TimeStamp>
            <gx:Track>
                <when>2026-01-01T00:00:00Z</when><when>2026-01-01T00:01:00Z</when>
                <gx:coord>7.0 46.0 100</gx:coord><gx:coord>7.1 46.1 110</gx:coord>
            </gx:Track></Placemark></kml>"""
        val pts = KmlReader.read(StringReader(kml)).tracks.single().segments.single()
        assertEquals(2, pts.size)
        assertEquals(Iso8601.parse("2026-01-01T00:00:00Z"), pts[0].epochMs)
        assertEquals(Iso8601.parse("2026-01-01T00:01:00Z"), pts[1].epochMs)
        assertEquals(46.1, pts[1].position.lat, 1e-9)
    }

    @Test
    fun kmlPolygonRingIsSkippedAndDoesNotLeakIntoALaterPoint() {
        val kml = """<kml><Placemark><Polygon><outerBoundaryIs><LinearRing><coordinates>1,1 2,2 3,3 1,1</coordinates></LinearRing></outerBoundaryIs></Polygon>
            <Point/><LineString/></Placemark></kml>"""
        val doc = KmlReader.read(StringReader(kml))
        assertTrue(doc.waypoints.isEmpty() && doc.routes.isEmpty(), "got $doc")
    }

    @Test
    fun textAroundTheChunkBoundaryKeepsEntitiesSurrogatesAndCdataIntact() {
        val chunk = XmlPull.TEXT_CHUNK
        fun gpxName(xml: String) = GpxReader.read(StringReader("<gpx><wpt lat=\"1\" lon=\"2\"><name>$xml</name></wpt></gpx>")).waypoints.single().name
        for (pad in chunk - 8..chunk + 3) {
            val a = "a".repeat(pad)
            assertEquals("$a&<>\"'z", gpxName("$a&amp;&lt;&gt;&quot;&apos;z"), "entities after $pad chars")
            assertEquals("${a}😀z", gpxName("${a}😀z"), "emoji after $pad chars")
            assertEquals("${a}😀", gpxName("$a&#x1F600;"), "char reference after $pad chars")
            val x = "x".repeat(pad)
            assertEquals("$x]", gpxName("<![CDATA[$x]]]>"), "CDATA after $pad chars")
            assertEquals("$x]]>z", gpxName("<![CDATA[$x]]]]><![CDATA[>z]]>"), "split terminator after $pad chars")
        }
        assertNull(gpxName("<![CDATA[]]>"))
    }

    @Test
    fun overlongDescriptionIsCutNotBufferedWithoutBound() {
        val kml = "<kml><Placemark><name>D</name><description><![CDATA[" + "d".repeat(2_500_000) + "]]></description>" +
            "<Point><coordinates>7,46</coordinates></Point></Placemark></kml>"
        val wp = KmlReader.read(StringReader(kml)).waypoints.single()
        assertEquals(XmlPull.MAX_TEXT, wp.description!!.length)
        assertEquals("D", wp.name)
    }

    @Test
    fun exportOfAnAbsurdElevationIsAPlainDecimalThatReadsBack() {
        val w = StringWriter()
        GpxWriter(w).apply { waypoint(NamedPoint(LatLon(1.0, 2.0), "x", elevationM = 1e25)); finish() }
        val xml = w.toString()
        assertTrue(xml.contains("<ele>10000000000000000000000000.0</ele>"), xml)
        assertFalse(xml.contains("E25"), xml)
        assertEquals(100_000.0, GpxReader.read(StringReader(xml)).waypoints.single().elevationM!!, 1e-9)
    }

    @Test
    fun elevationClampedOnImport() {
        val gpx = """<gpx><wpt lat="1" lon="2"><ele>1e25</ele></wpt><wpt lat="3" lon="4"><ele>-9999999</ele></wpt></gpx>"""
        val doc = GpxReader.read(StringReader(gpx))
        assertEquals(100_000.0, doc.waypoints[0].elevationM!!, 1e-9)
        assertEquals(-11_000.0, doc.waypoints[1].elevationM!!, 1e-9)

        val kml = """<kml><Placemark><Point><coordinates>2.0,1.0,1e25</coordinates></Point></Placemark></kml>"""
        val kmlDoc = KmlReader.read(StringReader(kml))
        assertEquals(100_000.0, kmlDoc.waypoints[0].elevationM!!, 1e-9)
    }
}
