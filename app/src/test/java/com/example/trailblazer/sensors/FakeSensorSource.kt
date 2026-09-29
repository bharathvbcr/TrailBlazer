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
    val listeners = HashMap<Int, Int>()

    private fun stream(type: Int) = streams.getOrPut(type) { MutableSharedFlow(extraBufferCapacity = 100_000) }

    override fun has(type: Int) = type in present
    override fun info(type: Int): SensorInfo? = if (has(type)) SensorInfo(type, "fake-$type", "test", 1, 1f, 0.01f, 0.1f, 10_000, false) else null
    override fun all(): List<SensorInfo> = present.mapNotNull { info(it) }

    override fun samples(type: Int, periodUs: Int): Flow<SensorSample> {
        if (!has(type)) return kotlinx.coroutines.flow.emptyFlow()
        return stream(type)
            .onStart { listeners[type] = (listeners[type] ?: 0) + 1 }
            .onCompletion { listeners[type] = (listeners[type] ?: 1) - 1 }
            .buffer(Channel.CONFLATED)
    }

    fun emit(type: Int, vararg values: Float, accuracy: Int = 3) {
        check(stream(type).tryEmit(SensorSample(values, accuracy, System.nanoTime())))
    }

    fun listenerCount(type: Int) = listeners[type] ?: 0
}
