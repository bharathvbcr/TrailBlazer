package com.trailblazer.core.sensors.log

import com.trailblazer.core.time.CivilDate

/**
 * One sensor kind the logger records, with the CSV name written in `sensor_type`. The values go to `x`, `y`, `z` in
 * order; a one-value sensor fills `x` only.
 *
 * Units: accelerometer m/s² (including gravity), gyroscope rad/s, barometer hPa.
 */
enum class LogSensor(val csvName: String) {
    Accelerometer("ACCELEROMETER"),
    Gyroscope("GYROSCOPE"),
    Barometer("PRESSURE"),
}

/** Rows that are not sensor samples. Their text is in the `note` column as `key=value;key=value`. */
enum class MarkerKind(val csvName: String) {
    SessionStart("SESSION_START"),
    SessionEnd("SESSION_END"),

    /** Samples were dropped because the writer could not keep up; `note` says how many since the previous row. */
    Gap("GAP"),
    AppForeground("APP_FOREGROUND"),
    AppBackground("APP_BACKGROUND"),

    /** The platform reported that raw GNSS measurements are not supported or not allowed. */
    GnssRawStatus("GNSS_RAW_STATUS"),
}

/** One row of the log. [elapsedNs] is on the `SystemClock.elapsedRealtimeNanos()` time base. */
sealed interface LogEvent {
    val elapsedNs: Long
}

/**
 * A raw sensor event. [values] has every value the sensor reported, unfiltered (a NaN stays NaN, so a driver glitch is
 * visible in the data); [accuracy] is the raw `SensorEvent.accuracy` (0 unreliable … 3 high).
 */
class SensorEventRow(
    override val elapsedNs: Long,
    val sensor: LogSensor,
    val values: FloatArray,
    val accuracy: Int,
) : LogEvent

/** A marker row; [note] is flattened to one line. */
class MarkerRow(override val elapsedNs: Long, val kind: MarkerKind, val note: String) : LogEvent

/**
 * One satellite signal from a `GnssMeasurementsEvent`, with the receiver clock fields needed to compute a
 * pseudorange offline. Nothing is derived here: a pseudorange needs week/day rollover and constellation time-system
 * handling that belongs in the analysis, and a guess in the logger would look like a measurement.
 *
 * Optional fields are null when the chipset does not report them (`has*()` was false); they are written as empty cells.
 *
 * - GPS time (ns) = [clockTimeNs] − ([fullBiasNs] + [biasNs]); measurement time = [clockTimeNs] + [timeOffsetNs].
 * - [receivedSvTimeNs] is the satellite's time of transmission, relative to the start of its system week (GLONASS: day).
 * - [elapsedNs] is the clock's `elapsedRealtimeNanos` when the chipset reports it; otherwise it is the time the callback
 *   ran and [note] says `elapsed=callback`.
 */
class GnssMeasurementRow(
    override val elapsedNs: Long,
    val constellation: Int,
    val svid: Int,
    val cn0DbHz: Double,
    val receivedSvTimeNs: Long,
    val receivedSvTimeUncertaintyNs: Long,
    val state: Int,
    val pseudorangeRateMps: Double,
    val pseudorangeRateUncertaintyMps: Double,
    val timeOffsetNs: Double,
    val accumulatedDeltaRangeM: Double,
    val accumulatedDeltaRangeState: Int,
    val carrierFrequencyHz: Float?,
    val clockTimeNs: Long?,
    val fullBiasNs: Long?,
    val biasNs: Double?,
    val hardwareClockDiscontinuityCount: Int,
    val note: String = "",
) : LogEvent

/**
 * Converts the monotonic sensor time base to UTC with one pair captured together at session start:
 * `utc = wallMs + (elapsed − anchorElapsed)`. The wall clock is read once, so a later NTP step or a manual clock change
 * cannot make the timestamps jump or run backwards; the cost is that they follow the monotonic clock's rate, and the
 * two captured instants differ by well under a millisecond.
 */
class ClockAnchor(val wallMs: Long, val elapsedNs: Long) {
    init {
        require(wallMs in 0..MAX_WALL_MS) { "wallMs out of range: $wallMs" }
    }

    /** Nanoseconds since the Unix epoch (UTC) for a sensor-clock instant. */
    fun utcNanos(eventElapsedNs: Long): Long = wallMs * NS_PER_MS + (eventElapsedNs - elapsedNs)

    private companion object {
        const val NS_PER_MS = 1_000_000L

        /** wallMs × 10⁶ must fit in a Long (year 2262). */
        const val MAX_WALL_MS = Long.MAX_VALUE / NS_PER_MS / 2
    }
}

/**
 * The CSV format of a sensor log: RFC 4180, CRLF line ends, UTF-8, a header row, then one row per event.
 *
 * Numbers are written with `toString()`, which never depends on a locale (no decimal commas); a Float is written as a
 * Float, so 0.1f reads 0.1 and not 0.10000000149011612. A NaN or infinite value is written as the literal `NaN`,
 * `Infinity` or `-Infinity`, never as an empty cell: empty means "not reported".
 */
object SensorCsv {
    val COLUMNS: List<String> = listOf(
        "utc_iso", "elapsed_ns", "sensor_type", "x", "y", "z", "accuracy",
        "svid", "constellation", "cn0_dbhz", "received_sv_time_ns", "received_sv_time_unc_ns", "state",
        "pseudorange_rate_mps", "pseudorange_rate_unc_mps", "time_offset_ns", "adr_m", "adr_state", "carrier_freq_hz",
        "clock_time_ns", "full_bias_ns", "bias_ns", "hw_clock_discontinuity", "note",
    )

    const val GNSS_TYPE = "GNSS_MEASUREMENT"
    private const val EOL = "\r\n"
    private const val NOTE = 23

    fun headerLine(): String = COLUMNS.joinToString(",") + EOL

    /** The line for [event], including its line end. */
    fun rowLine(event: LogEvent, anchor: ClockAnchor): String {
        val cells = Array(COLUMNS.size) { "" }
        cells[0] = utcIso(anchor.utcNanos(event.elapsedNs))
        cells[1] = event.elapsedNs.toString()
        when (event) {
            is SensorEventRow -> {
                cells[2] = event.sensor.csvName
                for (i in 0 until minOf(3, event.values.size)) cells[3 + i] = number(event.values[i])
                cells[6] = event.accuracy.toString()
                if (event.values.size > 3) {
                    cells[NOTE] = event.values.drop(3).mapIndexed { i, v -> "v${i + 3}=${number(v)}" }.joinToString(";")
                }
            }
            is MarkerRow -> {
                cells[2] = event.kind.csvName
                cells[NOTE] = oneLine(event.note)
            }
            is GnssMeasurementRow -> {
                cells[2] = GNSS_TYPE
                cells[7] = event.svid.toString()
                cells[8] = event.constellation.toString()
                cells[9] = number(event.cn0DbHz)
                cells[10] = event.receivedSvTimeNs.toString()
                cells[11] = event.receivedSvTimeUncertaintyNs.toString()
                cells[12] = event.state.toString()
                cells[13] = number(event.pseudorangeRateMps)
                cells[14] = number(event.pseudorangeRateUncertaintyMps)
                cells[15] = number(event.timeOffsetNs)
                cells[16] = number(event.accumulatedDeltaRangeM)
                cells[17] = event.accumulatedDeltaRangeState.toString()
                cells[18] = event.carrierFrequencyHz?.let(::number).orEmpty()
                cells[19] = event.clockTimeNs?.toString().orEmpty()
                cells[20] = event.fullBiasNs?.toString().orEmpty()
                cells[21] = event.biasNs?.let(::number).orEmpty()
                cells[22] = event.hardwareClockDiscontinuityCount.toString()
                cells[NOTE] = oneLine(event.note)
            }
        }
        return join(cells)
    }

    /** A marker with no timestamp, for a file repaired after the process died (the clock anchor is gone). */
    fun undatedMarkerLine(kind: MarkerKind, note: String): String {
        val cells = Array(COLUMNS.size) { "" }
        cells[2] = kind.csvName
        cells[NOTE] = oneLine(note)
        return join(cells)
    }

    private fun join(cells: Array<String>): String {
        val sb = StringBuilder(160)
        for (i in cells.indices) {
            if (i > 0) sb.append(',')
            sb.append(escape(cells[i]))
        }
        return sb.append(EOL).toString()
    }

    /** RFC 4180: a field with a comma, quote, CR or LF is quoted and its quotes doubled. */
    fun escape(field: String): String {
        if (field.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /**
     * Notes never contain a line break, so a file cut off by a crash can be repaired by trimming to its last line end
     * without ever cutting inside a quoted field.
     */
    internal fun oneLine(s: String): String = s.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')

    fun number(v: Double): String = when {
        v.isNaN() -> "NaN"
        v == Double.POSITIVE_INFINITY -> "Infinity"
        v == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> v.toString()
    }

    fun number(v: Float): String = when {
        v.isNaN() -> "NaN"
        v == Float.POSITIVE_INFINITY -> "Infinity"
        v == Float.NEGATIVE_INFINITY -> "-Infinity"
        else -> v.toString()
    }

    /** `yyyy-MM-ddTHH:mm:ss.ffffffZ`: always microseconds, so the column sorts as text. */
    fun utcIso(utcNanos: Long): String {
        val days = utcNanos.floorDiv(NS_PER_DAY)
        val nsOfDay = utcNanos.mod(NS_PER_DAY)
        val (y, mo, d) = CivilDate.civilFromDays(days)
        val sec = nsOfDay / 1_000_000_000L
        val micros = nsOfDay % 1_000_000_000L / 1_000L
        return buildString(27) {
            append(y.toString().padStart(4, '0')).append('-')
            append(mo.toString().padStart(2, '0')).append('-')
            append(d.toString().padStart(2, '0')).append('T')
            append((sec / 3600).toString().padStart(2, '0')).append(':')
            append((sec / 60 % 60).toString().padStart(2, '0')).append(':')
            append((sec % 60).toString().padStart(2, '0')).append('.')
            append(micros.toString().padStart(6, '0')).append('Z')
        }
    }

    /** The exact UTF-8 size of [s], to count file bytes without encoding twice. */
    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { i++; 4 }
                else -> 3 // a BMP character, or a lone surrogate (encoders write it as '?', one byte; counted high is safe)
            }
            i++
        }
        return n
    }

    private const val NS_PER_DAY = 86_400_000_000_000L
}
