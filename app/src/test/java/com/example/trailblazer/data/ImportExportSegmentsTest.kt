package com.example.trailblazer.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.track.ElevationDatum
import com.trailblazer.core.track.TrackPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Track segments and the elevation datum through GPX/KML import and export. */
@RunWith(AndroidJUnit4::class)
class ImportExportSegmentsTest {
    private lateinit var context: Context
    private lateinit var db: TrailDb
    private lateinit var tracksRepo: TrackRepository
    private lateinit var importExport: ImportExport
    private val clock = Clock { 1_700_000_000_000L }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TrailDb::class.java).allowMainThreadQueries().build()
        tracksRepo = TrackRepository(db.tracks())
        importExport = ImportExport(context.contentResolver, db, WaypointRepository(db.waypoints(), clock), TripRepository(db.trips(), clock), tracksRepo)
    }

    @After
    fun tearDown() = db.close()

    private fun tempFile(name: String, text: String = ""): Pair<Uri, File> {
        val file = File(context.cacheDir, name)
        file.writeText(text)
        return Uri.fromFile(file) to file
    }

    private suspend fun points(id: String): List<TrackPoint> = buildList { tracksRepo.forEachPoint(id) { add(it) } }

    /** Segment 1 starts an hour later and 11 km east of where segment 0 ends. */
    private val twoSegments = """<gpx><trk><name>Two parts</name>
        <trkseg>
            <trkpt lat="46.0000" lon="7.0"><ele>1000</ele><time>2026-01-01T10:00:00Z</time></trkpt>
            <trkpt lat="46.0001" lon="7.0"><ele>1001</ele><time>2026-01-01T10:00:10Z</time></trkpt>
            <trkpt lat="46.0002" lon="7.0"><ele>1002</ele><time>2026-01-01T10:00:20Z</time></trkpt>
        </trkseg>
        <trkseg>
            <trkpt lat="46.0000" lon="7.15"><ele>1500</ele><time>2026-01-01T11:00:20Z</time></trkpt>
            <trkpt lat="46.0001" lon="7.15"><ele>1501</ele><time>2026-01-01T11:00:30Z</time></trkpt>
            <trkpt lat="46.0002" lon="7.15"><ele>1502</ele><time>2026-01-01T11:00:40Z</time></trkpt>
        </trkseg></trk></gpx>"""

    @Test
    fun gpxTrksegBoundariesAreKeptAndTheBreakIsNotCounted() = runBlocking {
        val (uri, _) = tempFile("two.gpx", twoSegments)
        assertTrue(importExport.import(uri) is IoResult.Ok)
        val track = tracksRepo.all.first().single()
        assertEquals(listOf(0, 0, 0, 1, 1, 1), points(track.id).map { it.segment })
        assertEquals("two walks of about 22 m, not an 11 km line across the break", 44.0, track.distanceM, 3.0)
        assertEquals("the hour between the parts is not moving time", 40_000L, track.movingMs)
        assertEquals("climb restarts in segment 1 (1002 to 1500 is not climbed)", 0.0, track.gainM, 0.0)
        assertEquals(6, track.pointCount)
    }

    @Test
    fun segmentsAreNeverSortedAcrossEachOther() = runBlocking {
        // The file lists the later part first. Sorting every point by time would interleave the two; each stays whole,
        // in the order the file gives, and a segment is only sorted within itself.
        val gpx = """<gpx><trk><trkseg>
            <trkpt lat="46.2" lon="7.0"><time>2026-01-01T12:00:20Z</time></trkpt>
            <trkpt lat="46.1" lon="7.0"><time>2026-01-01T12:00:00Z</time></trkpt>
        </trkseg><trkseg>
            <trkpt lat="46.4" lon="7.0"><time>2026-01-01T09:00:10Z</time></trkpt>
            <trkpt lat="46.3" lon="7.0"><time>2026-01-01T09:00:00Z</time></trkpt>
        </trkseg></trk></gpx>"""
        assertTrue(importExport.import(tempFile("order.gpx", gpx).first) is IoResult.Ok)
        val track = tracksRepo.all.first().single()
        val pts = points(track.id)
        assertEquals(listOf(0, 0, 1, 1), pts.map { it.segment })
        assertEquals("within a segment: time order", listOf(46.1, 46.2, 46.3, 46.4), pts.map { it.position.lat })
        assertTrue(pts[0].epochMs < pts[1].epochMs && pts[2].epochMs < pts[3].epochMs)
        assertTrue("the second segment is earlier in time, and stays second", pts[2].epochMs < pts[1].epochMs)
        assertEquals("start is the earliest fix, end the latest", pts[2].epochMs, track.startedMs)
        assertEquals(pts[1].epochMs, track.endedMs)
    }

    @Test
    fun aSinglePointSegmentAndAnEmptyTrksegAreHandled() = runBlocking {
        val gpx = """<gpx><trk><trkseg></trkseg><trkseg>
            <trkpt lat="46.0" lon="7.0"><time>2026-01-01T10:00:00Z</time></trkpt>
        </trkseg><trkseg>
            <trkpt lat="46.1" lon="7.1"><time>2026-01-01T10:10:00Z</time></trkpt>
            <trkpt lat="46.1001" lon="7.1"><time>2026-01-01T10:10:10Z</time></trkpt>
        </trkseg></trk></gpx>"""
        assertTrue(importExport.import(tempFile("single.gpx", gpx).first) is IoResult.Ok)
        val track = tracksRepo.all.first().single()
        assertEquals("the empty trkseg is dropped and the rest renumbered", listOf(0, 1, 1), points(track.id).map { it.segment })
        assertEquals(11.0, track.distanceM, 1.0)
    }

    @Test
    fun aFileWithOnlyEmptySegmentsStoresNoTrack() = runBlocking {
        val gpx = """<gpx><wpt lat="46.0" lon="7.0"/><trk><trkseg></trkseg><trkseg/></trk></gpx>"""
        val result = importExport.import(tempFile("empty.gpx", gpx).first)
        assertTrue(result is IoResult.Ok)
        assertTrue(tracksRepo.all.first().isEmpty())
    }

    @Test
    fun kmlGxMultiTrackImportsAsSegments() = runBlocking {
        val kml = """<kml xmlns:gx="http://www.google.com/kml/ext/2.2"><Placemark><name>K</name><gx:MultiTrack>
            <gx:Track><when>2026-01-01T00:00:00Z</when><when>2026-01-01T00:00:10Z</when><gx:coord>7.0 46.0 100</gx:coord><gx:coord>7.0 46.0001 101</gx:coord></gx:Track>
            <gx:Track><when>2026-01-01T05:00:00Z</when><when>2026-01-01T05:00:10Z</when><gx:coord>7.5 46.5 400</gx:coord><gx:coord>7.5 46.5001 401</gx:coord></gx:Track>
        </gx:MultiTrack></Placemark></kml>"""
        assertTrue(importExport.import(tempFile("seg.kml", kml).first) is IoResult.Ok)
        val track = tracksRepo.all.first().single()
        assertEquals(listOf(0, 0, 1, 1), points(track.id).map { it.segment })
        assertEquals(22.0, track.distanceM, 2.0)
    }

    @Test
    fun importedElevationIsStoredAsSeaLevel() = runBlocking {
        assertTrue(importExport.import(tempFile("msl.gpx", twoSegments).first) is IoResult.Ok)
        val track = tracksRepo.all.first().single()
        assertTrue(points(track.id).all { it.datum == ElevationDatum.Msl })
        assertTrue(db.tracks().pointsAfter(track.id, -1, 10).all { it.elevationDatum == ElevationDatum.Msl })
    }

    @Test
    fun exportWritesOneTrksegPerSegmentAndRoundTrips() = runBlocking {
        assertTrue(importExport.import(tempFile("rt.gpx", twoSegments).first) is IoResult.Ok)
        val id = tracksRepo.all.first().single().id
        val (out, file) = tempFile("out.gpx")
        assertTrue(importExport.exportTrack(id, out, GeoFormat.Gpx) is IoResult.Ok)
        val xml = file.readText()
        assertEquals(2, Regex("<trkseg>").findAll(xml).count())
        assertEquals(2, Regex("</trkseg>").findAll(xml).count())
        // And importing the export again gives the same two segments.
        db.tracks().delete(id)
        assertTrue(importExport.import(out) is IoResult.Ok)
        val again = tracksRepo.all.first().single()
        assertEquals(listOf(0, 0, 0, 1, 1, 1), points(again.id).map { it.segment })
        assertEquals(1500.0, points(again.id)[3].elevationM!!, 0.1)
    }

    @Test
    fun exportWritesOneLineStringPerSegmentInKml() = runBlocking {
        assertTrue(importExport.import(tempFile("rt2.gpx", twoSegments).first) is IoResult.Ok)
        val id = tracksRepo.all.first().single().id
        val (out, file) = tempFile("out.kml")
        assertTrue(importExport.exportTrack(id, out, GeoFormat.Kml) is IoResult.Ok)
        assertEquals(2, Regex("<LineString>").findAll(file.readText()).count())
    }

    @Test
    fun exportNeverLabelsAnEllipsoidOrUnknownHeightAsSeaLevel() = runBlocking {
        val id = "mixed"
        db.tracks().insert(TrackEntity(id, "Mixed", 1_000, 5_000, TrackState.Finished, 0.0, 0.0, 0.0, 0, null, 3))
        db.tracks().insertPoints(
            listOf(
                TrackPointEntity(id, 0, 1_000, 46.0, 7.0, 1111.0, 5.0, null, 0, ElevationDatum.Msl),
                TrackPointEntity(id, 1, 2_000, 46.0001, 7.0, 2222.0, 5.0, null, 0, ElevationDatum.Ellipsoid),
                TrackPointEntity(id, 2, 3_000, 46.0002, 7.0, 3333.0, 5.0, null, 0, null),
            ),
        )
        for (format in GeoFormat.entries) {
            val (out, file) = tempFile("mixed.${format.extension}")
            assertTrue(importExport.exportTrack(id, out, format) is IoResult.Ok)
            val text = file.readText()
            assertTrue("${format.name}: sea-level height is written", text.contains("1111.0"))
            assertFalse("${format.name}: ellipsoid height must be left out", text.contains("2222"))
            assertFalse("${format.name}: unknown-datum height must be left out", text.contains("3333"))
        }
        // The heights are still stored, only not exported.
        assertEquals(listOf(1111.0, 2222.0, 3333.0), db.tracks().pointsAfter(id, -1, 10).map { it.elevationM })
        assertNull(db.tracks().pointsAfter(id, 1, 10).first().elevationDatum)
    }
}
