package com.example.trailblazer.sensors

import android.hardware.Sensor
import com.trailblazer.core.sensors.log.GnssMeasurementRow
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

const val ACCEL = Sensor.TYPE_ACCELEROMETER
const val GYRO = Sensor.TYPE_GYROSCOPE
const val BARO = Sensor.TYPE_PRESSURE

/** Wall clock and sensor clock the test moves by hand. */
class FakeLogClocks(val wall: Long = 1_700_000_000_123L, start: Long = 5_000_000_000L) : LogClocks {
    val elapsed = AtomicLong(start)
    override fun wallMs() = wall
    override fun elapsedNs() = elapsed.get()
    override fun monotonicMs() = System.nanoTime() / 1_000_000L
}

class FakeGnss(var available: String? = null) : GnssMeasurementSource {
    private var onRows: ((List<GnssMeasurementRow>) -> Unit)? = null
    private var onStatus: ((String) -> Unit)? = null
    val registrations = AtomicInteger()
    val closed = AtomicInteger()

    override fun register(onRows: (List<GnssMeasurementRow>) -> Unit, onStatus: (String) -> Unit): GnssRawRegistration {
        available?.let { return GnssRawRegistration.Unavailable(it) }
        registrations.incrementAndGet()
        this.onRows = onRows
        this.onStatus = onStatus
        return GnssRawRegistration.Active(AutoCloseable { closed.incrementAndGet(); this.onRows = null })
    }

    fun emit(rows: List<GnssMeasurementRow>) = onRows?.invoke(rows)
    fun status(text: String) = onStatus?.invoke(text)
}

/** A logger wired to fakes, writing into [dir]. [job] is the app scope: cancel it to simulate the scope ending. */
class LoggerFixture(
    val dir: File,
    present: Set<Int> = setOf(ACCEL, GYRO, BARO),
    val limits: LogLimits = LogLimits(statsIntervalMs = 20, flushIntervalMs = 10),
    io: CoroutineDispatcher = Dispatchers.IO,
    freeBytes: () -> Long = { Long.MAX_VALUE },
    val gnss: FakeGnss = FakeGnss(),
    val exporter: SensorLogExporter = SensorLogExporter({ null }, {}, Long.MAX_VALUE),
    val rates: SamplingRates = SamplingRates(),
) {
    val sensors = FakeSensorSource(present)
    val clocks = FakeLogClocks()
    val foreground = MutableStateFlow(true)
    val job: CompletableJob = SupervisorJob()
    val files = SensorLogFiles(dir, freeBytes)
    val logger = SensorLogger(
        sensors = sensors, gnss = gnss, files = files, exporter = exporter,
        scope = CoroutineScope(job + Dispatchers.Default), foreground = foreground, device = "Test Phone (Android 16, API 36)",
        io = io, limits = limits, rates = rates, clocks = clocks, newCallbackThread = { null },
    )

    fun emitAccel(n: Int, firstNs: Long = 5_001_000_000L, stepNs: Long = 5_000_000L) {
        for (i in 0 until n) sensors.emit(ACCEL, 0.1f * (i % 10), 9.8f, -0.2f, accuracy = 3, timestampNs = firstNs + i * stepNs)
    }
}

/** Waits for [condition] (work runs on other threads), failing with [what] after [timeoutMs]. */
fun waitFor(what: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting for: $what")
        Thread.sleep(5)
    }
}

fun SensorLogger.awaitFinished(): LoggerState.Finished {
    waitFor("the recording to finish") { state.value is LoggerState.Finished }
    return state.value as LoggerState.Finished
}

/** A minimal RFC 4180 reader: what the logger writes must read back as complete records of the header's width. */
fun parseCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
            inQuotes && c == '"' -> inQuotes = false
            inQuotes -> cell.append(c)
            c == '"' -> inQuotes = true
            c == ',' -> { row.add(cell.toString()); cell.clear() }
            c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList(); i++ }
            else -> cell.append(c)
        }
        i++
    }
    check(!inQuotes && cell.isEmpty() && row.isEmpty()) { "the file ends inside a record" }
    return rows
}

fun readCsv(file: File): List<List<String>> = parseCsv(file.readText(Charsets.UTF_8))

/** `key=value;key=value` note into a map (values may contain `=`, never `;` in what the logger writes). */
fun notes(cell: String): Map<String, String> = cell.split(';').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

const val TYPE_COL = 2
const val NOTE_COL = 23
