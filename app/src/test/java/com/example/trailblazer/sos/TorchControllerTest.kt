package com.example.trailblazer.sos

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.sos.Morse
import com.trailblazer.core.sos.Pulse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCameraCharacteristics

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TorchControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(CameraManager::class.java)

    private fun addBackCameraWithFlash() {
        val ch = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadow = shadowOf(ch)
        shadow.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadow.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        shadowOf(manager).addCamera("0", ch)
    }

    @Test
    fun noTorchMeansNoCameraIdAndPlayFails() = runTest {
        val t = TorchController(context)
        assertNull(t.cameraId)
        assertFalse(t.play(Morse.SOS))
    }

    @Test
    fun sosTimelineDrivesTorchAndStopsOffWhenCancelled() = runTest {
        addBackCameraWithFlash()
        val t = TorchController(context)
        assertEquals("0", t.cameraId)
        val pulses = ArrayList<Boolean>()
        val job = launch { t.play(Morse.SOS) { pulses += it } }
        runCurrent()
        assertTrue(shadowOf(manager).getTorchMode("0"))
        // One full SOS cycle: 9 symbols (3×200 + 3×600 + 3×200 on) + gaps.
        val cycle = Morse.SOS.sumOf { it.durationMs }
        advanceTimeBy(cycle - 1)
        runCurrent()
        val onPulses = pulses.zipWithNext().count { (a, b) -> !a && b } + 1
        assertEquals(9, onPulses)
        job.cancel()
        runCurrent()
        assertFalse("torch must be off after cancellation", shadowOf(manager).getTorchMode("0"))
        assertFalse(pulses.last())
    }

    @Test
    fun aTimelineThatNeverSwitchesOnIsRefusedInsteadOfLoopingInTheDark() = runTest {
        addBackCameraWithFlash()
        val t = TorchController(context)
        for (silent in listOf(Morse.timeline("!!"), listOf(Pulse(false, 500L)), listOf(Pulse(true, 0L), Pulse(false, 500L)))) {
            var result: Boolean? = null
            val job = launch { result = t.play(silent) }
            runCurrent()
            val finished = job.isCompleted
            job.cancel()
            assertTrue("play must return at once, not loop over $silent", finished)
            assertEquals(false, result)
        }
    }
}
