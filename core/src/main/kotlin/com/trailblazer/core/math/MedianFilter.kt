package com.trailblazer.core.math

/** Running median over the last [size] finite values; rejects single-sample spikes (e.g. a door slam on a barometer). */
class MedianFilter(private val size: Int = 5) {
    init {
        require(size in 1..101 && size % 2 == 1) { "size must be odd" }
    }

    private val window = ArrayDeque<Double>(size)

    fun reset() = window.clear()

    /** Adds a value and returns the current median, or null if the value was not finite and nothing is buffered. */
    fun update(v: Double): Double? {
        if (v.isFinite()) {
            if (window.size == size) window.removeFirst()
            window.addLast(v)
        }
        if (window.isEmpty()) return null
        val sorted = window.sorted()
        return sorted[sorted.size / 2]
    }
}
