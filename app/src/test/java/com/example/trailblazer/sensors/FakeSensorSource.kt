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

    override fun triggers(type: Int): Flow<Unit> =
        counted(type, if (has(type)) triggerStream(type) else null) ?: kotlinx.coroutines.flow.emptyFlow()

    private fun <T> counted(type: Int, flow: Flow<T>?): Flow<T>? = flow
        ?.onStart { listeners[type] = (listeners[type] ?: 0) + 1 }
        ?.onCompletion { listeners[type] = (listeners[type] ?: 1) - 1 }

    fun emit(type: Int, vararg values: Float, accuracy: Int = 3, timestampNs: Long = System.nanoTime()) {
        check(stream(type).tryEmit(SensorSample(values, accuracy, timestampNs)))
    }

    /** Fires a trigger sensor; returns false when nobody is listening for it. */
    fun trigger(type: Int): Boolean = triggerStream(type).subscriptionCount.value > 0 && triggerStream(type).tryEmit(Unit)

    fun listenerCount(type: Int) = listeners[type] ?: 0
}
