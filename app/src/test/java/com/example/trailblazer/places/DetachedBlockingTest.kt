package com.example.trailblazer.places

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The geocoder below API 33 blocks a thread and cannot be interrupted. These tests hold such a call shut with a latch
 * and check what the caller sees meanwhile.
 */
class DetachedBlockingTest {
    private val release = CountDownLatch(1)

    /** A call that blocks until the test releases it. [started] counts how many have begun. */
    private fun blocking(started: AtomicInteger? = null, result: String = "done"): () -> String = {
        started?.incrementAndGet()
        check(release.await(20, TimeUnit.SECONDS)) { "the test never released the blocked call" }
        result
    }

    /** Runs [block], failing the test when it does not come back within [ms]: a hang must read as a failure. */
    private fun <T> within(ms: Long, block: suspend () -> T): T = runBlocking {
        withTimeoutOrNull(ms) { block() }
            ?: error("did not return within $ms ms: the caller is being held by a blocked call")
    }

    @Test
    fun aTimeoutReturnsToTheCallerAtOnceWhileTheBlockedCallIsStillRunning() {
        val d = DetachedBlocking(maxCalls = 2)
        val started = AtomicInteger()
        val t0 = System.nanoTime()

        val answer = runBlocking { withTimeoutOrNull(150) { d.call(blocking(started)) } }

        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertNull("timed out, so no answer", answer)
        assertTrue("the caller was held for $elapsedMs ms by a call that cannot be interrupted", elapsedMs < 2_000)
        assertEquals("and the call did start", 1, started.get())
        release.countDown()
    }

    @Test
    fun theResultOfACallThatFinishesInTimeIsReturned() {
        val d = DetachedBlocking(maxCalls = 2)
        release.countDown()
        assertEquals("zermatt", within(5_000) { d.call(blocking(result = "zermatt")) })
    }

    @Test
    fun repeatedSearchesAgainstAStuckServiceCannotPileUp() {
        val d = DetachedBlocking(maxCalls = 2)
        val started = AtomicInteger()
        runBlocking {
            repeat(2) { withTimeoutOrNull(100) { d.call(blocking(started)) } }
            val refused = assertThrows(IOException::class.java) { runBlocking { d.call(blocking(started)) } }
            assertTrue(refused.message!!.contains("busy"))
        }
        Thread.sleep(100)
        assertEquals("the refused call never ran, and no third thread was used", 2, started.get())
        release.countDown()
    }

    @Test
    fun aFinishedCallFreesItsPlaceForTheNextSearch() {
        val d = DetachedBlocking(maxCalls = 1)
        val started = AtomicInteger()
        runBlocking { withTimeoutOrNull(100) { d.call(blocking(started)) } }
        release.countDown() // the stuck call finishes in the background
        val deadline = System.currentTimeMillis() + 5_000
        var next: String? = null
        while (next == null && System.currentTimeMillis() < deadline) {
            next = try { runBlocking { d.call { "next" } } } catch (_: IOException) { Thread.sleep(10); null }
        }
        assertEquals("next", next)
    }

    @Test
    fun aFailureInsideTheCallReachesTheCallerAsAnIoException() {
        val d = DetachedBlocking(maxCalls = 2)
        val io = assertThrows(IOException::class.java) { runBlocking { d.call<String> { throw IOException("network down") } } }
        assertEquals("network down", io.message)
        val wrapped = assertThrows(IOException::class.java) { runBlocking { d.call<String> { throw IllegalArgumentException("bad query") } } }
        assertEquals("bad query", wrapped.message)
        assertTrue(generateSequence<Throwable>(wrapped) { it.cause }.any { it is IllegalArgumentException })
    }

    @Test
    fun cancellingTheCallerDoesNotWaitForTheBlockedCall() {
        val d = DetachedBlocking(maxCalls = 2)
        val t0 = System.nanoTime()
        runBlocking {
            val job = async { d.call(blocking()) }
            Thread.sleep(50)
            job.cancel()
            runCatching { job.await() }
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancelling took $elapsedMs ms", elapsedMs < 2_000)
        release.countDown()
    }
}
