package com.example.trailblazer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.Trip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripRepositoryTest {
    private lateinit var db: TrailDb
    private var now = 1_700_000_000_000L
    private lateinit var repo: TripRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrailDb::class.java).allowMainThreadQueries().build()
        repo = TripRepository(db.trips(), Clock { now })
    }

    @After
    fun tearDown() = db.close()

    private fun trip(name: String, vararg stops: String) =
        Trip("t1", name, stops.mapIndexed { i, s -> Stop("s$i", s, if (i == 0) StopKind.Start else StopKind.Visit, LatLon(46.0 + i, 7.0)) })

    @Test
    fun createdMsSurvivesEveryLaterSave() = runBlocking {
        repo.save(trip("Alps", "A", "B"))
        val created = now
        assertEquals(created, db.trips().createdMs("t1"))

        now += 3_600_000L
        repo.save(trip("Alps, renamed", "A", "B", "C"))
        now += 3_600_000L
        repo.save(trip("Alps, renamed again", "A", "C"))

        assertEquals("created is when the trip was first saved", created, db.trips().createdMs("t1"))
        val stored = db.trips().observe("t1").first()!!
        assertEquals("Alps, renamed again", stored.trip.name)
        assertEquals("updated moves with every save", now, stored.trip.updatedMs)
        assertEquals(2, stored.stops.size)
    }

    @Test
    fun tripsAreDatedIndependently() = runBlocking {
        repo.save(trip("One", "A", "B"))
        val first = now
        now += 1_000L
        repo.save(Trip("t2", "Two", trip("x", "A", "B").stops.map { it.copy(id = "other-${it.id}") }))
        now += 1_000L
        repo.save(trip("One again", "A", "B"))
        assertEquals(first, db.trips().createdMs("t1"))
        assertEquals(first + 1_000L, db.trips().createdMs("t2"))
        assertEquals(null, db.trips().createdMs("missing"))
    }
}
