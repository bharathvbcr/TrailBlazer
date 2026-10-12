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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
                s.addHandle(handle)
                sources += sensor.label()
                notes += "${sensor.csvName.lowercase(Locale.ROOT)}_period_us=$periodUs"
            }
        }
        when (val raw = gnss.register({ rows -> rows.forEach(s::offer) }, { status -> s.offer(MarkerRow(clocks.elapsedNs(), MarkerKind.GnssRawStatus, status)) })) {
            is GnssRawRegistration.Active -> {
                s.addHandle(raw.handle)
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
     * Stops a recording and cancels a save in progress, then waits until both have ended. False when they did not end
     * within [timeoutMs]; the caller must then not delete the files under them.
     */
    suspend fun stopAndAwait(timeoutMs: Long = STOP_TIMEOUT_MS): Boolean {
        cancelExport()
        val saving = exportJob
        if (saving != null && withTimeoutOrNull(timeoutMs) { saving.join() } == null) return false
        stop()
        return withTimeoutOrNull(timeoutMs) { state.first { it !is LoggerState.Recording } } != null
    }

    /**
     * Deletes every recording file on the phone (finished, unsaved and half-written) and returns to [LoggerState.Idle].
     * Used by "delete all data": raw GNSS with UTC timestamps is location history. Refuses (false) while a recording runs.
     */
    fun deleteAllFiles(): Boolean = synchronized(lock) {
        if (session != null) return false
        val clean = files.deleteAll()
        _notice.value = null
        if (clean) _state.value = LoggerState.Idle
        clean
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
            } catch (e: Exception) {
                // Nothing may escape into the application scope (an uncaught exception there ends the process).
                ExportOutcome.Failed("Could not save: ${e.message ?: e::class.java.simpleName}.")
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
            // A file that cannot be repaired now (read-only, disk full) is skipped, never allowed to fail start-up.
            val log = if (p.interrupted) runCatching { files.recover(p.file) }.getOrNull() else p
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
        val queue = Channel<Queued>(limits.queueCapacity) // SUSPEND on full: trySend fails and the caller counts the drop

        /** Child of the app scope that owns the writer, the ticker and the foreground watcher: ending it ends all three. */
        val job: Job = Job(scope.coroutineContext[Job])

        // Events dropped since the last one that was queued. Guarded by [gapLock]; the lock is held for a few
        // instructions and never across I/O, so a callback still never waits for the disk.
        private val gapLock = Any()
        private var openCount = 0L
        private var openFirstNs = 0L
        private var openLastNs = 0L

        /** Overflow drops the writer has not yet written as GAP rows (writer-confined). */
        var reportedGapTotal = 0L
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
        /**
         * Called on sensor and GNSS threads. Never blocks, never touches the disk. An event that does not fit is
         * dropped and counted; the next event that does fit carries the drops that precede it, so the writer puts the
         * GAP row exactly where the loss happened, with the first and last dropped time.
         */
        fun offer(e: LogEvent) {
            synchronized(gapLock) {
                val gap = if (openCount > 0) GapSnapshot(openCount, openFirstNs, openLastNs) else null
                val r = queue.trySend(Queued(e, gap))
                when {
                    r.isSuccess -> {
                        accepted.incrementAndGet()
                        if (gap != null) openCount = 0
                    }
                    r.isClosed -> late.incrementAndGet() // arrived after stop; not part of this recording
                    else -> {
                        dropped.incrementAndGet()
                        if (openCount == 0L) { openFirstNs = e.elapsedNs; openLastNs = e.elapsedNs }
                        else { openFirstNs = minOf(openFirstNs, e.elapsedNs); openLastNs = maxOf(openLastNs, e.elapsedNs) }
                        openCount++
                    }
                }
            }
        }

        /** The drops after the last queued event (and any carried by events that were never written), once, at the end. */
        fun takeOpenGap(): GapSnapshot? = synchronized(gapLock) {
            if (openCount == 0L) null else GapSnapshot(openCount, openFirstNs, openLastNs).also { openCount = 0 }
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

        /** A source registered while start() is still running; if the session already ended meanwhile, release it at once. */
        fun addHandle(handle: AutoCloseable) {
            handles += handle
            if (stopReason.get() != null) releaseSources()
        }

        fun releaseSources() {
            // Called from the user's thread, the writer and the ticker; whoever gets there first closes each handle once.
            for (h in handles.toList()) if (handles.remove(h)) runCatching { h.close() }
            callbackThread.getAndSet(null)?.quitSafely()
        }

        fun launchJobs() {
            // ATOMIC: even a scope cancelled right now still enters runWriter, whose finally closes the file and the sources.
            scope.launch(job + io, start = CoroutineStart.ATOMIC) { runWriter() }
            scope.launch(job) {
                while (isActive) {
                    delay(limits.statsIntervalMs)
                    if (stats().elapsedMs >= limits.maxDurationMs) requestStop(StopReason(StopReason.Kind.DurationCap, "${limits.maxDurationMs / 3_600_000.0} h"))
                    _state.update { if (it is LoggerState.Recording) it.copy(stats = stats()) else it }
                }
            }
            scope.launch(job) {
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
            var tail: GapSnapshot? = null
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
                var dirty = false
                while (true) {
                    var received = queue.tryReceive()
                    if (!received.isSuccess && !received.isClosed) {
                        // Nothing queued. Wait for the next event, but no longer than the flush interval: rows that are
                        // already written must reach the disk, and storage must be checked, even when the sensors go quiet.
                        val wait = (limits.flushIntervalMs - (clocks.monotonicMs() - lastFlush)).coerceAtLeast(1L)
                        val waited = withTimeoutOrNull(wait) { queue.receiveCatching() }
                        if (waited == null) {
                            if (dirty) { w.flush(); dirty = false }
                            lastFlush = clocks.monotonicMs()
                            if (files.freeBytes() < limits.minFreeBytes) {
                                requestStop(StopReason(StopReason.Kind.DiskLow))
                                break
                            }
                            continue
                        }
                        received = waited
                    }
                    val item = received.getOrNull() ?: break // closed and empty: everything accepted was written
                    item.gapBefore?.let { g ->
                        reportedGapTotal += g.count
                        put(SensorCsv.rowLine(MarkerRow(g.firstNs, MarkerKind.Gap, gapNote(g, reportedGapTotal)), anchor))
                    }
                    val event = item.event
                    put(SensorCsv.rowLine(event, anchor))
                    written.incrementAndGet()
                    dirty = true
                    when (event) {
                        is SensorEventRow -> perSensor[event.sensor.ordinal]++
                        is GnssMeasurementRow -> gnssRows++
                        is MarkerRow -> if (event.kind == MarkerKind.AppBackground || event.kind == MarkerKind.AppForeground) {
                            // The app is about to be frozen or has just resumed: make what is written durable at once.
                            w.flush(); dirty = false; lastFlush = clocks.monotonicMs()
                        }
                    }
                    if (bytes.get() >= limits.maxBytes) {
                        requestStop(StopReason(StopReason.Kind.SizeCap, mib(limits.maxBytes)))
                        break
                    }
                    val now = clocks.monotonicMs()
                    if (now - lastFlush >= limits.flushIntervalMs) {
                        w.flush()
                        dirty = false
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
                    // Events accepted but never written (the writer stopped early) are losses, not silently absent; the
                    // drops they carried are still unreported, so they join the closing GAP row.
                    while (true) {
                        val item = queue.tryReceive().getOrNull() ?: break
                        droppedAtStop++
                        item.gapBefore?.let { tail = tail.plus(it) }
                    }
                    takeOpenGap()?.let { tail = tail.plus(it) }
                    dropped.addAndGet(droppedAtStop)
                    finish(writer, ioFailed, perSensor, gnssRows, droppedAtStop, tail)
                }
            }
        }

        private fun gapNote(g: GapSnapshot, total: Long) =
            "dropped=${g.count};first_dropped_ns=${g.firstNs};last_dropped_ns=${g.lastNs};total_dropped=$total;queue_full"

        private fun finish(writer: BufferedWriter?, ioFailed: Boolean, perSensor: LongArray, gnssRows: Long, droppedAtStop: Long, tail: GapSnapshot?) {
            requestStop(StopReason(StopReason.Kind.Cancelled)) // no-op when a reason is already set
            val reason = stopReason.get()!!
            val duration = max(0L, (clocks.elapsedNs() - startedNs) / 1_000_000L)
            var closed = false
            try {
                if (writer != null) {
                    if (tail != null) {
                        reportedGapTotal += tail.count
                        val gap = SensorCsv.rowLine(MarkerRow(tail.firstNs, MarkerKind.Gap, gapNote(tail, reportedGapTotal) + ";at=end"), anchor)
                        writer.write(gap)
                        bytes.addAndGet(SensorCsv.utf8Length(gap).toLong())
                    }
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
            synchronized(lock) {
                session = null
                _state.value = LoggerState.Finished(PendingLog(sealed, sealed.length(), interrupted = false), finalStats, reason)
            }
            job.cancel() // the ticker and the foreground watcher; the writer is on its way out of this very call
        }
    }

    /** An event on its way to the writer, with the overflow drops that happened just before it was queued. */
    private class Queued(val event: LogEvent, val gapBefore: GapSnapshot?)

    /** [count] events were dropped between [firstNs] and [lastNs] (sensor-clock time of the first and last one dropped). */
    private class GapSnapshot(val count: Long, val firstNs: Long, val lastNs: Long)

    private fun GapSnapshot?.plus(other: GapSnapshot): GapSnapshot =
        if (this == null) other else GapSnapshot(count + other.count, minOf(firstNs, other.firstNs), maxOf(lastNs, other.lastNs))

    private companion object {
        const val WRITE_BUFFER = 64 * 1024
        const val STOP_TIMEOUT_MS = 10_000L
    }
}
