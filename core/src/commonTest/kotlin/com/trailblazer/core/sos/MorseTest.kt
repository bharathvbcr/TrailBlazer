package com.trailblazer.core.sos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MorseTest {
    @Test
    fun textWithNothingEncodableHasAnEmptyTimelineAndCode() {
        for (s in listOf("", "   ", "!!", "?! ... !?", "é ü", "\t\n")) {
            assertTrue(Morse.timeline(s).isEmpty(), "timeline of '$s'")
            assertEquals("", Morse.code(s), "code of '$s'")
        }
    }

    @Test
    fun wordsWithNothingEncodableAreSkippedNotSpaced() {
        assertEquals(Morse.timeline("e t"), Morse.timeline("e !! t"))
        assertEquals(Morse.code("e t"), Morse.code("e !! t"))
        assertEquals(Morse.timeline("e"), Morse.timeline("!! e ??"))
        assertEquals(". / -", Morse.code("e !! t"))
    }

    @Test
    fun standardSpacingIsUnchanged() {
        val u = 100L
        // "E T": dot, word gap, dash, word gap.
        assertEquals(
            listOf(Pulse(true, u), Pulse(false, 7 * u), Pulse(true, 3 * u), Pulse(false, 7 * u)),
            Morse.timeline("E T", unitMs = u),
        )
        // "A": dot, gap, dash, then the word gap.
        assertEquals(
            listOf(Pulse(true, u), Pulse(false, u), Pulse(true, 3 * u), Pulse(false, 7 * u)),
            Morse.timeline("A", unitMs = u),
        )
        assertEquals("... --- ...", Morse.code("sos"))
    }

    @Test
    fun silentTimelinesAreRecognised() {
        assertTrue(Morse.isSilent(emptyList()))
        assertTrue(Morse.isSilent(listOf(Pulse(false, 700L))))
        assertTrue(Morse.isSilent(listOf(Pulse(true, 0L), Pulse(false, 700L))))
        assertFalse(Morse.isSilent(Morse.SOS))
        assertFalse(Morse.isSilent(Morse.whistleTimeline()))
    }
}
