package com.example.trailblazer.ui.stars

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.TrailDb
import com.example.trailblazer.sensors.FakeSensorSource
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class StargazeViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(app, TrailDb::class.java).allowMainThreadQueries().build()
    private val container = AppContainer(app, sensorSource = FakeSensorSource(emptySet()), db = db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        scope.cancel()
        container.scope.cancel()
        db.close()
    }

    /** The plotted tracks must agree with the rise/set/best maths they sit beside, or the card contradicts itself. */
    @Test
    fun altitudeTracksCoverTheNightAndAgreeWithTheBestAltitude() {
        for (place in listOf(LatLon(39.74, -104.99), LatLon(-33.87, 151.21), LatLon(69.65, 18.96))) {
            val vm = StargazeViewModel(container, StarPlace(place, null, true))
            val job = scope.launch { vm.tonight.collect {} }
            awaitMainLooper { vm.tonight.value != null }
            val t = vm.tonight.value!!
            job.cancel()
            val tracks = t.planets.map { it.planet.label to it.track } + ("core" to t.galacticTrack)
            for ((name, tr) in tracks) {
                assertEquals("$name starts at the window", t.plan.windowStartMs, tr.startMs)
                assertTrue("$name covers the window", tr.endMs in t.plan.windowEndMs - tr.stepMs..t.plan.windowEndMs)
                assertTrue("$name altitudes are real", tr.altitudesDeg.all { it.isFinite() && it in -90f..90f })
            }
            for (p in t.planets + listOf(null)) {
                val night = p?.night ?: t.galacticCentre
                val tr = p?.track ?: t.galacticTrack
                val best = night.bestAltitudeDeg ?: continue
                val bestIdx = ((night.bestTimeMs!! - tr.startMs) / tr.stepMs).toInt()
                val near = tr.altitudesDeg.subList((bestIdx - 1).coerceAtLeast(0), (bestIdx + 2).coerceAtMost(tr.altitudesDeg.size))
                assertTrue("$place ${p?.planet}: best $best vs track $near", near.any { kotlin.math.abs(it - best) < 4.0 })
            }
        }
    }

    @Test
    fun aChosenPlaceGivesTonightAChartAndPolarAlignment() {
        val vm = StargazeViewModel(container, StarPlace(LatLon(39.74, -104.99), "Denver", true))
        scope.launch { vm.tonight.collect {} }
        scope.launch { vm.chart.collect {} }
        scope.launch { vm.polar.collect {} }
        awaitMainLooper { vm.tonight.value != null && vm.chart.value != null && vm.polar.value != null }
        val t = vm.tonight.value!!
        assertEquals(7, t.planets.size)
        assertTrue(t.plan.windowEndMs - t.plan.windowStartMs in 23 * 3_600_000L..25 * 3_600_000L)
        val chart = vm.chart.value!!
        assertTrue("some stars are always above the horizon", chart.objects.count { it.kind == ChartKind.Star } > 50)
        assertTrue(chart.objects.all { it.altitudeDeg > -2 && it.azimuthDeg in 0.0..360.0 })
        assertNull("no compass following unless asked", chart.facingDeg)
        assertEquals("Polaris", vm.polar.value!!.starLabel)
    }

    @Test
    fun theChartLooksAheadWhenAskedAndStaysWithinTwelveHours() {
        val vm = StargazeViewModel(container, StarPlace(LatLon(-33.87, 151.21), null, true))
        scope.launch { vm.chart.collect {} }
        awaitMainLooper { vm.chart.value != null }
        val now = vm.chart.value!!.timeMs
        vm.chartOffsetMin.value = 180
        awaitMainLooper { vm.chart.value!!.timeMs != now }
        assertEquals(180 * 60_000.0, (vm.chart.value!!.timeMs - now).toDouble(), 60_000.0)
    }

    /** Without a chosen place and without location permission there is honestly nothing to compute. */
    @Test
    fun noPlaceAndNoPermissionMeansNoFakeSky() {
        val vm = StargazeViewModel(container, null)
        scope.launch { vm.tonight.collect {} }
        scope.launch { vm.chart.collect {} }
        awaitMainLooper(1_000) { false }
        assertNull(vm.place.value)
        assertNull(vm.tonight.value)
        assertNull(vm.chart.value)
    }

    private fun stored(ageMs: Long) {
        shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        container.permissions.refresh()
        val manager = app.getSystemService(android.location.LocationManager::class.java)
        shadowOf(manager).setLocationEnabled(true)
        val loc = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 39.7392; longitude = -104.9903; accuracy = 30f; time = System.currentTimeMillis() - ageMs
        }
        for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER, "fused")) {
            shadowOf(manager).setLastKnownLocation(p, loc)
        }
    }

    /** Indoors the first live fix can take minutes; a recent stored position fills in, clearly marked. */
    @Test
    fun aRecentStoredPositionStandsInWhileGpsSearches() {
        stored(ageMs = 2 * 3_600_000L)
        val vm = StargazeViewModel(container, null)
        scope.launch { vm.tonight.collect {} }
        awaitMainLooper { vm.tonight.value != null }
        val p = vm.place.value
        assertNotNull("a recent stored position should be used", p)
        assertTrue(p!!.lastKnown)
        assertEquals(39.74, p.position.lat, 1e-9)
        assertEquals(-104.99, p.position.lon, 1e-9)
    }

    @Test
    fun aStaleStoredPositionIsNotUsed() {
        stored(ageMs = LAST_KNOWN_MAX_AGE_MS + 60_000L)
        val vm = StargazeViewModel(container, null)
        scope.launch { vm.tonight.collect {} }
        awaitMainLooper(1_500) { false }
        assertNull(vm.place.value)
        assertNull(vm.tonight.value)
    }

    @Test
    fun followCompassWithoutACompassNeverInventsAHeading() {
        val vm = StargazeViewModel(container, StarPlace(LatLon(46.5, 7.8), null, true))
        assertFalse(vm.compassAvailable)
        vm.followCompass.value = true
        scope.launch { vm.chart.collect {} }
        awaitMainLooper(3_000) { vm.chart.value != null }
        assertNotNull(vm.chart.value)
        assertNull(vm.chart.value!!.facingDeg)
    }
}
