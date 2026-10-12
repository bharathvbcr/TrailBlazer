package com.example.trailblazer.ui.tools

import com.trailblazer.core.sos.Morse
import com.trailblazer.core.sos.Pulse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TorchlessScreenTest {
    @Test
    fun aTimelineThatNeverSwitchesOnReturnsFalseAtOnceWithoutAPulse() = runTest {
        // (An empty timeline is refused by the same check, but unguarded it would spin without ever suspending.)
        for (silent in listOf(listOf(Pulse(false, 500L)), listOf(Pulse(true, 0L), Pulse(false, 500L)))) {
            val pulses = ArrayList<Boolean>()
            var result: Boolean? = null
            val job = launch { result = torchlessScreen(silent) { pulses += it } }
            runCurrent()
            val finished = job.isCompleted
            job.cancel()
            assertTrue("returns at once for $silent", finished)
            assertEquals(false, result)
            assertTrue(pulses.isEmpty())
        }
    }

    @Test
    fun theSosTimelineKeepsRunningUntilCancelled() = runTest {
        val pulses = ArrayList<Boolean>()
        val job = launch { torchlessScreen(Morse.SOS) { pulses += it } }
        runCurrent()
        assertFalse(job.isCompleted)
        assertTrue(pulses.first())
        job.cancel()
        runCurrent()
        assertFalse(pulses.last())
    }
}
