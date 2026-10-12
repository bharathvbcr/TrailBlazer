package com.example.trailblazer.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.track.TrackPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

@RunWith(AndroidJUnit4::class)
class ImportExportTest {
    private lateinit var context: Context
    private lateinit var db: TrailDb
    private lateinit var waypointsRepo: WaypointRepository
    private lateinit var tripsRepo: TripRepository
    private lateinit var tracksRepo: TrackRepository
    private lateinit var importExport: ImportExport
    private val clock = Clock { 1_700_000_000_000L }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TrailDb::class.java).allowMainThreadQueries().build()
        waypointsRepo = WaypointRepository(db.waypoints(), clock)
        tripsRepo = TripRepository(db.trips(), clock)
        tracksRepo = TrackRepository(db.tracks())
        importExport = ImportExport(context.contentResolver, db, waypointsRepo, tripsRepo, tracksRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun writeTempFile(name: String, bytes: ByteArray): Uri {
        val file = File(context.cacheDir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    @Test
    fun testUtf8WithBom() = runBlocking {
        val gpx = """<?xml version="1.0" encoding="UTF-8"?>
            <gpx><wpt lat="46.0" lon="7.0"><name>Summit UTF8</name></wpt></gpx>""".trimIndent()
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + gpx.toByteArray(Charsets.UTF_8)
        val uri = writeTempFile("utf8_bom.gpx", bytes)

        val result = importExport.import(uri)
        assertTrue(result is IoResult.Ok)
        assertEquals(1, (result as IoResult.Ok).value.waypoints)

        val wp = waypointsRepo.snapshot().single()
        assertEquals("Summit UTF8", wp.name)
        assertEquals(46.0, wp.position.lat, 1e-6)
        assertEquals(7.0, wp.position.lon, 1e-6)
    }

    @Test
    fun testUtf16LeWithBom() = runBlocking {
        val gpx = """<?xml version="1.0" encoding="UTF-16"?>
            <gpx><wpt lat="46.1" lon="7.1"><name>Summit UTF16LE</name></wpt></gpx>""".trimIndent()
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + gpx.toByteArray(Charsets.UTF_16LE)
        val uri = writeTempFile("utf16_le.gpx", bytes)

        val result = importExport.import(uri)
        assertTrue(result is IoResult.Ok)
        assertEquals(1, (result as IoResult.Ok).value.waypoints)

        val wp = waypointsRepo.snapshot().single()
        assertEquals("Summit UTF16LE", wp.name)
        assertEquals(46.1, wp.position.lat, 1e-6)
        assertEquals(7.1, wp.position.lon, 1e-6)
    }

    @Test
    fun testUtf16BeWithBom() = runBlocking {
        val gpx = """<?xml version="1.0" encoding="UTF-16"?>
            <gpx><wpt lat="46.2" lon="7.2"><name>Summit UTF16BE</name></wpt></gpx>""".trimIndent()
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + gpx.toByteArray(Charsets.UTF_16BE)
        val uri = writeTempFile("utf16_be.gpx", bytes)

        val result = importExport.import(uri)
        assertTrue(result is IoResult.Ok)
        assertEquals(1, (result as IoResult.Ok).value.waypoints)

        val wp = waypointsRepo.snapshot().single()
        assertEquals("Summit UTF16BE", wp.name)
        assertEquals(46.2, wp.position.lat, 1e-6)
        assertEquals(7.2, wp.position.lon, 1e-6)
    }

    @Test
    fun testLatin1XmlDeclarationPreservesCharacters() = runBlocking {
        val gpx = """<?xml version="1.0" encoding="ISO-8859-1"?>
            <gpx><wpt lat="45.92" lon="6.87"><name>Chamonix-Mont-Blanc été café</name></wpt></gpx>""".trimIndent()
        val bytes = gpx.toByteArray(Charset.forName("ISO-8859-1"))
        val uri = writeTempFile("latin1.gpx", bytes)

        val result = importExport.import(uri)
        assertTrue(result is IoResult.Ok)
        assertEquals(1, (result as IoResult.Ok).value.waypoints)

        val wp = waypointsRepo.snapshot().single()
        assertEquals("Chamonix-Mont-Blanc été café", wp.name)
    }

    @Test
    fun testKmlMultiGeometryImportsAllGeometries() = runBlocking {
        val kml = """<kml><Placemark><name>Trail &amp; Camps</name><MultiGeometry>
            <Point><coordinates>7.0,46.0,1000</coordinates></Point>
            <LineString><coordinates>7.0,46.0,1000 7.1,46.1,1200 7.2,46.2,1400</coordinates></LineString>
            <Point><coordinates>7.2,46.2,1400</coordinates></Point>
        </MultiGeometry></Placemark></kml>""".trimIndent()
        val uri = writeTempFile("multi_geom.kml", kml.toByteArray(Charsets.UTF_8))

        val result = importExport.import(uri)
        assertTrue(result is IoResult.Ok)
        val summary = (result as IoResult.Ok).value
        assertEquals(2, summary.waypoints)
        assertEquals(1, summary.trips)

        val wps = waypointsRepo.snapshot()
        assertEquals(2, wps.size)
        assertEquals(listOf("Trail & Camps", "Trail & Camps"), wps.map { it.name })

        val trips = tripsRepo.all.first()
        assertEquals(1, trips.size)
        assertEquals("Trail & Camps", trips.single().name)
        assertEquals(3, trips.single().stops.size)
    }

    @Test
    fun testKmlGxMultiTrackImportsAllSegments() = runBlocking {
        val kml = """<kml xmlns:gx="http://www.google.com/kml/ext/2.2"><Placemark><name>DoubleSegmentTrack</name><gx:MultiTrack>
            <gx:Track>
                <when>2026-01-01T00:00:00Z</when>
                <when>2026-01-01T00:01:00Z</when>
                <gx:coord>7.0 46.0 1000</gx:coord>
                <gx:coord>7.1 46.1 1100</gx:coord>
            </gx:Track>
            <gx:Track>
                <when>2026-01-01T00:02:00Z</when>
                <when>2026-01-01T00:03:00Z</when>
                <gx:coord>7.2 46.2 1200</gx:coord>
                <gx:coord>7.3 46.3 1300</gx:coord>
            </gx:Track>
        </gx:MultiTrack></Placemark></kml>""".trimIndent()
        val uri = writeTempFile("multi_track.kml", kml.toByteArray(Charsets.UTF_8))

        val result = importExport.import(uri)
        assertTrue(result is IoResult.Ok)
        val summary = (result as IoResult.Ok).value
        assertEquals(1, summary.tracks)

        val tracks = tracksRepo.all.first()
        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals("DoubleSegmentTrack", track.name)
        assertEquals(4, track.pointCount)

        val points = mutableListOf<TrackPoint>()
        tracksRepo.forEachPoint(track.id) { points.add(it) }
        assertEquals(4, points.size)
    }

    @Test
    fun testPartialFailureRollbackIsAllOrNothing() = runBlocking {
        // Document has both a waypoint and a track.
        val gpx = """<gpx>
            <wpt lat="46.0" lon="7.0"><name>Summit Prior</name></wpt>
            <trk><name>FailTrack</name><trkseg>
                <trkpt lat="46.0" lon="7.0"><time>2026-01-01T00:00:00Z</time></trkpt>
            </trkseg></trk>
        </gpx>""".trimIndent()
        val uri = writeTempFile("fail_test.gpx", gpx.toByteArray(Charsets.UTF_8))

        // Create a trigger that fails on inserting into tracks
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_track_insert BEFORE INSERT ON tracks BEGIN SELECT RAISE(FAIL, 'simulated disk failure'); END;"
        )

        val result = importExport.import(uri)
        assertTrue(result is IoResult.Failed)
        assertTrue((result as IoResult.Failed).message.contains("Database error"))

        // Waypoints must have been rolled back atomically
        val wps = waypointsRepo.snapshot()
        assertTrue(wps.isEmpty())
        val tracks = tracksRepo.all.first()
        assertTrue(tracks.isEmpty())
    }

    /** [assertTrue] with the condition first, so the failure message can sit last like everywhere else. */
    private fun check(condition: Boolean, message: String) = assertTrue(message, condition)

    private suspend fun assertNothingStored() {
        assertTrue(waypointsRepo.snapshot().isEmpty())
        assertTrue(tripsRepo.all.first().isEmpty())
        assertTrue(tracksRepo.all.first().isEmpty())
    }

    private val mixedGpx = """<gpx>
        <wpt lat="46.0" lon="7.0"><name>Summit Prior</name></wpt>
        <rte><name>Ridge</name><rtept lat="46.0" lon="7.0"/><rtept lat="46.1" lon="7.1"/></rte>
        <trk><name>Walk</name><trkseg>
            <trkpt lat="46.0" lon="7.0"><time>2026-01-01T00:00:00Z</time></trkpt>
            <trkpt lat="46.1" lon="7.1"><time>2026-01-01T00:01:00Z</time></trkpt>
        </trkseg></trk>
    </gpx>"""

    @Test
    fun testFailureWhileWritingTheTrackRollsBackWaypointsAndRoutesToo() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_point_insert BEFORE INSERT ON track_points BEGIN SELECT RAISE(ABORT, 'disk full'); END;"
        )
        val result = importExport.import(writeTempFile("mixed_fail_points.gpx", mixedGpx.toByteArray()))
        assertTrue(result is IoResult.Failed)
        check((result as IoResult.Failed).message.startsWith("Database error"), result.message)
        assertNothingStored()
    }

    @Test
    fun testFailureWhileWritingARouteRollsBackTheWaypointsBeforeIt() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stop_insert BEFORE INSERT ON stops BEGIN SELECT RAISE(ABORT, 'disk full'); END;"
        )
        val result = importExport.import(writeTempFile("mixed_fail_stops.gpx", mixedGpx.toByteArray()))
        assertTrue(result is IoResult.Failed)
        check((result as IoResult.Failed).message.startsWith("Database error"), result.message)
        assertNothingStored()
    }

    @Test
    fun testTheSameFileImportsFullyOnceTheFailureIsGone() = runBlocking {
        val result = importExport.import(writeTempFile("mixed_ok.gpx", mixedGpx.toByteArray()))
        assertTrue(result is IoResult.Ok)
        val summary = (result as IoResult.Ok).value
        assertEquals(1, summary.waypoints)
        assertEquals(1, summary.trips)
        assertEquals(1, summary.tracks)
    }

    @Test
    fun testUnsupportedDeclaredEncodingIsRefusedNotGarbled() = runBlocking {
        val gpx = """<?xml version="1.0" encoding="x-unknown"?><gpx><wpt lat="46.0" lon="7.0"><name>x</name></wpt></gpx>"""
        val result = importExport.import(writeTempFile("unknown.gpx", gpx.toByteArray()))
        assertTrue(result is IoResult.Failed)
        check((result as IoResult.Failed).message.contains("unsupported encoding 'x-unknown'"), result.message)
        assertNothingStored()
    }

    @Test
    fun testOtherDeclaredEncodingsThePlatformKnowsStillImport() = runBlocking {
        // These imported before encoding detection existed; an allowlist must not take them away.
        val cases = listOf(
            "KOI8-R" to "Москва",
            "Shift_JIS" to "富士山",
            "windows-1251" to "Київ",
            "GB18030" to "泰山",
            "Big5" to "玉山",
            "EUC-JP" to "筑波山",
        )
        for ((i, case) in cases.withIndex()) {
            val (encoding, name) = case
            val gpx = """<?xml version="1.0" encoding="$encoding"?><gpx><wpt lat="${40 + i}.0" lon="7.0"><name>$name</name></wpt></gpx>"""
            val result = importExport.import(writeTempFile("enc_$i.gpx", gpx.toByteArray(Charset.forName(encoding))))
            check(result is IoResult.Ok, "$encoding gave $result")
        }
        assertEquals(cases.map { it.second }.toSet(), waypointsRepo.snapshot().map { it.name }.toSet())
    }

    @Test
    fun testDeclaredUtf16ButStoredAsAsciiIsRefused() = runBlocking {
        val gpx = """<?xml version="1.0" encoding="UTF-16"?><gpx><wpt lat="46.0" lon="7.0"><name>x</name></wpt></gpx>"""
        val result = importExport.import(writeTempFile("liar16.gpx", gpx.toByteArray(Charsets.UTF_8)))
        assertTrue(result is IoResult.Failed)
        check((result as IoResult.Failed).message.contains("not stored in it"), result.message)
        assertNothingStored()
    }

    @Test
    fun testUtf16WithoutByteOrderMarkIsRecognisedFromItsMarkup() = runBlocking {
        val gpx = """<?xml version="1.0" encoding="UTF-16"?><gpx><wpt lat="46.3" lon="7.3"><name>Gipfel Ü</name></wpt></gpx>"""
        val result = importExport.import(writeTempFile("utf16_nobom.gpx", gpx.toByteArray(Charsets.UTF_16LE)))
        assertTrue(result is IoResult.Ok)
        assertEquals("Gipfel Ü", waypointsRepo.snapshot().single().name)
    }

    @Test
    fun testWindows1252EuroSignAndAsciiDeclarationsAreHonoured() = runBlocking {
        val gpx = """<?xml version="1.0" encoding="windows-1252"?><gpx><wpt lat="46.0" lon="7.0"><name>5€ café</name></wpt></gpx>"""
        val result = importExport.import(writeTempFile("cp1252.gpx", gpx.toByteArray(Charset.forName("windows-1252"))))
        assertTrue(result is IoResult.Ok)
        assertEquals("5€ café", waypointsRepo.snapshot().single().name)
    }

    @Test
    fun testKmlInLatin1KeepsItsAccents() = runBlocking {
        val kml = """<?xml version="1.0" encoding="iso-8859-1"?><kml><Placemark><name>Zürich Hütte</name><Point><coordinates>8.54,47.37</coordinates></Point></Placemark></kml>"""
        val result = importExport.import(writeTempFile("latin1.kml", kml.toByteArray(Charset.forName("ISO-8859-1"))))
        assertTrue(result is IoResult.Ok)
        assertEquals("Zürich Hütte", waypointsRepo.snapshot().single().name)
    }

    @Test
    fun testRouteReductionDoesNotCareWhereOnTheGlobeTheRouteIs() = runBlocking {
        // The same 301-point wiggle, once crossing the antimeridian and once far from it. Reducing a route to the stop
        // limit must keep the same points either way; without longitude wrap the dateline route loses different ones.
        val rnd = kotlin.random.Random(5)
        val lats = List(301) { rnd.nextDouble(-0.02, 0.02) }
        fun route(name: String, startLon: Double) = lats.withIndex().joinToString("") { (i, lat) ->
            val lon = (startLon + i * 0.01).let { if (it > 180.0) it - 360.0 else it }
            """<rtept lat="$lat" lon="$lon"/>"""
        }.let { """<gpx><rte><name>$name</name>$it</rte></gpx>""" }

        assertTrue(importExport.import(writeTempFile("dateline.gpx", route("Dateline", 178.5).toByteArray())) is IoResult.Ok)
        assertTrue(importExport.import(writeTempFile("plain.gpx", route("Plain", 100.0).toByteArray())) is IoResult.Ok)
        val trips = tripsRepo.all.first().associateBy { it.name }
        val dateline = trips.getValue("Dateline").stops.map { it.position.lat }
        val plain = trips.getValue("Plain").stops.map { it.position.lat }
        check(dateline.size == com.trailblazer.core.trip.TripRules.MAX_STOPS, "kept ${dateline.size} stops")
        assertEquals(plain, dateline)
    }

    @Test
    fun testOutOfOrderTimestampsAreSortedChronologically() = runBlocking {
        // Track points written out of order: 3000ms, 1000ms, 2000ms
        val gpx = """<gpx><trk><name>UnorderedTrack</name><trkseg>
            <trkpt lat="46.0" lon="7.0"><time>1970-01-01T00:00:03Z</time></trkpt>
            <trkpt lat="46.1" lon="7.1"><time>1970-01-01T00:00:01Z</time></trkpt>
            <trkpt lat="46.2" lon="7.2"><time>1970-01-01T00:00:02Z</time></trkpt>
        </trkseg></trk></gpx>""".trimIndent()
        val uri = writeTempFile("unordered.gpx", gpx.toByteArray(Charsets.UTF_8))

        val result = importExport.import(uri)
        assertTrue(result is IoResult.Ok)

        val track = tracksRepo.all.first().single()
        assertEquals("UnorderedTrack", track.name)
        assertEquals(1000L, track.startedMs)
        assertEquals(3000L, track.endedMs)

        val points = mutableListOf<TrackPoint>()
        tracksRepo.forEachPoint(track.id) { points.add(it) }
        assertEquals(3, points.size)
        assertEquals(1000L, points[0].epochMs)
        assertEquals(46.1, points[0].position.lat, 1e-6)
        assertEquals(2000L, points[1].epochMs)
        assertEquals(46.2, points[1].position.lat, 1e-6)
        assertEquals(3000L, points[2].epochMs)
        assertEquals(46.0, points[2].position.lat, 1e-6)
    }
}
