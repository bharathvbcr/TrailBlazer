package com.example.trailblazer.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.TrackEntity
import com.example.trailblazer.data.TrackPointEntity
import com.example.trailblazer.data.TrackRepository
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.io.GpxWriter
import com.trailblazer.core.track.DouglasPeucker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Writer

@RunWith(AndroidJUnit4::class)
class TrackRecorderTest {
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

    private fun fix(i: Int, metersNorth: Double, accuracy: Double? = 5.0) = Fix(
        Geo.destination(origin, 0.0, metersNorth), accuracy, 1000.0 + i, AltitudeDatum.SeaLevel, null, 2.0, null, now + i * 1000L, "gps",
    )

    @Test
    fun filtersInaccurateJitteryAndOutOfOrderFixes() = runBlocking {
        val r = TrackRecorder(db.tracks(), clock)
        r.openOrStart("T")
        assertTrue(r.offer(fix(0, 0.0)))
        assertFalse("accuracy worse than 30 m", r.offer(fix(1, 50.0, accuracy = 45.0)))
        assertFalse("no accuracy reported", r.offer(fix(2, 60.0, accuracy = null)))
        assertFalse("1 m jitter", r.offer(fix(3, 1.0)))
        assertFalse("large accuracy raises the jitter threshold", r.offer(fix(4, 10.0, accuracy = 28.0)))
        assertTrue(r.offer(fix(5, 20.0)))
        assertFalse("older than the last kept point", r.offer(fix(1, 40.0)))
        assertEquals(2, r.pointCount)
    }

    @Test
    fun batchesTenPointsPerWrite() = runBlocking {
        val r = TrackRecorder(db.tracks(), clock)
        val t = r.openOrStart("T")
        for (i in 0 until 9) r.offer(fix(i, i * 10.0))
        assertFalse(r.flushDue())
        assertEquals(-1, db.tracks().maxSeq(t.id))
        r.offer(fix(9, 90.0))
        assertTrue(r.flushDue())
        r.flush()
        assertEquals(9, db.tracks().maxSeq(t.id))
        assertEquals(90.0, db.tracks().get(t.id)!!.distanceM, 0.5)
        // Time-based flush: a single point is written after 5 s.
        r.offer(fix(10, 100.0))
        assertFalse(r.flushDue())
        now += 5_000
        assertTrue(r.flushDue())
    }

    @Test
    fun resumesTheOpenTrackAfterProcessDeath() = runBlocking {
        val a = TrackRecorder(db.tracks(), clock)
        val t = a.openOrStart("Morning")
        for (i in 0 until 25) a.offer(fix(i, i * 10.0))
        a.flush()
        // The process dies here: no finish, no state change. A new service instance starts.
        val b = TrackRecorder(db.tracks(), clock)
        val resumed = b.openOrStart("ignored")
        assertEquals(t.id, resumed.id)
        assertEquals("Morning", resumed.name)
        assertEquals(240.0, b.distanceM, 0.5)
        assertFalse("jitter check uses the restored last point", b.offer(fix(25, 241.0)))
        assertTrue(b.offer(fix(26, 250.0)))
        b.flush()
        assertEquals(25, db.tracks().maxSeq(t.id))
        b.setState(TrackState.Finished)
        assertEquals(null, db.tracks().open())
        assertTrue(db.tracks().get(t.id)!!.endedMs != null)
    }

    @Test
    fun pausingKeepsEverythingAndRejectsNewFixes() = runBlocking {
        val r = TrackRecorder(db.tracks(), clock)
        val t = r.openOrStart("T")
        r.offer(fix(0, 0.0)); r.offer(fix(1, 10.0))
        r.setState(TrackState.Paused)
        assertFalse(r.offer(fix(2, 20.0)))
        assertEquals(TrackState.Paused, db.tracks().get(t.id)!!.state)
        assertEquals(1, db.tracks().maxSeq(t.id))
    }

    @Test
    fun hundredThousandPointsStreamWithBoundedMemory() = runBlocking {
        val dao = db.tracks()
        val id = "big"
        dao.insert(TrackEntity(id, "Big", now, now + 100_000_000L, TrackState.Finished, 0.0, 0.0, 0.0, 0, null, 100_000))
        var p = origin
        (0 until 100_000).chunked(1_000).forEach { chunk ->
            dao.insertPoints(chunk.map { i ->
                p = Geo.destination(p, (i % 360).toDouble(), 5.0)
                TrackPointEntity(id, i, now + i * 1000L, p.lat, p.lon, 1000.0, 5.0, 1.0)
            })
        }
        var count = 0
        var chars = 0L
        val sink = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) { chars += len }
            override fun flush() {}
            override fun close() {}
        }
        val gpx = GpxWriter(sink)
        gpx.beginTrack("Big"); gpx.beginSegment()
        val sampled = ArrayList<LatLon>()
        TrackRepository(dao).forEachPoint(id) { tp ->
            gpx.trackPoint(tp)
            if (count % 50 == 0) sampled += tp.position
            count++
        }
        gpx.endSegment(); gpx.endTrack(); gpx.finish()
        assertEquals(100_000, count)
        assertTrue(chars > 5_000_000)
        assertTrue(DouglasPeucker.simplifyToMax(sampled, 2_000) { it }.size <= 2_000)
    }
}
