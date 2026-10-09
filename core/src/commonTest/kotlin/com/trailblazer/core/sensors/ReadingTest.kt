package com.trailblazer.core.sensors

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTest {
    @Test
    fun readingMapTransformsValue() {
        val r: Reading<Int> = Reading.Value(42, Accuracy.High, 1000L)
        val mapped = r.map { it * 2 }
        assertTrue(mapped is Reading.Value)
        assertEquals(84, mapped.value)
        assertEquals(Accuracy.High, mapped.accuracy)
        assertEquals(1000L, mapped.timestampMs)
    }

    @Test
    fun readingMapPassesThroughUnavailableAndAcquiring() {
        val un: Reading<Int> = Reading.Unavailable(UnavailableReason.NoHardware)
        assertEquals(un, un.map { it * 2 })

        val ac: Reading<Int> = Reading.Acquiring
        assertEquals(ac, ac.map { it * 2 })
    }

    @Test
    fun readingValueOrNull() {
        assertEquals(10, Reading.Value(10, timestampMs = 0L).valueOrNull())
        assertNull(Reading.Acquiring.valueOrNull())
        assertNull(Reading.Unavailable(UnavailableReason.Disabled).valueOrNull())
    }

    @Test
    fun shareReadingReplaysStaleOnResubscription() = runTest {
        val source = MutableSharedFlow<Reading<String>>()
        val state = source.shareReading(backgroundScope)

        assertEquals(Reading.Acquiring, state.value)

        val collector1 = launch {
            state.take(2).toList()
        }
        runCurrent()
        source.emit(Reading.Value("first", Accuracy.High, 100L))
        runCurrent()

        assertEquals("first", (state.value as Reading.Value).value)
        assertEquals(false, (state.value as Reading.Value).stale)
    }
}
