package com.trailblazer.core

import com.trailblazer.core.astro.RiseSetFinder
import com.trailblazer.core.atmo.AirDensity
import com.trailblazer.core.atmo.BoilingPoint
import com.trailblazer.core.atmo.DensityAltitude
import com.trailblazer.core.atmo.DewPoint
import com.trailblazer.core.atmo.Isa
import com.trailblazer.core.geo.CoordinateFormat
import com.trailblazer.core.geo.CoordinateParse
import com.trailblazer.core.geo.CoordinateParser
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.intents.MapLinks
import com.trailblazer.core.intents.TravelMode
import com.trailblazer.core.io.GpxReader
import com.trailblazer.core.io.KmlReader
import com.trailblazer.core.io.XmlFormatException
import com.trailblazer.core.math.CircularLowPass
import com.trailblazer.core.math.MedianFilter
import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.math.cardinal16
import com.trailblazer.core.math.mod360
import com.trailblazer.core.sos.Morse
import com.trailblazer.core.time.Iso8601
import com.trailblazer.core.weather.PressureSample
import com.trailblazer.core.weather.PressureTrend
import com.trailblazer.core.weather.TrendResult
import com.trailblazer.core.weather.Zambretti
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader
import kotlin.random.Random

/**
 * Adversarial inputs for every entry point that takes untrusted or extreme data: random and hostile strings for the
 * parsers, hostile XML for the file readers, NaN / infinity / boundary numbers for the maths. The rule under test is
 * totality: a result, a null, or the documented exception — never a crash, a hang, NaN or a value out of range.
 */
class StressTest {
    private val rnd = Random(20260929)
    private val hostileStrings: List<String> = listOf(
        "", " ", "\u0000", "NaN, NaN", "Infinity,-Infinity", "1e400, 5", "-0,-0", "90.0000001, 0", "0, 180.0000001",
        "91 0", "46°33'29.5\"N 7°50'60\"E", "46°60'N 7°E", "geo:", "geo:,", "geo:0,0?q=", "geo:NaN,1", "https://",
        "https://www.google.com/maps/@", "https://www.google.com/maps/dir///", "https://maps.apple.com/?ll=,", "#map=/",
        "https://www.openstreetmap.org/#map=99/1e999/1", "😀, 😀", "٤٦٫٥, ٧٫٨", "46,5 7,8", "46.5,,7.8", "--46.5, 7.8",
        "1".repeat(100_000), "1,".repeat(50_000), "@".repeat(10_000) + "1,2", "°".repeat(10_000), "0.".repeat(20_000),
        "https://www.google.com/maps/dir/" + "1,1/".repeat(5_000),
    )

    private fun randomString(len: Int): String {
        val alphabet = "0123456789.,-+ °'\"′″NSEWnsew:/?&=#@abcgeoqlmapsdir \t\n"
        return buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }

    private fun <T> withinMs(limitMs: Long, what: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$what took $ms ms (limit $limitMs)", ms <= limitMs)
        return r
    }

    // ---------------------------------------------------------------- parsers

    @Test
    fun coordinateParserIsTotalFastAndOnlyReturnsValidPlaces() {
        val inputs = hostileStrings + List(20_000) { randomString(rnd.nextInt(0, 60)) }
        for (s in inputs) {
            val r = withinMs(500, "parse(${s.take(40)}…${s.length})") { CoordinateParser.parse(s) }
            if (r is CoordinateParse.Found) {
                assertTrue(r.places.isNotEmpty())
                for (p in r.places) assertTrue("$s -> ${p.position}", p.position.lat in -90.0..90.0 && p.position.lon in -180.0..180.0)
            }
        }
    }

    @Test
    fun iso8601IsTotalAndRoundTrips() {
        for (s in hostileStrings + listOf("2026-02-29T00:00:00Z", "2028-02-29T24:00:00Z", "0000-01-01", "9999-12-31T23:59:60Z", "2026-13-01", "2026-01-01T00:00:00+99:00", "2026-01-01T00:00:00+18:00", "2026-01-01T00:00:00.123456789Z") + List(5_000) { randomString(rnd.nextInt(0, 40)) }) {
            Iso8601.parse(s)
        }
        assertNull("not a leap year", Iso8601.parse("2026-02-29T00:00:00Z"))
        assertNull(Iso8601.parse("2026-01-01T24:00:00Z"))
        repeat(5_000) {
            val ms = rnd.nextLong(-5_000_000_000_000L, 5_000_000_000_000L)
            assertEquals(ms, Iso8601.parse(Iso8601.format(ms)))
        }
    }

    @Test
    fun gpxAndKmlReadersSurviveHostileXml() {
        val docs = listOf(
            "", "<", "<gpx", "<gpx>", "<gpx><wpt lat=\"NaN\" lon=\"1\"/></gpx>", "<gpx><wpt lat=\"1e999\" lon=\"1\"/></gpx>",
            "<gpx><wpt lat=\"91\" lon=\"1\"/><wpt lat=\"1\" lon=\"181\"/></gpx>", "<gpx><wpt/></gpx>", "<gpx><trk><trkseg><trkpt lat='1' lon='2'><ele>abc</ele><time>never</time></trkpt></trkseg></trk></gpx>",
            "<?xml version='1.0'?><!DOCTYPE gpx [<!ENTITY a \"" + "x".repeat(1000) + "\"><!ENTITY b \"&a;&a;&a;&a;&a;\">]><gpx><wpt lat='1' lon='2'><name>&b;&b;&b;</name></wpt></gpx>",
            "<gpx>" + "<a>".repeat(200_000) + "</gpx>", "<gpx " + "a='1' ".repeat(100) + "/>", "<gpx><wpt lat='1' lon='2'><name>" + "x".repeat(2_000_000) + "</name></wpt></gpx>",
            "<kml><Placemark><Point><coordinates>1,2,3,4,5</coordinates></Point></Placemark></kml>",
            "<kml><Placemark><LineString><coordinates>" + "1,2 ".repeat(1000) + "nan,nan 1e999,0 ,,, </coordinates></LineString></Placemark></kml>",
            "<kml><![CDATA[" + "]".repeat(10_000) + "</kml>", "﻿<gpx></gpx>", "<gpx>&#0;&#xFFFFFFFF;&#99999999999;&unknown;</gpx>",
        ) + List(3_000) { buildString { repeat(rnd.nextInt(0, 400)) { append("<>/='\"&;!?[]gpxkmltrkptwlaon1.2 -"[rnd.nextInt(33)]) } } }
        for (d in docs) {
            for ((name, read) in listOf("gpx" to { GpxReader.read(StringReader(d)) }, "kml" to { KmlReader.read(StringReader(d)) })) {
                try {
                    val doc = withinMs(3_000, "$name ${d.take(30)}") { read() }
                    val all = doc.waypoints.map { it.position } + doc.routes.flatMap { r -> r.points.map { it.position } } +
                        doc.tracks.flatMap { t -> t.segments.flatten().map { it.position } }
                    assertTrue(all.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 })
                } catch (_: XmlFormatException) {
                    // Documented rejection.
                } catch (e: Throwable) {
                    fail("$name threw ${e::class.simpleName} on ${d.take(60)}: ${e.message}")
                }
            }
        }
    }

    @Test
    fun readersEnforceThePointLimit() {
        val doc = "<gpx><trk><trkseg>" + "<trkpt lat='1' lon='2'/>".repeat(1_001) + "</trkseg></trk></gpx>"
        try {
            GpxReader.read(StringReader(doc), maxPoints = 1_000)
            fail("expected the point limit to stop the reader")
        } catch (_: XmlFormatException) {
        }
    }

    // ---------------------------------------------------------------- maths

    private val hostileNumbers = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0, Double.MIN_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, 1e-300, -1e300, 360.0, -360.0, 720.000001, 1e15)

    @Test
    fun angleHelpersNeverLeaveTheirRangeForFiniteInput() {
        for (a in hostileNumbers.filter { it.isFinite() } + List(10_000) { rnd.nextDouble(-1e6, 1e6) }) {
            val m = mod360(a)
            assertTrue("mod360($a)=$m", m >= 0.0 && m < 360.0)
            for (b in listOf(0.0, 179.9, 180.0, 359.999, -540.0)) {
                val d = angleDiff(a, b)
                assertTrue("angleDiff($a,$b)=$d", d > -180.0 && d <= 180.0)
            }
            assertTrue(cardinal16(a).isNotEmpty())
        }
    }

    /** One bad sample (NaN from a glitching sensor) must not poison the filter for the rest of the session. */
    @Test
    fun circularLowPassRecoversFromNonFiniteSamples() {
        val f = CircularLowPass(0.25)
        f.update(10.0)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull("a bad sample yields no heading, not a made-up one", f.update(bad))
        }
        val next = f.update(20.0)
        assertTrue("filter poisoned: $next", next != null && next.isFinite() && next in 10.0..20.0)
        assertNull("an unprimed filter has nothing to report", CircularLowPass(0.5).update(Double.NaN))
    }

    @Test
    fun medianFilterIgnoresNonFiniteSamples() {
        val f = MedianFilter(5)
        assertNull(f.update(Double.NaN))
        assertEquals(1000.0, f.update(1000.0)!!, 0.0)
        assertEquals(1000.0, f.update(Double.POSITIVE_INFINITY)!!, 0.0)
    }

    @Test
    fun dmsRejectsNonFiniteAndCarriesRounding() {
        for (n in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull("deg=$n", Dms.toDecimal(n, 0.0, 0.0, false))
            assertNull("min=$n", Dms.toDecimal(1.0, n, 0.0, false))
            assertNull("sec=$n", Dms.toDecimal(1.0, 0.0, n, false))
        }
        for (v in listOf(46.99999999, -0.00000001, 179.99999999, 89.9999999, 0.0)) {
            for (fmt in CoordinateFormat.entries) {
                val s = Dms.formatAxis(v, true, fmt)
                assertTrue("$v $fmt -> $s", !s.contains("60″") && !s.contains("60.0″") && !s.contains("60′") && !s.contains("60.000′"))
            }
        }
    }

    @Test
    fun geodesyIsTotalForEveryValidPair() {
        val specials = listOf(LatLon(90.0, 0.0), LatLon(-90.0, 0.0), LatLon(0.0, 180.0), LatLon(0.0, -180.0), LatLon(0.0, 0.0), LatLon(45.0, 179.9999))
        val pts = specials + List(2_000) { LatLon(rnd.nextDouble(-90.0, 90.0), rnd.nextDouble(-180.0, 180.0)) }
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i * 7 + 3) % pts.size]
            val d = Geo.distanceM(a, b)
            assertTrue("$a $b $d", d.isFinite() && d >= 0 && d <= 20_015_100.0)
            val br = Geo.initialBearing(a, b)
            assertTrue(br.isFinite() && br >= 0 && br < 360)
            assertTrue(Geo.crossTrackM(a, a, b).isFinite())
            assertTrue(Geo.crossTrackM(b, a, a).isFinite())
            for (dist in listOf(0.0, 1.0, 1e7, 4e7, 1e9)) {
                val dest = Geo.destination(a, br, dist)
                assertTrue("$a $dist $dest", dest.lat in -90.0..90.0 && dest.lon in -180.0..180.0)
            }
        }
        assertEquals(0.0, Geo.distanceM(pts[0], pts[0]), 0.0)
    }

    @Test
    fun atmosphereReturnsNullNotNonsenseForImpossibleInput() {
        for (p in hostileNumbers + listOf(-1.0, 0.0, 1e-9, 5000.0)) {
            val alt = Isa.altitudeM(p)
            assertTrue("altitude($p)=$alt", alt == null || alt.isFinite())
            val q = Isa.qnhHpa(p, 1000.0)
            assertTrue("qnh($p)=$q", q == null || (q.isFinite() && q > 0))
            val bp = BoilingPoint.celsius(p)
            assertTrue("boil($p)=$bp", bp == null || bp.isFinite())
            for (t in listOf(null, Double.NaN, -273.15, -300.0, 20.0, 1e9)) {
                val da = DensityAltitude.meters(p, t)
                assertTrue("DA($p,$t)=$da", da == null || da.isFinite())
                val rho = AirDensity.kgPerM3(p, t)
                assertTrue("rho($p,$t)=$rho", rho == null || (rho.isFinite() && rho > 0))
            }
        }
        for (t in hostileNumbers) for (rh in listOf(Double.NaN, -1.0, 0.0, 50.0, 100.0, 101.0)) {
            val dp = DewPoint.celsius(t, rh)
            assertTrue("dew($t,$rh)=$dp", dp == null || dp.isFinite())
        }
    }

    @Test
    fun pressureTrendIsTotalForMessyHistories() {
        val now = 1_800_000_000_000L
        val histories = listOf(
            emptyList(),
            List(20) { PressureSample(now - it * 600_000L, 1013.0, null) },
            List(20) { PressureSample(now, 1013.0 + it, 100.0) }, // all at the same instant
            List(20) { PressureSample(now - (19 - it) * 600_000L, 1013.0, null) }.shuffled(rnd), // out of order
            List(20) { PressureSample(now - it * 600_000L, if (it % 3 == 0) Double.NaN else 1000.0 + it, if (it % 2 == 0) Double.NaN else 50.0) },
            List(20) { PressureSample(now + it * 600_000L, 1010.0, 0.0) }, // future
            List(20) { PressureSample(now - it * 600_000L, 1e9, 1e9) },
        )
        for (h in histories) {
            when (val r = PressureTrend.compute(h, now)) {
                is TrendResult.Trend -> assertTrue("$h -> $r", r.hpaPer3h.isFinite())
                is TrendResult.Insufficient -> Unit
            }
        }
        for (p in hostileNumbers) for (t in hostileNumbers) Zambretti.forecast(p, t)
    }

    @Test
    fun riseSetFinderIsBoundedForHostileFunctions() {
        val day = 86_400_000L
        withinMs(2_000, "NaN function") { RiseSetFinder.crossings(0, day) { Double.NaN } }
        withinMs(2_000, "noisy function") { RiseSetFinder.crossings(0, day, stepMs = 60_000) { if (it / 1000 % 2 == 0L) 1.0 else -1.0 } }
        withinMs(2_000, "step larger than window") { RiseSetFinder.crossings(0, 1_000, stepMs = day) { it - 500.0 } }
        withinMs(2_000, "window near Long.MAX") { RiseSetFinder.crossings(Long.MAX_VALUE - 10 * day, Long.MAX_VALUE - day) { 1.0 } }
    }

    @Test
    fun mapLinksEncodeHostileLabels() {
        val p = LatLon(46.5, 7.8)
        for (label in listOf("A & B", "#hash", "a\nb", "100%", "?q=evil", "😀", "\u0000", "x".repeat(10_000), "")) {
            val g = MapLinks.geo(p, label)
            assertTrue("geo link leaks raw characters: $g", g.none { it == '\n' || it == '#' || it == ' ' || it == '&' && !g.startsWith("geo:") })
            assertTrue(g.startsWith("geo:46.5"))
        }
        assertTrue(MapLinks.googleDirections(emptyList(), TravelMode.Driving).isEmpty())
        assertTrue(MapLinks.googleDirections(listOf(p), TravelMode.Driving).size <= 1)
        val many = List(100) { LatLon(it * 0.5 - 25, it * 1.0 - 50) }
        val links = MapLinks.googleDirections(many, TravelMode.Walking)
        assertTrue(links.isNotEmpty() && links.all { it.length < 8_000 })
    }

    @Test
    fun morseHandlesAnyText() {
        for (s in hostileStrings.take(12) + listOf("sos", "SOS 123", "ÄÖÜ", "😀")) {
            Morse.code(s)
            Morse.timeline(s).forEach { assertTrue(it.durationMs > 0) }
        }
        assertTrue(Morse.timeline("").isEmpty() || Morse.timeline("").all { it.durationMs > 0 })
    }
}
