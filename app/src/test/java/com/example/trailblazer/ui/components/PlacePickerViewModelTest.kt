package com.example.trailblazer.ui.components

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.places.FoundPlace
import com.example.trailblazer.places.PlaceSearchBackend
import com.example.trailblazer.testing.ContainerFixture
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlacePickerViewModelTest {
    /** One answer per query, held back until the test gives it. */
    private val answers = HashMap<String, CompletableDeferred<List<FoundPlace>>>()
    private val asked = ArrayList<String>()
    private val backend = PlaceSearchBackend { q, _ ->
        asked += q
        answers.getOrPut(q) { CompletableDeferred() }.await()
    }
    private val fx = ContainerFixture(placeBackend = backend)

    @After
    fun tearDown() = fx.close()

    private fun found(name: String) = listOf(FoundPlace(LatLon(1.0, 2.0), name))

    private fun answer(q: String, vararg names: String) {
        answers.getOrPut(q) { CompletableDeferred() }.complete(names.flatMap { found(it) })
    }

    @Test
    fun anOlderSearchAnsweringLateNeverReplacesTheNewerOne() {
        val vm = PlacePickerViewModel(fx.container)

        vm.search("matterhorn", consented = true)
        awaitMainLooper(5_000) { "matterhorn" in asked }
        vm.search("eiger", consented = true)
        awaitMainLooper(5_000) { "eiger" in asked }

        answer("eiger", "Eiger")
        awaitMainLooper(5_000) { vm.state.value.results != null }
        answer("matterhorn", "Matterhorn") // the first one answers last
        awaitMainLooper(300) { false }

        val s = vm.state.value
        assertEquals("eiger", s.results?.query)
        assertEquals(listOf("Eiger"), s.results?.places?.map { it.name })
        assertFalse(s.searching)
    }

    @Test
    fun theSpinnerStaysOnUntilTheLatestSearchAnswers() {
        val vm = PlacePickerViewModel(fx.container)
        vm.search("matterhorn", consented = true)
        awaitMainLooper(5_000) { "matterhorn" in asked }
        vm.search("eiger", consented = true)
        awaitMainLooper(5_000) { "eiger" in asked }

        answer("matterhorn", "Matterhorn")
        awaitMainLooper(300) { false }

        assertTrue("the earlier answer is not the one being waited for", vm.state.value.searching)
        assertNull(vm.state.value.results)
    }

    @Test
    fun closingThePickerDropsTheAnswerAndTheSearch() {
        val vm = PlacePickerViewModel(fx.container)
        vm.search("matterhorn", consented = true)
        awaitMainLooper(5_000) { "matterhorn" in asked }

        vm.reset()
        answer("matterhorn", "Matterhorn")
        awaitMainLooper(300) { false }

        assertEquals(PlacePickerViewModel.State(), vm.state.value)
    }

    @Test
    fun withoutConsentTheQueryWaitsForTheUsersSayAndNothingIsSent() {
        val vm = PlacePickerViewModel(fx.container)

        vm.search("matterhorn", consented = false)
        awaitMainLooper(5_000) { vm.state.value.askConsent != null }

        assertEquals("matterhorn", vm.state.value.askConsent)
        assertTrue("nothing left the phone", asked.isEmpty())
    }

    @Test
    fun agreeingStoresTheConsentEvenIfThePickerIsGoneBeforeItFinishes() {
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>) = PlacePickerViewModel(fx.container) as T
        })[PlacePickerViewModel::class.java]

        vm.agreeAndSearch("matterhorn")
        store.clear() // the screen is popped straight away

        awaitMainLooper(5_000) { runBlocking { fx.container.prefs.settings.first().placeSearchConsent } }
        assertTrue(runBlocking { fx.container.prefs.settings.first().placeSearchConsent })
    }

    @Test
    fun declineLeavesAnExplanationAndNoAsk() {
        val vm = PlacePickerViewModel(fx.container)
        vm.search("matterhorn", consented = false)
        awaitMainLooper(5_000) { vm.state.value.askConsent != null }

        vm.declineConsent()

        assertNull(vm.state.value.askConsent)
        assertTrue(vm.state.value.notice!!.startsWith("Search is off"))
    }

    @Test
    fun anEmptyAnswerSaysNothingWasFound() {
        val vm = PlacePickerViewModel(fx.container)
        vm.search("zzzz", consented = true)
        awaitMainLooper(5_000) { "zzzz" in asked }
        answer("zzzz")
        awaitMainLooper(5_000) { vm.state.value.notice != null }

        assertEquals("Nothing found for “zzzz”.", vm.state.value.notice)
    }

    @Test
    fun aQueryThatIsTooShortIsRefusedWithoutAskingAnyone() {
        val vm = PlacePickerViewModel(fx.container)
        vm.search("a", consented = true)
        awaitMainLooper(5_000) { vm.state.value.notice != null }

        assertTrue(asked.isEmpty())
        assertEquals("Type at least 2 letters.", vm.state.value.notice)
    }
}
