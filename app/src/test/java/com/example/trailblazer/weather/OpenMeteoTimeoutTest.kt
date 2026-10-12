package com.example.trailblazer.weather

import com.example.trailblazer.sensors.Clock
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class OpenMeteoTimeoutTest {
    private val sample = """{"latitude":46.54,"longitude":7.82,"utc_offset_seconds":7200,"timezone":"Europe/Zurich",
        "current":{"time":"2026-09-29T22:45","interval":900,"temperature_2m":7.6,"weather_code":61}}"""
    private val place = LatLon(46.5582, 7.8352)
    private val clock = Clock { 0L }

    private class ScriptedTransport(var next: suspend () -> String) : HttpTransport {
        var calls = 0
        override suspend fun get(url: String): String {
            calls++
            return next()
        }
    }

    @Test
    fun aRequestThatNeverAnswersEndsInFailedAndReleasesTheLock() = runTest(timeout = 10.seconds) {
        val t = ScriptedTransport { awaitCancellation() }
        val client = OpenMeteoClient(t, clock)
        var first: ForecastResult? = null
        val job = launch { first = client.forecast(place, consented = true) }
        runCurrent()
        advanceTimeBy(OpenMeteoClient.REQUEST_TIMEOUT_MS - 1)
        runCurrent()
        assertNull("still waiting just before the bound", first)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(ForecastResult.Failed("timed out"), first)
        job.cancel()

        // The lock is free again: the next request goes through.
        t.next = { sample }
        val second = client.forecast(place, consented = true)
        assertTrue(second is ForecastResult.Ok)
        assertEquals(2, t.calls)
    }

    @Test
    fun aRequestQueuedBehindAHungOneIsServedAfterItTimesOut() = runTest(timeout = 10.seconds) {
        val t = ScriptedTransport { awaitCancellation() }
        val client = OpenMeteoClient(t, clock)
        val hung = async { client.forecast(place, consented = true) }
        runCurrent()
        t.next = { sample }
        val queued = async { client.forecast(LatLon(10.0, 10.0), consented = true) }
        runCurrent()
        advanceTimeBy(OpenMeteoClient.REQUEST_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(ForecastResult.Failed("timed out"), hung.await())
        assertTrue(queued.await() is ForecastResult.Ok)
    }

    @Test
    fun cancellingTheCallerCancelsTheTransportAndFreesTheLock() = runTest(timeout = 10.seconds) {
        var cancelled = false
        val t = ScriptedTransport { try { awaitCancellation() } finally { cancelled = true } }
        val client = OpenMeteoClient(t, clock)
        val job: Job = launch { client.forecast(place, consented = true) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(cancelled)
        t.next = { sample }
        assertTrue(client.forecast(place, consented = true) is ForecastResult.Ok)
    }

    @Test
    fun clearingWhileARequestIsInFlightDropsItsAnswerAndLeavesNothingCached() = runTest(timeout = 10.seconds) {
        val gate = CompletableDeferred<String>()
        val t = ScriptedTransport { gate.await() }
        val client = OpenMeteoClient(t, clock)
        val inFlight = async { client.forecast(place, consented = true) }
        runCurrent()
        client.clearCache() // returns at once; it does not wait for the request
        gate.complete(sample)
        assertEquals(ForecastResult.NotConsented, inFlight.await())

        t.next = { sample }
        val after = client.forecast(place, consented = true) as ForecastResult.Ok
        assertFalse("nothing from the voided request may be served from cache", after.fromCache)
        assertEquals(2, t.calls)
    }

    @Test
    fun clearingTheCacheForcesTheNextCallToFetchAgain() = runTest(timeout = 10.seconds) {
        val t = ScriptedTransport { sample }
        val client = OpenMeteoClient(t, clock)
        client.forecast(place, consented = true)
        assertTrue((client.forecast(place, consented = true) as ForecastResult.Ok).fromCache)
        client.clearCache()
        assertFalse((client.forecast(place, consented = true) as ForecastResult.Ok).fromCache)
        assertEquals(2, t.calls)
    }

    @Test
    fun aTransportErrorIsStillAFailureNotATimeout() = runTest(timeout = 10.seconds) {
        val client = OpenMeteoClient(ScriptedTransport { throw IOException("unreachable") }, clock)
        assertEquals(ForecastResult.Failed("unreachable"), client.forecast(place, consented = true))
    }

    /**
     * A real loopback socket that completes the TCP handshake (the kernel accepts into the backlog) and never
     * answers the TLS hello. Closing the connection to enforce the deadline makes the blocked read throw
     * SocketException; the caller must still be told "timed out".
     */
    @Test
    fun aServerThatNeverAnswersTimesOutInsteadOfReportingASocketError() = runBlocking {
        val budgetMs = 400L
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val transport = HttpTransport { UrlConnectionTransport().get("https://127.0.0.1:${server.localPort}/forecast") }
            val client = OpenMeteoClient(transport, clock, timeoutMs = budgetMs)
            val begun = System.nanoTime()
            val result = client.forecast(place, consented = true)
            val elapsedMs = (System.nanoTime() - begun) / 1_000_000
            assertEquals(ForecastResult.Failed("timed out"), result)
            assertTrue("took ${elapsedMs}ms for a ${budgetMs}ms budget", elapsedMs < budgetMs + 3_000)
        }
    }

    /** Blocking socket calls ignore coroutine cancellation; the helper must unblock them by closing the connection. */
    @Test
    fun blockingCancellableUnblocksAStuckCallWhenTheCallerTimesOut() = runBlocking {
        val closed = CountDownLatch(1)
        val started = CompletableDeferred<Unit>()
        val begun = System.nanoTime()
        val outcome = try {
            withTimeoutOrNull(200L) {
                blockingCancellable(onCancel = { closed.countDown() }) {
                    started.complete(Unit)
                    // Stands in for a socket read that only returns once the connection is closed.
                    if (!closed.await(30, TimeUnit.SECONDS)) throw AssertionError("onCancel was never called")
                    throw IOException("Socket closed")
                }
            }
        } catch (_: IOException) {
            "io"
        }
        val elapsedMs = (System.nanoTime() - begun) / 1_000_000
        assertTrue("caller waited ${elapsedMs}ms for a stuck read", elapsedMs < 5_000)
        assertTrue(started.isCompleted)
        assertTrue("outcome $outcome", outcome == null || outcome == "io")
        assertEquals(0L, closed.count)
    }

    @Test
    fun blockingCancellableDoesNotCloseAConnectionThatFinished() = runBlocking {
        val closed = AtomicBoolean(false)
        val value = blockingCancellable(onCancel = { closed.set(true) }) { 42 }
        assertEquals(42, value)
        Thread.sleep(50)
        assertFalse(closed.get())
    }
}
