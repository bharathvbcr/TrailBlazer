package com.example.trailblazer.sensors

import android.hardware.Sensor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import com.trailblazer.core.sensors.log.ClockAnchor
import com.trailblazer.core.sensors.log.GnssMeasurementRow
import com.trailblazer.core.sensors.log.LogEvent
import com.trailblazer.core.sensors.log.LogSensor
import com.trailblazer.core.sensors.log.MarkerKind
import com.trailblazer.core.sensors.log.MarkerRow
import com.trailblazer.core.sensors.log.SensorCsv
import com.trailblazer.core.sensors.log.SensorEventRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * Records raw accelerometer, gyroscope and barometer events, and raw GNSS measurements where the device reports them,
 * to a CSV file in app storage, at the highest rate allowed without `HIGH_SAMPLING_RATE_SENSORS`. See docs/SENSORS.md
 * ("Sensor stream logger") for the file format, the rates and the guarantees.
 *
 * **Threads.** A sensor callback does one thing: `trySend` onto a bounded queue ([LogLimits.queueCapacity]). It never
 * touches the disk and never blocks. One writer coroutine on [io] drains the queue into a `BufferedWriter`. When the
 * queue is full the NEWEST event is dropped and counted ([LogStats.dropped]); the writer then puts a `GAP` row in the
 * file, and the closing row carries the totals, so a lossy log is never presented as complete.
 *
 * **Lifetime.** One instance for the process (see `AppContainer`), not tied to a screen: leaving Diagnostics does not
 * stop a recording, and no screen owns a listener, so none can leak. The session stops when the user stops it, at a
 * limit, on a write failure, or when the process ends (leaving a `.part` file that [SensorLogFiles.recover] repairs at
 * the next start). No foreground service is used, so Android 9+ withholds events from sensors while the app is in the
 * background; `APP_BACKGROUND` / `APP_FOREGROUND` rows mark those stretches.
 *
 * Timestamps: `elapsed_ns` is the sensor clock (`SystemClock.elapsedRealtimeNanos` time base); `utc_iso` is that clock
 * anchored once to the wall clock at the start ([ClockAnchor]).
 */
class SensorLogger(
    private val sensors: SensorSource,
    private val gnss: GnssMeasurementSource,
    private val files: SensorLogFiles,
    private val exporter: SensorLogExporter,
    private val scope: CoroutineScope,
    private val foreground: StateFlow<Boolean>,
    private val device: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val limits: LogLimits = LogLimits(),
    private val rates: SamplingRates = SamplingRates(),
    private val clocks: LogClocks = LogClocks.System,
    private val newCallbackThread: () -> HandlerThread? = { HandlerThread("tb-sensor-log").apply { start() } },
) {
    private val lock = Any()
    @Volatile private var session: Session? = null
    private var exportJob: Job? = null

    private val _state = MutableStateFlow<LoggerState>(LoggerState.Idle)
    val state: StateFlow<LoggerState> = _state

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-line result for the user (a save succeeded or failed, a recording could not start), until [clearNotice]. */
    val notice: StateFlow<String?> = _notice

    init {
        scope.launch(io) { synchronized(lock) { refreshPending() } }
    }

    /** Starts a recording. Returns null on success, otherwise the reason it did not start (also set as [notice]). */
    fun start(): String? = synchronized(lock) {
        fun refuse(message: String): String { _notice.value = message; return message }
        if (session != null || _state.value !is LoggerState.Idle) {
            return refuse("Save or discard the current recording before starting another.")
        }
        val free = files.freeBytes()
        if (free < limits.minFreeBytes) {
            return refuse("Not enough free storage to record (${mib(free)} free, ${mib(limits.minFreeBytes)} needed).")
        }
        val wallMs = clocks.wallMs()
        val anchor = ClockAnchor(wallMs, clocks.elapsedNs())
        val file = try {
            files.create(fileStamp(wallMs))
        } catch (e: IOException) {
            return refuse("Could not create the recording file: ${e.message}.")
        }
        val s = Session(file, anchor)
        val thread = newCallbackThread()
        s.callbackThread.set(thread)
        val handler = thread?.let { Handler(it.looper) }
        val sources = ArrayList<String>()
        val skipped = ArrayList<String>()
        val notes = ArrayList<String>()
        for (sensor in LogSensor.entries) {
            val type = sensor.androidType()
            val hz = if (sensor == LogSensor.Barometer) rates.barometerHz else rates.motionHz
            val info = sensors.info(type)
            if (info == null) {
                skipped += "${sensor.label()}: no such sensor"
                notes += "${sensor.csvName.lowercase(Locale.ROOT)}=absent"
                continue
            }
            val periodUs = max(1_000_000 / hz, max(info.minDelayUs, 0))
            val handle = sensors.register(type, periodUs, 0, handler) { sample -> s.offer(SensorEventRow(sample.timestampNs, sensor, sample.values, sample.accuracy)) }
            if (handle == null) {
                skipped += "${sensor.label()}: the system refused the registration"
                notes += "${sensor.csvName.lowercase(Locale.ROOT)}=refused"
            } else {
                s.handles += handle
                sources += sensor.label()
                notes += "${sensor.csvName.lowercase(Locale.ROOT)}_period_us=$periodUs"
            }
        }
        when (val raw = gnss.register({ rows -> rows.forEach(s::offer) }, { status -> s.offer(MarkerRow(clocks.elapsedNs(), MarkerKind.GnssRawStatus, status)) })) {
            is GnssRawRegistration.Active -> {
                s.handles += raw.handle
                sources += "GNSS raw measurements"
                notes += "gnss_raw=on"
            }
            is GnssRawRegistration.Unavailable -> {
                skipped += "GNSS raw measurements: ${raw.reason}"
                notes += "gnss_raw=off(${raw.reason})"
            }
        }
        if (sources.isEmpty()) {
            s.releaseSources()
            files.delete(file)
            return refuse("Nothing to record: " + skipped.joinToString("; ") + ".")
        }
        s.startNote = buildString {
            append("csv_format=1;device=").append(device)
            append(";anchor_wall_ms=").append(anchor.wallMs).append(";anchor_elapsed_ns=").append(anchor.elapsedNs)
            append(";").append(notes.joinToString(";"))
            append(";app_foreground=").append(s.initialForeground)
            append(";max_bytes=").append(limits.maxBytes).append(";max_duration_ms=").append(limits.maxDurationMs)
            append(";queue_capacity=").append(limits.queueCapacity).append(";overflow=drop_newest")
        }
        s.sources = sources
        s.skipped = skipped
        session = s
        _notice.value = null
        _state.value = LoggerState.Recording(LogStats.Zero, sources, skipped)
        s.launchJobs()
        return null
    }

    /** Asks the recording to stop. The state becomes [LoggerState.Finished] once the last queued events are written. */
    fun stop() {
        session?.requestStop(StopReason(StopReason.Kind.UserStopped))
    }

    /**
     * Copies the finished recording to [destination], the document the user chose. A null [destination] is the user
     * cancelling the picker: nothing changes. The private file is deleted only after the copy succeeded.
     */
    fun export(destination: Uri?): Job? = synchronized(lock) {
        val current = _state.value as? LoggerState.Finished ?: return null
        if (destination == null) {
            _notice.value = "Save cancelled. The recording is still on this phone."
            return null
        }
        if (current.exporting) return null
        _state.value = current.copy(exporting = true)
        _notice.value = null
        val job = scope.launch {
            val outcome = try {
                exporter.copy(current.log.file, destination)
            } catch (e: CancellationException) {
                markNotExporting("Save cancelled. The recording is still on this phone.")
                throw e
            }
            when (outcome) {
                is ExportOutcome.Saved -> withContext(NonCancellable + io) {
                    synchronized(lock) {
                        val removed = files.delete(current.log.file)
                        _notice.value = "Saved ${mib(outcome.bytes)} to the location you chose." +
                            if (removed) "" else " The private copy could not be deleted; discard it from here."
                        if (removed) {
                            _state.value = LoggerState.Idle
                            refreshPending()
                        } else {
                            _state.value = current.copy(exporting = false)
                        }
                    }
                }
                is ExportOutcome.Failed -> markNotExporting(outcome.message + " The recording is still on this phone.")
            }
        }
        exportJob = job
        job
    }

    /** Cancels a save in progress; the recording stays. */
    fun cancelExport() {
        exportJob?.cancel()
    }

    /** Deletes the finished recording. Only called for an explicit user choice. */
    fun discard(): Job = scope.launch(io) {
        synchronized(lock) {
            val current = _state.value as? LoggerState.Finished ?: return@synchronized
            if (current.exporting) return@synchronized
            if (files.delete(current.log.file)) {
                _notice.value = "Recording discarded."
                _state.value = LoggerState.Idle
                refreshPending()
            } else {
                _notice.value = "The recording could not be deleted."
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    private fun markNotExporting(message: String) {
        synchronized(lock) {
            _notice.value = message
            _state.update { if (it is LoggerState.Finished) it.copy(exporting = false) else it }
        }
    }

    /** Finds a recording left by an earlier run (repairing an orphan) and offers it. Call with [lock] held. */
    private fun refreshPending() {
        if (session != null || _state.value !is LoggerState.Idle) return
        for (p in files.pending()) {
            val log = if (p.interrupted) files.recover(p.file) else p
            if (log != null) {
                val reason = StopReason(if (p.interrupted) StopReason.Kind.Interrupted else StopReason.Kind.Earlier)
                _state.value = LoggerState.Finished(log, null, reason)
                return
            }
        }
    }

    private fun mib(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

    private fun fileStamp(wallMs: Long): String =
        SensorCsv.utcIso(wallMs * 1_000_000L).substringBefore('.').replace(":", "").replace("-", "")

    private fun LogSensor.androidType(): Int = when (this) {
        LogSensor.Accelerometer -> Sensor.TYPE_ACCELEROMETER
        LogSensor.Gyroscope -> Sensor.TYPE_GYROSCOPE
        LogSensor.Barometer -> Sensor.TYPE_PRESSURE
    }

    private fun LogSensor.label(): String = when (this) {
        LogSensor.Accelerometer -> "Accelerometer"
        LogSensor.Gyroscope -> "Gyroscope"
        LogSensor.Barometer -> "Barometer"
    }

    /** One recording's mutable state. Everything shared with callbacks is atomic or confined to the writer coroutine. */
    private inner class Session(val file: File, val anchor: ClockAnchor) {
        val startedNs = anchor.elapsedNs
        val initialForeground = foreground.value
        val queue = Channel<LogEvent>(limits.queueCapacity) // SUSPEND on full: trySend fails and the caller counts the drop
        val handles = java.util.concurrent.CopyOnWriteArrayList<AutoCloseable>()
        val callbackThread = AtomicReference<HandlerThread?>(null)
        var startNote = ""
        var sources: List<String> = emptyList()
        var skipped: List<String> = emptyList()

        val accepted = AtomicLong()
        val dropped = AtomicLong()
        val late = AtomicLong()
        val written = AtomicLong()
        val bytes = AtomicLong()
        val stopReason = AtomicReference<StopReason?>(null)
        private var ticker: Job? = null
        private var marks: Job? = null

        /** Called on sensor and GNSS threads. Never blocks, never touches the disk. */
        fun offer(e: LogEvent) {
            val r = queue.trySend(e)
            when {
                r.isSuccess -> accepted.incrementAndGet()
                r.isClosed -> late.incrementAndGet() // arrived after stop; not part of this recording
                else -> dropped.incrementAndGet()
            }
        }

        fun stats(): LogStats = LogStats(
            elapsedMs = max(0L, (clocks.elapsedNs() - startedNs) / 1_000_000L),
            written = written.get(),
            dropped = dropped.get(),
            bytes = bytes.get(),
        )

        /** First reason wins. Unregisters every source, then closes the queue so the writer drains what it holds and ends. */
        fun requestStop(reason: StopReason) {
            if (!stopReason.compareAndSet(null, reason)) return
            releaseSources()
            queue.close()
            _state.update { if (it is LoggerState.Recording) it.copy(stopping = true, stats = stats()) else it }
        }

        fun releaseSources() {
            // Called from the user's thread, the writer and the ticker; whoever gets there first closes each handle once.
            for (h in handles.toList()) if (handles.remove(h)) runCatching { h.close() }
            callbackThread.getAndSet(null)?.quitSafely()
        }

        fun launchJobs() {
            // ATOMIC: even a scope cancelled right now still enters runWriter, whose finally closes the file and the sources.
            scope.launch(io, start = CoroutineStart.ATOMIC) { runWriter() }
            ticker = scope.launch {
                while (isActive) {
                    delay(limits.statsIntervalMs)
                    if (stats().elapsedMs >= limits.maxDurationMs) requestStop(StopReason(StopReason.Kind.DurationCap, "${limits.maxDurationMs / 3_600_000.0} h"))
                    _state.update { if (it is LoggerState.Recording) it.copy(stats = stats()) else it }
                }
            }
            marks = scope.launch {
                // Compared with the value the start row recorded, not "the first value seen": a change before this
                // coroutine starts running would otherwise be swallowed as the initial value.
                var last = initialForeground
                foreground.collect { fg ->
                    if (fg != last) {
                        last = fg
                        offer(MarkerRow(clocks.elapsedNs(), if (fg) MarkerKind.AppForeground else MarkerKind.AppBackground, ""))
                    }
                }
            }
        }

        private suspend fun runWriter() {
            var writer: BufferedWriter? = null
            var ioFailed = false
            val perSensor = LongArray(LogSensor.entries.size)
            var gnssRows = 0L
            var droppedAtStop = 0L
            try {
                val w = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), WRITE_BUFFER)
                writer = w
                fun put(line: String) {
                    w.write(line)
                    bytes.addAndGet(SensorCsv.utf8Length(line).toLong())
                }
                put(SensorCsv.headerLine())
                put(SensorCsv.rowLine(MarkerRow(anchor.elapsedNs, MarkerKind.SessionStart, startNote), anchor))
                w.flush() // a file that exists is a file with its header
                var lastFlush = clocks.monotonicMs()
                var reportedDrops = 0L
                for (event in queue) {
                    val dropsNow = dropped.get()
                    if (dropsNow > reportedDrops) {
                        put(SensorCsv.rowLine(MarkerRow(event.elapsedNs, MarkerKind.Gap, "dropped=${dropsNow - reportedDrops};total_dropped=$dropsNow;queue_full"), anchor))
                        reportedDrops = dropsNow
                    }
                    put(SensorCsv.rowLine(event, anchor))
                    written.incrementAndGet()
                    when (event) {
                        is SensorEventRow -> perSensor[event.sensor.ordinal]++
                        is GnssMeasurementRow -> gnssRows++
                        else -> Unit
                    }
                    if (bytes.get() >= limits.maxBytes) {
                        requestStop(StopReason(StopReason.Kind.SizeCap, mib(limits.maxBytes)))
                        break
                    }
                    val now = clocks.monotonicMs()
                    if (now - lastFlush >= limits.flushIntervalMs) {
                        w.flush()
                        lastFlush = now
                        if (files.freeBytes() < limits.minFreeBytes) {
                            requestStop(StopReason(StopReason.Kind.DiskLow))
                            break
                        }
                    }
                }
            } catch (e: CancellationException) {
                requestStop(StopReason(StopReason.Kind.Cancelled))
                throw e
            } catch (e: Exception) {
                ioFailed = true
                requestStop(StopReason(StopReason.Kind.WriteFailed, e.message ?: e::class.java.simpleName))
            } finally {
                withContext(NonCancellable) {
                    // Events accepted but never written (the writer stopped early) are losses, not silently absent.
                    while (queue.tryReceive().isSuccess) droppedAtStop++
                    dropped.addAndGet(droppedAtStop)
                    finish(writer, ioFailed, perSensor, gnssRows, droppedAtStop)
                }
            }
        }

        private fun finish(writer: BufferedWriter?, ioFailed: Boolean, perSensor: LongArray, gnssRows: Long, droppedAtStop: Long) {
            requestStop(StopReason(StopReason.Kind.Cancelled)) // no-op when a reason is already set
            val reason = stopReason.get()!!
            val duration = max(0L, (clocks.elapsedNs() - startedNs) / 1_000_000L)
            var closed = false
            try {
                if (writer != null) {
                    val counts = LogSensor.entries.joinToString(",") { "${it.csvName}:${perSensor[it.ordinal]}" } + ",GNSS_MEASUREMENT:$gnssRows"
                    val rates = LogSensor.entries.joinToString(",") { s ->
                        "${s.csvName}:" + if (duration > 0) String.format(Locale.ROOT, "%.1f", perSensor[s.ordinal] * 1000.0 / duration) else "0.0"
                    }
                    val note = "stop_reason=${reason.code};accepted=${accepted.get()};written=${written.get()};" +
                        "dropped=${dropped.get()};dropped_at_stop=$droppedAtStop;late_after_stop=${late.get()};" +
                        "duration_ms=$duration;counts=$counts;observed_hz=$rates" +
                        if (reason.detail.isEmpty()) "" else ";detail=${reason.detail}"
                    val end = SensorCsv.rowLine(MarkerRow(clocks.elapsedNs(), MarkerKind.SessionEnd, note), anchor)
                    writer.write(end)
                    bytes.addAndGet(SensorCsv.utf8Length(end).toLong())
                }
                writer?.close()
                closed = true
            } catch (_: IOException) {
                // The closing row could not be written (disk full): the rows before it are intact, trimmed below.
            } finally {
                if (!closed) runCatching { writer?.close() }
            }
            // After a failed write or close the last row may be cut off; keep only whole rows.
            if (ioFailed || !closed) runCatching { files.trimPartialLine(file) }
            val sealed = files.seal(file)
            val finalStats = LogStats(duration, written.get(), dropped.get(), sealed.length())
            releaseSources()
            ticker?.cancel()
            marks?.cancel()
            synchronized(lock) {
                session = null
                _state.value = LoggerState.Finished(PendingLog(sealed, sealed.length(), interrupted = false), finalStats, reason)
            }
        }
    }

    private companion object {
        const val WRITE_BUFFER = 64 * 1024
    }
}
