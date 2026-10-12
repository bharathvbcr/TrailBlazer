package com.example.trailblazer.sensors

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/** In-memory [SensorSource]: tests push samples and observe how many listeners are registered. */
class FakeSensorSource(private val present: Set<Int>) : SensorSource {
    private val streams = HashMap<Int, MutableSharedFlow<SensorSample>>()
    private val triggerStreams = HashMap<Int, MutableSharedFlow<Unit>>()
    val listeners = HashMap<Int, Int>()

    private fun stream(type: Int) = streams.getOrPut(type) { MutableSharedFlow(extraBufferCapacity = 100_000) }
    private fun triggerStream(type: Int) = triggerStreams.getOrPut(type) { MutableSharedFlow(extraBufferCapacity = 16) }

    override fun has(type: Int) = type in present
    override fun info(type: Int): SensorInfo? = if (has(type)) SensorInfo(type, "fake-$type", "test", 1, 1f, 0.01f, 0.1f, 10_000, false) else null
    override fun all(): List<SensorInfo> = present.mapNotNull { info(it) }

    override fun samples(type: Int, periodUs: Int): Flow<SensorSample> =
        counted(type, if (has(type)) stream(type) else null)?.buffer(Channel.CONFLATED) ?: kotlinx.coroutines.flow.emptyFlow()

    override fun batchedSamples(type: Int, periodUs: Int, maxReportLatencyUs: Int): Flow<SensorSample> =
        counted(type, if (has(type)) stream(type) else null) ?: kotlinx.coroutines.flow.emptyFlow()

    private val callbacks = HashMap<Int, MutableList<(SensorSample) -> Unit>>()

    /** Registrations made through [register], with the period each asked for (the last one per type). */
    val requestedPeriodUs = HashMap<Int, Int>()

    /** Events handed to a [register] callback, per type: what the platform "delivered". */
    val delivered = HashMap<Int, java.util.concurrent.atomic.AtomicLong>()

    /** Runs inside [register] after the callback is installed, to deliver events while a recording is still starting. */
    var onRegister: ((Int) -> Unit)? = null

    override fun register(type: Int, periodUs: Int, maxReportLatencyUs: Int, handler: android.os.Handler?, onSample: (SensorSample) -> Unit): AutoCloseable? {
        if (!has(type)) return null
        val list = callbacks.getOrPut(type) { java.util.concurrent.CopyOnWriteArrayList() }
        list += onSample
        requestedPeriodUs[type] = periodUs
        listeners[type] = (listeners[type] ?: 0) + 1
        onRegister?.invoke(type)
        return AutoCloseable {
            if (list.remove(onSample)) listeners[type] = (listeners[type] ?: 1) - 1
        }
    }

    override fun triggers(type: Int): Flow<Unit> =
        counted(type, if (has(type)) triggerStream(type) else null) ?: kotlinx.coroutines.flow.emptyFlow()

    private fun <T> counted(type: Int, flow: Flow<T>?): Flow<T>? = flow
        ?.onStart { listeners[type] = (listeners[type] ?: 0) + 1 }
        ?.onCompletion { listeners[type] = (listeners[type] ?: 1) - 1 }

    fun emit(type: Int, vararg values: Float, accuracy: Int = 3, timestampNs: Long = System.nanoTime()) {
        val sample = SensorSample(values, accuracy, timestampNs)
        check(stream(type).tryEmit(sample))
        // Iterate the CopyOnWriteArrayList itself (a snapshot): toList() reads size then get(0), which races with a
        // listener the logger removes on its own thread when it stops mid-burst.
        callbacks[type]?.forEach {
            delivered.getOrPut(type) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
            it(sample)
        }
    }

    /** Fires a trigger sensor; returns false when nobody is listening for it. */
    fun trigger(type: Int): Boolean = triggerStream(type).subscriptionCount.value > 0 && triggerStream(type).tryEmit(Unit)

    fun listenerCount(type: Int) = listeners[type] ?: 0
}
