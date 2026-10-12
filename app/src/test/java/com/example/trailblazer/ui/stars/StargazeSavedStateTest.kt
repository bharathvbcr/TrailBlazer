package com.example.trailblazer.ui.stars

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.testing.ContainerFixture
import com.example.trailblazer.testing.afterProcessDeath
import com.trailblazer.core.geo.LatLon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StargazeSavedStateTest {
    private val fx = ContainerFixture()
    private val place = StarPlace(LatLon(39.74, -104.99), "Denver", fromRoute = true)

    @After
    fun tearDown() = fx.close()

    @Test
    fun theChartTimeAndFollowCompassComeBackAfterTheProcessIsKilled() {
        val handle = SavedStateHandle()
        val first = StargazeViewModel(fx.container, place, handle)
        first.setChartOffsetMin(195)
        first.setFollowCompass(true)

        val second = StargazeViewModel(fx.container, place, handle.afterProcessDeath())

        assertEquals(195, second.chartOffsetMin.value)
        assertTrue(second.followCompass.value)
    }

    @Test
    fun aFreshChartStartsNowWithTheCompassOff() {
        val vm = StargazeViewModel(fx.container, place)
        assertEquals(0, vm.chartOffsetMin.value)
        assertFalse(vm.followCompass.value)
    }

    @Test
    fun theChartTimeStaysWithinTheTwelveHoursTheSliderOffers() {
        val vm = StargazeViewModel(fx.container, place)
        vm.setChartOffsetMin(100_000)
        assertEquals(MAX_CHART_OFFSET_MIN, vm.chartOffsetMin.value)
        vm.setChartOffsetMin(-5)
        assertEquals(0, vm.chartOffsetMin.value)
        val restored = StargazeViewModel(fx.container, place, SavedStateHandle(mapOf("chartOffsetMin" to -40_000)))
        assertEquals("a saved value out of range is pulled back in", 0, restored.chartOffsetMin.value)
    }
}
