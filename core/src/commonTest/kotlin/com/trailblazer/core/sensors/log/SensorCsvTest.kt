package com.trailblazer.core.sensors.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SensorCsvTest {
    private val anchor = ClockAnchor(wallMs = 1_700_000_000_123L, elapsedNs = 5_000_000_000L)

    /** A minimal RFC 4180 reader, so the tests check that what is written reads back as the same cells. */
    private fun parse(text: String): List<List<String>> {
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
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList(); i++
                }
                else -> cell.append(c)
            }
            i++
        }
        assertTrue(!inQuotes && cell.isEmpty() && row.isEmpty(), "the text ends with a complete record")
        return rows
    }

    private fun cells(e: LogEvent): List<String> = parse(SensorCsv.rowLine(e, anchor)).single()

    private fun gnss(
        carrier: Float? = 1_575_420_000f,
        clockTime: Long? = 123_456_789_000L,
        fullBias: Long? = -1_234_567_890_123L,
        bias: Double? = 0.25,
        note: String = "",
    ) = GnssMeasurementRow(
        elapsedNs = 6_000_000_000L, constellation = 1, svid = 17, cn0DbHz = 41.5, receivedSvTimeNs = 345_600_123_456L,
        receivedSvTimeUncertaintyNs = 30L, state = 0x4001, pseudorangeRateMps = -512.125, pseudorangeRateUncertaintyMps = 0.2,
        timeOffsetNs = 0.0, accumulatedDeltaRangeM = 1234.5, accumulatedDeltaRangeState = 1, carrierFrequencyHz = carrier,
        clockTimeNs = clockTime, fullBiasNs = fullBias, biasNs = bias, hardwareClockDiscontinuityCount = 2, note = note,
    )

    @Test
    fun escapeFollowsRfc4180() {
        assertEquals("plain", SensorCsv.escape("plain"))
        assertEquals("", SensorCsv.escape(""))
        assertEquals("\"a,b\"", SensorCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SensorCsv.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", SensorCsv.escape("line1\nline2"))
        assertEquals("\"a\rb\"", SensorCsv.escape("a\rb"))
        assertEquals("\"\"\"\"", SensorCsv.escape("\""))
    }

    @Test
    fun headerAndEveryRowTypeHaveTheSameNumberOfCells() {
        val header = parse(SensorCsv.headerLine()).single()
        assertEquals(SensorCsv.COLUMNS, header)
        val events = listOf(
            SensorEventRow(5_000_000_000L, LogSensor.Accelerometer, floatArrayOf(0.1f, 9.8f, -0.2f), 3),
            SensorEventRow(5_000_000_000L, LogSensor.Barometer, floatArrayOf(1013.25f), 0),
            SensorEventRow(5_000_000_000L, LogSensor.Gyroscope, floatArrayOf(), 1),
            MarkerRow(5_000_000_000L, MarkerKind.Gap, "dropped=3"),
            gnss(),
            gnss(carrier = null, clockTime = null, fullBias = null, bias = null),
        )
        for (e in events) assertEquals(header.size, cells(e).size, e::class.simpleName)
    }

    @Test
    fun accelerometerRowIsExact() {
        val row = SensorEventRow(5_001_500_000L, LogSensor.Accelerometer, floatArrayOf(0.1f, 9.80665f, -0.5f), 3)
        assertEquals(
            "2023-11-14T22:13:20.124500Z,5001500000,ACCELEROMETER,0.1,9.80665,-0.5,3" + ",".repeat(17) + "\r\n",
            SensorCsv.rowLine(row, anchor),
        )
    }

    @Test
    fun barometerFillsOnlyX() {
        val c = cells(SensorEventRow(5_000_000_000L, LogSensor.Barometer, floatArrayOf(1013.25f), 2))
        assertEquals(listOf("PRESSURE", "1013.25", "", "", "2"), c.subList(2, 7))
    }

    @Test
    fun valuesBeyondThreeGoToTheNoteInsteadOfBeingLost() {
        val c = cells(SensorEventRow(5_000_000_000L, LogSensor.Accelerometer, floatArrayOf(1f, 2f, 3f, 4f, 5.5f), 3))
        assertEquals("v3=4.0;v4=5.5", c.last())
    }

    @Test
    fun nonFiniteValuesAreWrittenExplicitlyNeverAsEmptyOrLocaleText() {
        val c = cells(SensorEventRow(5_000_000_000L, LogSensor.Gyroscope, floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY), 0))
        assertEquals(listOf("NaN", "Infinity", "-Infinity"), c.subList(3, 6))
        assertEquals("NaN", SensorCsv.number(Double.NaN))
        assertEquals("Infinity", SensorCsv.number(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", SensorCsv.number(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun floatsKeepTheirOwnPrecisionAndNeverUseADecimalComma() {
        assertEquals("0.1", SensorCsv.number(0.1f))
        assertEquals("-0.0", SensorCsv.number(-0.0f))
        assertTrue(SensorCsv.number(1234.5678f).none { it == ',' })
        assertEquals("0.1", SensorCsv.number(0.1))
    }

    @Test
    fun gnssRowCarriesTheClockAndMeasurementFields() {
        val c = cells(gnss())
        assertEquals("GNSS_MEASUREMENT", c[2])
        assertEquals(listOf("", "", "", ""), c.subList(3, 7))
        assertEquals(
            listOf("17", "1", "41.5", "345600123456", "30", "16385", "-512.125", "0.2", "0.0", "1234.5", "1"),
            c.subList(7, 18),
        )
        assertEquals(1_575_420_000f, c[18].toFloat(), "carrier frequency reads back exactly")
        assertEquals(listOf("123456789000", "-1234567890123", "0.25", "2", ""), c.subList(19, 24))
        assertEquals("2023-11-14T22:13:21.123000Z", c[0])
    }

    @Test
    fun gnssOptionalFieldsAreEmptyNotZero() {
        val c = cells(gnss(carrier = null, clockTime = null, fullBias = null, bias = null, note = "elapsed=callback"))
        assertEquals("", c[18])
        assertEquals(listOf("", "", ""), c.subList(19, 22))
        assertEquals("elapsed=callback", c[23])
    }

    @Test
    fun noteWithCommasQuotesAndLineBreaksStaysOneRecord() {
        val note = "device=\"Pixel, 10\"\r\nline2\nline3"
        val line = SensorCsv.rowLine(MarkerRow(5_000_000_000L, MarkerKind.SessionStart, note), anchor)
        assertEquals(1, line.count { it == '\n' }, "only the record terminator is a line break")
        val c = parse(line).single()
        assertEquals("device=\"Pixel, 10\" line2 line3", c[23])
    }

    @Test
    fun undatedMarkerHasNoTimestamps() {
        val c = parse(SensorCsv.undatedMarkerLine(MarkerKind.SessionEnd, "stop_reason=interrupted")).single()
        assertEquals(SensorCsv.COLUMNS.size, c.size)
        assertEquals(listOf("", "", "SESSION_END"), c.subList(0, 3))
        assertEquals("stop_reason=interrupted", c[23])
    }

    @Test
    fun utcAnchorAddsTheElapsedOffsetToTheWallClock() {
        assertEquals("2023-11-14T22:13:20.123000Z", SensorCsv.utcIso(anchor.utcNanos(5_000_000_000L)))
        assertEquals("2023-11-14T22:13:20.124500Z", SensorCsv.utcIso(anchor.utcNanos(5_001_500_000L)))
        assertEquals("2023-11-14T22:13:21.123000Z", SensorCsv.utcIso(anchor.utcNanos(6_000_000_000L)))
        // An event stamped before the anchor (a batched sample) is earlier, not wrapped.
        assertEquals("2023-11-14T22:13:19.123000Z", SensorCsv.utcIso(anchor.utcNanos(4_000_000_000L)))
    }

    @Test
    fun utcFormatHandlesTheEpochLeapDaysAndBeforeTheEpoch() {
        assertEquals("1970-01-01T00:00:00.000000Z", SensorCsv.utcIso(0L))
        assertEquals("1969-12-31T23:59:59.999999Z", SensorCsv.utcIso(-1_000L))
        assertEquals("1969-12-31T23:59:59.999999Z", SensorCsv.utcIso(-1L), "sub-microsecond remainder floors")
        assertEquals("2024-02-29T23:59:59.999999Z", SensorCsv.utcIso(1_709_251_199_999_999_999L))
        assertEquals("2100-03-01T00:00:00.000000Z", SensorCsv.utcIso(4_107_542_400_000_000_000L))
    }

    @Test
    fun anchorRejectsAnImpossibleWallClock() {
        assertFailsWith<IllegalArgumentException> { ClockAnchor(-1L, 0L) }
        assertFailsWith<IllegalArgumentException> { ClockAnchor(Long.MAX_VALUE, 0L) }
    }

    @Test
    fun utf8LengthMatchesTheEncoder() {
        for (s in listOf("", "abc", "é", "日本語", "😀 emoji", "mixed é日😀,\"x\"")) {
            assertEquals(s.encodeToByteArray().size, SensorCsv.utf8Length(s), s)
        }
    }
}
