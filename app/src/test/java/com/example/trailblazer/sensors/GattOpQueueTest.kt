package com.example.trailblazer.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** The GATT operation queue: one operation outstanding at a time, deterministic on failure, timeout and disconnect. */
class GattOpQueueTest {
    private class FakeScheduler : GattTimeoutScheduler {
        class Task(val delayMs: Long, val run: () -> Unit) {
            var cancelled = false
        }

        val tasks = ArrayList<Task>()
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val t = Task(delayMs, task)
            tasks += t
            return { t.cancelled = true }
        }

        val live: List<Task> get() = tasks.filter { !it.cancelled }

        /** The timeout of the operation in flight fires. */
        fun fireLive() = live.last().run()
    }

    private val c1 = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
    private val c2 = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
    private val c3 = UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")
    private val svc = BleUuids.ESS_SERVICE
    private val a = GattOp.EnableNotify(svc, c1)
    private val b = GattOp.EnableNotify(svc, c2)
    private val c = GattOp.EnableNotify(svc, c3)

    private lateinit var scheduler: FakeScheduler
    private val started = ArrayList<GattOp>()
    private val finished = ArrayList<Pair<GattOp, GattOpResult>>()
    private var drained = 0
    private var refuse: Set<GattOp> = emptySet()
    private lateinit var queue: GattOpQueue

    @Before
    fun setUp() {
        scheduler = FakeScheduler()
        queue = GattOpQueue(
            timeoutMs = 5_000,
            scheduler = scheduler,
            execute = { op -> if (op in refuse) false else { started += op; true } },
            onFinished = { op, r -> finished += op to r },
            onDrained = { drained++ },
        )
    }

    private fun complete(op: GattOp, ok: Boolean = true) = queue.complete(op.kind, op.characteristic, ok)

    @Test
    fun operationsRunStrictlyOneAtATimeInSubmissionOrder() {
        queue.enqueue(listOf(a, b, c))
        assertEquals("only the first is in flight", listOf<GattOp>(a), started)
        assertEquals(1, scheduler.live.size)
        assertEquals(5_000, scheduler.live.single().delayMs)

        assertTrue(complete(a))
        assertEquals(listOf<GattOp>(a, b), started)
        assertTrue(complete(b))
        assertEquals(listOf<GattOp>(a, b, c), started)
        assertEquals(0, drained)
        assertTrue(complete(c))

        assertEquals(1, drained)
        assertEquals(listOf(a to GattOpResult.Success, b to GattOpResult.Success, c to GattOpResult.Success), finished)
        assertTrue(queue.isIdle)
        assertTrue("completing its timeout timer", scheduler.live.isEmpty())
    }

    @Test
    fun anOperationThatCannotStartIsFailedAndTheNextOneRuns() {
        refuse = setOf(a)
        queue.enqueue(listOf(a, b))
        assertEquals(listOf<GattOp>(b), started)
        assertEquals(listOf(a to GattOpResult.Failed), finished)
        complete(b)
        assertEquals(1, drained)
    }

    @Test
    fun everyOperationFailingToStartStillDrainsOnce() {
        refuse = setOf(a, b)
        queue.enqueue(listOf(a, b))
        assertEquals(listOf(a to GattOpResult.Failed, b to GattOpResult.Failed), finished)
        assertEquals(1, drained)
        assertTrue(queue.isIdle)
    }

    @Test
    fun anErrorStatusFailsThatOperationOnlyAndTheQueueContinues() {
        queue.enqueue(listOf(a, b))
        complete(a, ok = false)
        assertEquals(listOf(a to GattOpResult.Failed), finished)
        assertEquals(listOf<GattOp>(a, b), started)
        complete(b)
        assertEquals(1, drained)
    }

    @Test
    fun aTimeoutAbandonsTheOperationAndAdvances() {
        queue.enqueue(listOf(a, b))
        scheduler.fireLive()
        assertEquals(listOf(a to GattOpResult.TimedOut), finished)
        assertEquals(listOf<GattOp>(a, b), started)
        assertEquals("a new timer guards the next operation", 1, scheduler.live.size)
    }

    @Test
    fun aLateCompletionOfATimedOutOperationCannotCompleteTheNextOne() {
        queue.enqueue(listOf(a, b))
        scheduler.fireLive() // a timed out, b now in flight
        assertFalse("late callback of a", complete(a))
        assertEquals("b is still in flight", 1, finished.size)
        assertTrue(complete(b))
        assertEquals(listOf(a to GattOpResult.TimedOut, b to GattOpResult.Success), finished)
    }

    @Test
    fun aStaleTimerOfAFinishedOperationDoesNothing() {
        queue.enqueue(listOf(a, b))
        val aTimer = scheduler.tasks.first()
        complete(a)
        aTimer.run() // fires despite having been cancelled (a race with the main-thread handler)
        assertEquals("b must not be timed out by a's timer", listOf(a to GattOpResult.Success), finished)
        assertTrue(queue.complete(b.kind, b.characteristic, true))
    }

    @Test
    fun aCompletionForTheWrongKindOrCharacteristicIsIgnored() {
        queue.enqueue(listOf(a))
        assertFalse(queue.complete(GattOpKind.RequestMtu, null, true))
        assertFalse(queue.complete(GattOpKind.EnableNotify, c2, true))
        assertFalse(queue.complete(GattOpKind.Read, c1, true))
        assertTrue(finished.isEmpty())
        assertTrue(complete(a))
    }

    @Test
    fun aCompletionWhenNothingIsInFlightIsIgnored() {
        assertFalse(complete(a))
        queue.enqueue(listOf(a))
        complete(a)
        assertFalse(complete(a))
        assertEquals(1, finished.size)
    }

    @Test
    fun aDisconnectDropsEverythingAndSilencesTheTimer() {
        queue.enqueue(listOf(a, b, c))
        val timer = scheduler.tasks.single()
        queue.clear()
        assertTrue(timer.cancelled)
        assertTrue(queue.isIdle)
        timer.run() // even a timer that was already queued on the main looper does nothing
        assertFalse(complete(a))
        assertTrue("nothing reported for dropped operations", finished.isEmpty())
        assertEquals(0, drained)
        assertEquals("b and c never started", listOf<GattOp>(a), started)
    }

    @Test
    fun theQueueIsReusableAfterAClear() {
        queue.enqueue(listOf(a))
        queue.clear()
        queue.enqueue(listOf(b))
        assertEquals(listOf<GattOp>(a, b), started)
        complete(b)
        assertEquals(1, drained)
    }

    @Test
    fun operationsSubmittedWhileBusyJoinTheEndOfTheQueue() {
        queue.enqueue(listOf(a))
        queue.enqueue(listOf(b))
        assertEquals(listOf<GattOp>(a), started)
        complete(a)
        assertEquals(listOf<GattOp>(a, b), started)
        complete(b)
        assertEquals(1, drained)
    }

    @Test
    fun anEmptyBatchDrainsImmediately() {
        queue.enqueue(emptyList())
        assertEquals(1, drained)
    }

    @Test
    fun anExecutorThatThrowsIsAFailureNotACrash() {
        val q = GattOpQueue(1_000, scheduler, { throw IllegalStateException("gatt closed") }, { op, r -> finished += op to r }, { drained++ })
        q.enqueue(listOf(a))
        assertEquals(listOf(a to GattOpResult.Failed), finished)
        assertEquals(1, drained)
    }

    @Test
    fun aListenerThatClearsTheQueueFromACallbackLeavesItEmptyAndSilent() {
        lateinit var q: GattOpQueue
        q = GattOpQueue(
            timeoutMs = 1_000,
            scheduler = scheduler,
            execute = { started += it; true },
            onFinished = { op, r -> finished += op to r; q.clear() }, // e.g. an error path closing the connection
            onDrained = { drained++ },
        )
        q.enqueue(listOf(a, b))
        q.complete(a.kind, a.characteristic, false)
        assertEquals(listOf<GattOp>(a), started)
        assertEquals(0, drained)
        assertTrue(q.isIdle)
    }

    @Test
    fun aCompletionDeliveredFromInsideTheExecutorIsNotLost() {
        lateinit var q: GattOpQueue
        q = GattOpQueue(
            timeoutMs = 1_000,
            scheduler = scheduler,
            execute = { op -> started += op; q.complete(op.kind, op.characteristic, true); true }, // the stack answers on the caller's thread
            onFinished = { op, r -> finished += op to r },
            onDrained = { drained++ },
        )
        q.enqueue(listOf(a, b, c))
        assertEquals(listOf<GattOp>(a, b, c), started)
        assertEquals(listOf(a to GattOpResult.Success, b to GattOpResult.Success, c to GattOpResult.Success), finished)
        assertEquals(1, drained)
        assertTrue(q.isIdle)
        assertTrue("no timer left running", scheduler.live.isEmpty())
    }

    @Test
    fun theMtuRequestIsMatchedByKindAlone() {
        val mtu = GattOp.RequestMtu(247)
        queue.enqueue(listOf(mtu, a))
        assertTrue(queue.complete(GattOpKind.RequestMtu, null, true))
        assertEquals(listOf<GattOp>(mtu, a), started)
    }
}
