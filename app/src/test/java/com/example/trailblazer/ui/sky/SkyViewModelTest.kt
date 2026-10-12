package com.example.trailblazer.ui.sky

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.testing.ContainerFixture
import com.example.trailblazer.testing.afterProcessDeath
import com.example.trailblazer.testing.awaitMainLooper
import com.example.trailblazer.weather.ForecastResult
import com.example.trailblazer.weather.HttpTransport
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The forecast transport the test holds shut: each request waits until the test answers it. */
private class GatedTransport : HttpTransport {
    val requests = ArrayList<CompletableDeferred<String>>()

    override suspend fun get(url: String): String {
        val gate = CompletableDeferred<String>()
        requests += gate
        return gate.await()
    }

    companion object {
        fun body(temperatureC: Double) = """{"current":{"time":"2026-09-29T22:45","temperature_2m":$temperatureC}}"""
    }
}

@RunWith(AndroidJUnit4::class)
class SkyViewModelTest {
    private val gate = GatedTransport()
    private val fx = ContainerFixture(http = gate)
    private val place = SkyPlace(LatLon(46.5, 7.8), "Grindelwald", manual = true)

    @After
    fun tearDown() = fx.close()

    // ---- what the user chose survives the process

    @Test
    fun theDayAndAChosenPlaceComeBackAfterTheProcessIsKilled() {
        val handle = SavedStateHandle()
        val first = SkyViewModel(fx.container, handle)
        first.step(3)
        first.setManualPlace(place)

        val second = SkyViewModel(fx.container, handle.afterProcessDeath())

        assertEquals(3, second.dayOffset.value)
        assertEquals(place, second.manualPlace.value)
    }

    @Test
    fun goingBackToLivePositionIsRemembered() {
        val handle = SavedStateHandle()
        val first = SkyViewModel(fx.container, handle)
        first.setManualPlace(place)
        first.setManualPlace(null)

        assertNull(SkyViewModel(fx.container, handle.afterProcessDeath()).manualPlace.value)
    }

    @Test
    fun theDayStaysWithinAYearEitherWay() {
        val vm = SkyViewModel(fx.container)
        vm.step(10_000)
        assertEquals(MAX_DAY_OFFSET, vm.dayOffset.value)
        vm.step(-100_000)
        assertEquals(-MAX_DAY_OFFSET, vm.dayOffset.value)
        val restored = SkyViewModel(fx.container, SavedStateHandle(mapOf("dayOffset" to 9_999_999)))
        assertEquals("a saved value out of range is pulled back in", MAX_DAY_OFFSET, restored.dayOffset.value)
    }

    @Test
    fun aChosenPlaceWithCommasInItsLabelRoundTrips() {
        val p = SkyPlace(LatLon(-33.8688, 151.2093), "Sydney, New South Wales, Australia", manual = true)
        assertEquals(p, decodeManualPlace(encodeManualPlace(p)))
        assertEquals(SkyPlace(LatLon(1.0, 2.0), null, manual = true), decodeManualPlace("1.0,2.0,"))
    }

    @Test
    fun aSavedPlaceThatIsNotARealPositionIsIgnored() {
        for (bad in listOf("", "x", "1,2", "NaN,5,Home", "91,0,Pole", "a,b,c", "5,Infinity,Far")) {
            assertNull("'$bad'", decodeManualPlace(bad))
        }
        val vm = SkyViewModel(fx.container, SavedStateHandle(mapOf("manualPlace" to "garbage")))
        assertNull(vm.manualPlace.value)
    }

    // ---- forecast loads

    private fun readyToLoad(vm: SkyViewModel) {
        runBlocking { fx.container.prefs.update { it.copy(forecastConsent = true) } }
        fx.scope.launch { vm.settings.collect {} }
        vm.setManualPlace(place)
        fx.scope.launch { vm.place.collect {} }
        awaitMainLooper(5_000) { vm.settings.value?.forecastConsent == true && vm.place.value != null }
        assertTrue("consent and place reached the ViewModel", vm.settings.value?.forecastConsent == true && vm.place.value != null)
    }

    private fun temperatureOf(vm: SkyViewModel) = ((vm.forecast.value as? ForecastResult.Ok)?.forecast?.response?.current?.temperatureC)

    @Test
    fun anOlderLoadFinishingLateNeitherEndsTheSpinnerNorReplacesTheNewerOne() {
        val vm = SkyViewModel(fx.container)
        readyToLoad(vm)

        vm.loadForecast(force = true)
        awaitMainLooper(5_000) { gate.requests.size == 1 }
        vm.loadForecast(force = true) // Refresh again while the first is still out
        awaitMainLooper(5_000) { gate.requests.size == 2 }

        gate.requests[0].complete(GatedTransport.body(1.0)) // the first answers late
        awaitMainLooper(300) { false }
        assertTrue("the second load is still running", vm.forecastLoading.value)
        assertNull("the older answer must not be shown", vm.forecast.value)

        gate.requests[1].complete(GatedTransport.body(2.0))
        awaitMainLooper(5_000) { !vm.forecastLoading.value }
        assertFalse(vm.forecastLoading.value)
        assertEquals(2.0, temperatureOf(vm)!!, 1e-9)
    }

    @Test
    fun aLoadFinishingWithoutWaitingStillEndsTheSpinner() {
        val vm = SkyViewModel(fx.container)
        readyToLoad(vm)
        vm.loadForecast(force = true)
        awaitMainLooper(5_000) { gate.requests.size == 1 }
        gate.requests[0].complete(GatedTransport.body(5.0))
        awaitMainLooper(5_000) { !vm.forecastLoading.value }

        vm.loadForecast(force = false) // served from the cache: no suspension at all
        awaitMainLooper(5_000) { temperatureOf(vm) == 5.0 }
        awaitMainLooper(500) { false }

        assertFalse("a cache hit must not leave the spinner on", vm.forecastLoading.value)
        assertEquals("and made no second request", 1, gate.requests.size)
    }

    @Test
    fun aLoadInFlightWhenConsentIsWithdrawnNeverShowsAndLeavesNothingCached() {
        val vm = SkyViewModel(fx.container)
        readyToLoad(vm)
        vm.loadForecast(force = true)
        awaitMainLooper(5_000) { gate.requests.size == 1 }

        vm.setConsent(false)
        awaitMainLooper(5_000) { vm.settings.value?.forecastConsent == false }
        gate.requests[0].complete(GatedTransport.body(9.0)) // the response arrives after the user said no
        awaitMainLooper(500) { false }

        assertNull("withdrawn consent must not be followed by a forecast", vm.forecast.value)
        assertFalse(vm.forecastLoading.value)
        runBlocking { fx.container.prefs.update { it.copy(forecastConsent = true) } }
        awaitMainLooper(5_000) { vm.settings.value?.forecastConsent == true }
        vm.loadForecast(force = false)
        awaitMainLooper(5_000) { gate.requests.size == 2 }
        assertEquals("nothing from the cancelled load was cached: the next load asks again", 2, gate.requests.size)
    }

    @Test
    fun withdrawingConsentClearsTheScreenAtOnceEvenWhileALoadIsStuck() {
        val vm = SkyViewModel(fx.container)
        readyToLoad(vm)
        vm.loadForecast(force = true)
        awaitMainLooper(5_000) { gate.requests.size == 1 }
        gate.requests[0].complete(GatedTransport.body(4.0))
        awaitMainLooper(5_000) { temperatureOf(vm) == 4.0 }
        vm.loadForecast(force = true)
        awaitMainLooper(5_000) { gate.requests.size == 2 } // this one never answers

        vm.setConsent(false)
        awaitMainLooper(5_000) { vm.forecast.value == null }

        assertNull(vm.forecast.value)
        assertFalse("the spinner is off with the load still unanswered", vm.forecastLoading.value)
        // DataStore runs a write's transform on the caller's dispatcher, here the main thread: let the write finish
        // before the test ends, or the next test's blocking write on that thread would wait on it forever.
        awaitMainLooper(5_000) { vm.settings.value?.forecastConsent == false }
        assertEquals(false, vm.settings.value?.forecastConsent)
    }
}
