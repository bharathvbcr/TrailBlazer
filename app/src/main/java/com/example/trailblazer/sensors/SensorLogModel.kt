package com.example.trailblazer.sensors

import android.os.SystemClock

/**
 * Hard bounds of one recording. A recording stops by itself, with a visible reason, at the first of them.
 *
 * - [maxBytes]: the file size (200 MiB, about 90 minutes of accelerometer and gyroscope at 200 Hz).
 * - [maxDurationMs]: wall time (4 hours).
 * - [minFreeBytes]: storage left; below it a recording does not start and a running one stops (64 MiB).
 * - [queueCapacity]: events waiting for the writer. When it is full the NEWEST event is dropped and counted.
 */
data class LogLimits(
    val maxBytes: Long = 200L * 1024 * 1024,
    val maxDurationMs: Long = 4L * 3_600_000L,
    val minFreeBytes: Long = 64L * 1024 * 1024,
    val queueCapacity: Int = 16_384,
    val flushIntervalMs: Long = 1_000L,
    val statsIntervalMs: Long = 250L,
) {
    init {
        require(maxBytes > 0 && maxDurationMs > 0 && minFreeBytes >= 0 && queueCapacity > 0 && flushIntervalMs > 0 && statsIntervalMs > 0)
    }
}

/**
 * Requested sampling rates. Android 12+ limits the accelerometer, gyroscope and magnetometer to 200 Hz for an app that
 * does not hold `HIGH_SAMPLING_RATE_SENSORS` (and asking for more can throw a SecurityException). TrailBlazer does not
 * declare that permission, so [motionHz] can never be raised past [MAX_MOTION_HZ]. The system may deliver slower than
 * requested (a sensor's `minDelay`); the closing row of the file records what each sensor really delivered.
 */
data class SamplingRates(val motionHz: Int = MAX_MOTION_HZ, val barometerHz: Int = 25) {
    init {
        require(motionHz in 1..MAX_MOTION_HZ) { "motionHz must be 1..$MAX_MOTION_HZ without HIGH_SAMPLING_RATE_SENSORS" }
        require(barometerHz in 1..MAX_MOTION_HZ)
    }

    companion object {
        const val MAX_MOTION_HZ = 200
    }
}

/** Why a recording ended. [message] is shown to the user. */
data class StopReason(val kind: Kind, val detail: String = "") {
    enum class Kind(val code: String) {
        UserStopped("user"),
        SizeCap("size_cap"),
        DurationCap("duration_cap"),
        DiskLow("disk_low"),
        WriteFailed("write_failed"),
        Cancelled("cancelled"),
        Interrupted("interrupted"),

        /** A closed recording found at start-up that was never saved. */
        Earlier("earlier"),
    }

    val code: String get() = kind.code

    val message: String
        get() = when (kind) {
            Kind.UserStopped -> "Stopped by you."
            Kind.SizeCap -> "Stopped automatically: the recording reached its size limit ($detail)."
            Kind.DurationCap -> "Stopped automatically: the recording reached its time limit ($detail)."
            Kind.DiskLow -> "Stopped automatically: the phone is almost out of storage."
            Kind.WriteFailed -> "Stopped: the recording could not be written to storage${if (detail.isEmpty()) "" else " ($detail)"}."
            Kind.Cancelled -> "Stopped: the app cancelled the recording."
            Kind.Interrupted -> "The app was closed or stopped while this was recording, so it may end early."
            Kind.Earlier -> "Recorded earlier and not saved yet."
        }
}

/** What a recording holds so far. [dropped] counts events lost because the writer could not keep up; non-zero means gaps. */
data class LogStats(val elapsedMs: Long, val written: Long, val dropped: Long, val bytes: Long) {
    companion object {
        val Zero = LogStats(0L, 0L, 0L, 0L)
    }
}

sealed interface LoggerState {
    /** Nothing recording and nothing waiting to be saved. */
    data object Idle : LoggerState

    /** [sources] lists what is being recorded; [skipped] what was not and why. [stopping] while the last events are written. */
    data class Recording(
        val stats: LogStats,
        val sources: List<String>,
        val skipped: List<String>,
        val stopping: Boolean = false,
    ) : LoggerState

    /**
     * A closed recording waiting to be saved or discarded. [stats] is null for one found after a restart. It stays until
     * the user saves it successfully or discards it.
     */
    data class Finished(
        val log: PendingLog,
        val stats: LogStats?,
        val reason: StopReason,
        val exporting: Boolean = false,
    ) : LoggerState
}

/** Clocks the logger reads, injectable for tests. */
interface LogClocks {
    fun wallMs(): Long
    fun elapsedNs(): Long

    /** Monotonic milliseconds for flush pacing. */
    fun monotonicMs(): Long

    object System : LogClocks {
        override fun wallMs(): Long = java.lang.System.currentTimeMillis()
        override fun elapsedNs(): Long = SystemClock.elapsedRealtimeNanos()
        override fun monotonicMs(): Long = java.lang.System.nanoTime() / 1_000_000L
    }
}
