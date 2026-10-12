package com.example.trailblazer.ui.now

import android.hardware.Sensor
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.testing.ContainerFixture
import com.example.trailblazer.testing.awaitMainLooper
import com.trailblazer.core.sensors.Reading
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The step counter reports steps since boot. The first reading becomes the baseline; steps shown are the ones since.
 * Re-baselining used to launch a write from inside the flow transform on every reading that arrived before the first
 * write came back, and each wrote its own reading, so walking erased the steps just counted.
 */
@RunWith(AndroidJUnit4::class)
class NowStepsBaselineTest {
    private val fx = ContainerFixture(sensorTypes = setOf(Sensor.TYPE_STEP_COUNTER))

    @After
    fun tearDown() = fx.close()

    private fun baseline() = runBlocking { fx.container.prefs.settings.first().stepsBaseline }

    private fun shown(vm: NowViewModel) = ((vm.steps.value as? Reading.Value)?.value)

    private fun startCounting(): NowViewModel {
        runBlocking { fx.container.prefs.update { it.copy(stepsEnabled = true, stepsBaseline = null) } }
        val vm = NowViewModel(fx.container)
        fx.scope.launch { vm.steps.collect {} }
        awaitMainLooper(5_000) { fx.sensors.listenerCount(Sensor.TYPE_STEP_COUNTER) > 0 }
        return vm
    }

    private fun emitStepsWithoutWaiting(vararg readings: Float) {
        for (r in readings) {
            fx.sensors.emit(Sensor.TYPE_STEP_COUNTER, r)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun readingsArrivingBeforeTheBaselineIsStoredDoNotMoveIt() {
        val vm = startCounting()

        emitStepsWithoutWaiting(1_000f, 1_040f, 1_090f)
        awaitMainLooper(5_000) { baseline() != null && shown(vm) == 90L }

        assertEquals("the first reading is the baseline", 1_000L, baseline())
        assertEquals("steps since then", 90L, shown(vm))
    }

    @Test
    fun aCounterRestartBelowTheBaselineIsRebaselinedOnce() {
        val vm = startCounting()
        emitStepsWithoutWaiting(5_000f)
        awaitMainLooper(5_000) { baseline() == 5_000L }

        emitStepsWithoutWaiting(30f, 45f, 60f) // rebooted: the counter starts again from zero
        awaitMainLooper(5_000) { baseline() == 30L && shown(vm) == 30L }

        assertEquals(30L, baseline())
        assertEquals(30L, shown(vm))
    }

    @Test
    fun stepsAreNotCountedWhileTheFeatureIsOff() {
        runBlocking { fx.container.prefs.update { it.copy(stepsEnabled = false, stepsBaseline = null) } }
        val vm = NowViewModel(fx.container)
        fx.scope.launch { vm.steps.collect {} }
        awaitMainLooper(300) { false }

        assertEquals(0, fx.sensors.listenerCount(Sensor.TYPE_STEP_COUNTER))
        assertEquals(null, baseline())
    }
}
