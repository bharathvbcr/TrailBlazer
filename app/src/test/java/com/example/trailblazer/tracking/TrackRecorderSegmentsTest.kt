package com.example.trailblazer.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.track.ElevationDatum
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pause/resume segments and the elevation datum in what the recorder stores and counts. */
@RunWith(AndroidJUnit4::class)
class TrackRecorderSegmentsTest {
    private lateinit var db: TrailDb
    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val origin = LatLon(46.0, 7.0)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrailDb::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun fix(from: LatLon, metersNorth: Double, atMs: Long, ele: Double? = 1000.0, datum: AltitudeDatum = AltitudeDatum.SeaLevel) =
        Fix(Geo.destination(from, 0.0, metersNorth), 5.0, ele, datum, null, 1.5, null, atMs, "gps")

    private suspend fun stored(id: String) = db.tracks().pointsAfter(id, -1, 1000)

    @Test
    fun resumingAPauseStartsANewSegmentAndTheGapIsNotCounted() = runBlocking {
        val r = TrackRecorder(db.tracks(), clock)
        val t = r.openOrStart("T")
        for (i in 0 until 5) assertTrue(r.offer(fix(origin, i * 10.0, now + i * 10_000L)))
        r.setState(TrackState.Paused)
        // An hour later, 5 km away (a lift): the user resumes recording.
        val far = Geo.destination(origin, 90.0, 5_000.0)
        now += 3_600_000L
        r.setState(TrackState.Recording)
        assertTrue(r.offer(fix(far, 0.0, now)))
        assertTrue(r.offer(fix(far, 10.0, now + 10_000L)))
        r.flush()

        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1), stored(t.id).map { it.segment })
        val row = db.tracks().get(t.id)!!
        assertEquals("distance is the two stretches only", 50.0, row.distanceM, 1.0)
        assertEquals("moving time is the 50 s walked, not the hour", 50_000L, row.movingMs)
    }

    @Test
    fun aPauseSurvivesTheProcessDyingWhilePaused() = runBlocking {
        val a = TrackRecorder(db.tracks(), clock)
        val t = a.openOrStart("T")
        for (i in 0 until 3) a.offer(fix(origin, i * 10.0, now + i * 10_000L))
        a.setState(TrackState.Paused)
        // A new service instance reattaches to the Paused row.
        val b = TrackRecorder(db.tracks(), clock)
        b.openOrStart("ignored")
        now += 600_000L
        assertTrue(b.offer(fix(Geo.destination(origin, 90.0, 2_000.0), 0.0, now)))
        b.flush()
        assertEquals(listOf(0, 0, 0, 1), stored(t.id).map { it.segment })
        assertEquals(20.0, db.tracks().get(t.id)!!.distanceM, 1.0)
    }

    @Test
    fun aRecordingCutShortByProcessDeathContinuesInTheSameSegment() = runBlocking {
        val a = TrackRecorder(db.tracks(), clock)
        val t = a.openOrStart("T")
        a.offer(fix(origin, 0.0, now))
        a.setState(TrackState.Paused)
        a.setState(TrackState.Recording)
        a.offer(fix(origin, 100.0, now + 10_000L))
        a.offer(fix(origin, 110.0, now + 20_000L))
        a.flush()
        // Killed while Recording: no pause, so no break (its gap is stitched, like Battery Saver's). The recorder must
        // carry on in the segment the stored points ended in (1), not fall back to 0.
        val b = TrackRecorder(db.tracks(), clock)
        b.openOrStart("ignored")
        assertTrue(b.offer(fix(origin, 120.0, now + 30_000L)))
        b.flush()
        assertEquals(listOf(0, 1, 1, 1), stored(t.id).map { it.segment })
    }

    @Test
    fun pausingBeforeAnyFixOrResumingTwiceDoesNotLeaveEmptySegments() = runBlocking {
        val r = TrackRecorder(db.tracks(), clock)
        val t = r.openOrStart("T")
        r.setState(TrackState.Paused)
        r.setState(TrackState.Recording)
        assertTrue(r.offer(fix(origin, 0.0, now)))
        r.setState(TrackState.Paused)
        r.setState(TrackState.Recording)
        r.setState(TrackState.Paused)
        r.setState(TrackState.Recording)
        assertTrue(r.offer(fix(origin, 100.0, now + 60_000L)))
        r.flush()
        assertEquals(listOf(0, 1), stored(t.id).map { it.segment })
    }

    @Test
    fun theDatumOfEachFixIsStoredAndAnAltitudelessFixHasNone() = runBlocking {
        val r = TrackRecorder(db.tracks(), clock)
        val t = r.openOrStart("T")
        r.offer(fix(origin, 0.0, now, ele = 100.0, datum = AltitudeDatum.SeaLevel))
        r.offer(fix(origin, 20.0, now + 10_000L, ele = 150.0, datum = AltitudeDatum.Ellipsoid))
        r.offer(fix(origin, 40.0, now + 20_000L, ele = null, datum = AltitudeDatum.Ellipsoid))
        r.flush()
        val points = stored(t.id)
        assertEquals(listOf(ElevationDatum.Msl, ElevationDatum.Ellipsoid, null), points.map { it.elevationDatum })
        assertEquals(listOf(100.0, 150.0, null), points.map { it.elevationM })
    }

    @Test
    fun aDatumSwitchMidTrackIsNotRecordedAsClimb() = runBlocking {
        val r = TrackRecorder(db.tracks(), clock)
        val t = r.openOrStart("T")
        // Flat walk. On API 34+ the geoid lookup works, then fails once (so one stretch is ellipsoid height, 47 m higher),
        // then works again.
        for (i in 0 until 4) r.offer(fix(origin, i * 20.0, now + i * 10_000L, ele = 1000.0, datum = AltitudeDatum.SeaLevel))
        for (i in 4 until 8) r.offer(fix(origin, i * 20.0, now + i * 10_000L, ele = 1047.0, datum = AltitudeDatum.Ellipsoid))
        for (i in 8 until 12) r.offer(fix(origin, i * 20.0, now + i * 10_000L, ele = 1000.0, datum = AltitudeDatum.SeaLevel))
        r.flush()
        val row = db.tracks().get(t.id)!!
        assertEquals(0.0, row.gainM, 0.0)
        assertEquals(0.0, row.lossM, 0.0)
    }
}
