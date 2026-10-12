package com.example.trailblazer.sos

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trailblazer.core.sos.Morse
import com.trailblazer.core.sos.Pulse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** The whistle must report an unusable audio output instead of crashing the SOS screen. */
@RunWith(AndroidJUnit4::class)
class WhistlePlayerTest {
    @Test
    fun anOutputThatCannotBeOpenedMakesPlayReturnFalseWithoutASound() = runBlocking {
        val failures = listOf<() -> Nothing>(
            { throw UnsupportedOperationException("Cannot create AudioTrack") },
            { throw IllegalArgumentException("Invalid audio attributes") },
            { throw IllegalStateException("audio policy") },
        )
        for (fail in failures) {
            val pulses = ArrayList<Boolean>()
            val played = WhistlePlayer(openTrack = fail).play(Morse.whistleTimeline()) { pulses += it }
            assertFalse(played)
            assertEquals("no pulse may be reported for a whistle that never started", emptyList<Boolean>(), pulses)
        }
    }

    @Test
    fun aSilentTimelineIsRefusedBeforeTheOutputIsTouched() = runBlocking {
        var opened = false
        val whistle = WhistlePlayer(openTrack = { opened = true; throw IllegalStateException("must not be called") })
        assertFalse(whistle.play(emptyList()))
        assertFalse(whistle.play(listOf(Pulse(false, 1000L))))
        assertFalse(opened)
    }

    @Test
    fun aWorkingOutputKeepsPlayingUntilCancelled() = runBlocking {
        val pulses = CopyOnWriteArrayList<Boolean>()
        // Still playing when the timeout cancels it: play never returned false.
        val result = withTimeoutOrNull(500L) { WhistlePlayer().play(Morse.whistleTimeline()) { pulses += it } }
        assertNull(result)
        assertEquals("the last pulse reported after cancellation is off", false, pulses.last())
    }
}
