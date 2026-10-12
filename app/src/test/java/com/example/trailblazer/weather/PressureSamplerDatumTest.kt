package com.example.trailblazer.weather

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.PressureHistory
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.sensors.Reading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The barometer history only takes an elevation that is above sea level: the pressure trend reduces to sea level with it. */
@RunWith(AndroidJUnit4::class)
class PressureSamplerDatumTest {
    private lateinit var db: TrailDb
    private val now = 1_700_000_000_000L
    private val clock = Clock { now }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrailDb::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun fix(ele: Double?, datum: AltitudeDatum) =
        Reading.Value(Fix(LatLon(46.0, 7.0), 5.0, ele, datum, null, null, null, now - 1_000L, "gps"), timestampMs = now)

    private fun sampleWith(fix: Reading<Fix>): Double? = runBlocking {
        val sampler = PressureSampler(MutableStateFlow(Reading.Value(900.0, timestampMs = now)), MutableStateFlow(fix), PressureHistory(db.pressure(), clock), clock)
        val job = launch { sampler.run() }
        withTimeout(10_000) { while (db.pressure().latestTime() == null) delay(10) }
        job.cancel()
        val row = db.pressure().observeSince(0).first().single()
        assertEquals(900.0, row.stationHpa, 0.0)
        row.elevationM
    }

    @Test
    fun aSeaLevelAltitudeIsRecorded() {
        assertEquals(1500.0, sampleWith(fix(1500.0, AltitudeDatum.SeaLevel))!!, 0.0)
    }

    @Test
    fun anEllipsoidHeightIsNotRecordedAsAnElevation() {
        assertNull(sampleWith(fix(1547.0, AltitudeDatum.Ellipsoid)))
    }

    @Test
    fun aFixWithoutAltitudeRecordsTheStationPressureAlone() {
        assertNull(sampleWith(fix(null, AltitudeDatum.SeaLevel)))
    }
}
