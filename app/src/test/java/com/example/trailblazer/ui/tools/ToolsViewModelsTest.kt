package com.example.trailblazer.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.Settings
import com.example.trailblazer.testing.ContainerFixture
import com.example.trailblazer.testing.awaitMainLooper
import com.example.trailblazer.sensors.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The level and altimeter settings are written by the user pressing a button and, often, leaving at once. The write
 * must outlast the screen: its ViewModel is cleared when the screen is popped.
 */
@RunWith(AndroidJUnit4::class)
class ToolsViewModelsTest {
    private val fx = ContainerFixture(clock = Clock { 1_790_000_000_000L })

    @After
    fun tearDown() = fx.close()

    private fun settings(): Settings = runBlocking { fx.container.prefs.settings.first() }

    private inline fun <reified T : ViewModel> screen(store: ViewModelStore, crossinline make: () -> T): T =
        ViewModelProvider.create(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>) = make() as V
        })[T::class.java]

    @Test
    fun theLevelZeroIsStoredEvenWhenTheScreenIsLeftAtOnce() {
        val store = ViewModelStore()
        val vm = screen(store) { LevelViewModel(fx.container) }

        vm.setZero(1.5, -2.5)
        store.clear()

        awaitMainLooper(5_000) { settings().levelPitchOffsetDeg == 1.5 }
        assertEquals(1.5, settings().levelPitchOffsetDeg, 1e-9)
        assertEquals(-2.5, settings().levelRollOffsetDeg, 1e-9)
    }

    @Test
    fun resettingTheLevelZeroClearsBothOffsets() {
        val vm = LevelViewModel(fx.container)
        vm.setZero(3.0, 4.0)
        awaitMainLooper(5_000) { settings().levelPitchOffsetDeg == 3.0 }

        vm.resetZero()
        awaitMainLooper(5_000) { settings().levelPitchOffsetDeg == 0.0 }

        assertEquals(0.0, settings().levelRollOffsetDeg, 1e-9)
    }

    @Test
    fun theHapticsSwitchIsStoredEvenWhenTheScreenIsLeftAtOnce() {
        val store = ViewModelStore()
        val vm = screen(store) { LevelViewModel(fx.container) }
        val before = settings().levelHaptics

        vm.setHaptics(!before)
        store.clear()

        awaitMainLooper(5_000) { settings().levelHaptics == !before }
        assertEquals(!before, settings().levelHaptics)
    }

    @Test
    fun theAltimeterCalibrationIsStampedAndStoredEvenWhenTheScreenIsLeftAtOnce() {
        val store = ViewModelStore()
        val vm = screen(store) { AltimeterViewModel(fx.container) }

        vm.calibrate(1_021.5)
        store.clear()

        awaitMainLooper(5_000) { settings().calibration != null }
        val c = settings().calibration
        assertNotNull(c)
        assertEquals(1_021.5, c!!.qnhHpa, 1e-9)
        assertEquals(1_790_000_000_000L, c.setAtMs)
    }

    @Test
    fun theAltimeterCalibrationCanBeCleared() {
        val vm = AltimeterViewModel(fx.container)
        vm.calibrate(1_013.0)
        awaitMainLooper(5_000) { settings().calibration != null }

        vm.clearCalibration()
        awaitMainLooper(5_000) { settings().calibration == null }

        assertNull(settings().calibration)
    }
}
