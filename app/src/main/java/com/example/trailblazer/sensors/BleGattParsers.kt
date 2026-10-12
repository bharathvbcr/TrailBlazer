package com.example.trailblazer.sensors

import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.CivilDate

/** Position Status of the LNS Location and Speed and Position Quality characteristics (flags bits 7-8). */
object BlePositionStatus {
    const val NO_POSITION = 0
    const val POSITION_OK = 1
    const val ESTIMATED = 2
    const val LAST_KNOWN = 3
}

/** Fields of Position Quality (0x2A69) that this app uses. Units are already converted. */
data class BlePositionQuality(val positionStatus: Int, val ehpeM: Double?, val hdop: Double?)

/**
 * Parses the Bluetooth SIG Position Quality characteristic (0x2A69): flags uint16, then in this order and only when flagged
 * Number of Beacons in Solution (uint8, bit 0), in View (uint8, bit 1), Time to First Fix (uint16, bit 2), EHPE (uint32,
 * 0.01 m, bit 3), EVPE (uint32, 0.01 m, bit 4), HDOP (uint8, 0.2, bit 5), VDOP (uint8, 0.2, bit 6); Position Status is
 * bits 7-8. A frame shorter than its flags promise is rejected.
 */
object BlePositionQualityParser {
    fun parse(data: ByteArray): BlePositionQuality? {
        val r = LeReader(data)
        val flags = r.u16() ?: return null
        if (flags and 0x01 != 0) r.skip(1) ?: return null
        if (flags and 0x02 != 0) r.skip(1) ?: return null
        if (flags and 0x04 != 0) r.skip(2) ?: return null
        var ehpe: Double? = null
        if (flags and 0x08 != 0) ehpe = (r.u32() ?: return null) / 100.0
        if (flags and 0x10 != 0) r.skip(4) ?: return null
        var hdop: Double? = null
        if (flags and 0x20 != 0) hdop = (r.u8() ?: return null) * 0.2
        if (flags and 0x40 != 0) r.skip(1) ?: return null
        return BlePositionQuality(
            positionStatus = (flags shr 7) and 0x03,
            ehpeM = ehpe?.takeIf { it.isFinite() && it <= BleFixPolicy.MAX_ACCURACY_M },
            hdop = hdop?.takeIf { it.isFinite() },
        )
    }
}

/**
 * Parses the Bluetooth SIG Location and Speed characteristic (0x2A67). Flags uint16: bit 0 speed, 1 total distance, 2 location,
 * 3 elevation, 4 heading, 5 rolling time, 6 UTC time, 7-8 Position Status. A frame is a fix only when it carries a location
 * and its Position Status is Position Ok or Estimated; No Position and Last Known frames hold stale or meaningless
 * coordinates and are rejected. An Estimated position is returned with [BleFixPolicy.ESTIMATED_ACCURACY_M] so it can be shown
 * but not recorded.
 */
object BleLnsParser {
    fun parse(data: ByteArray, clock: Clock = Clock.System, quality: BlePositionQuality? = null): Fix? {
        val r = LeReader(data)
        val flags = r.u16() ?: return null
        val status = (flags shr 7) and 0x03
        if (status != BlePositionStatus.POSITION_OK && status != BlePositionStatus.ESTIMATED) return null

        var speedMps: Double? = null
        if (flags and 0x01 != 0) {
            speedMps = ((r.u16() ?: return null) / 100.0).takeIf { it in 0.0..400.0 }
        }
        if (flags and 0x02 != 0) r.skip(3) ?: return null // total distance
        if (flags and 0x04 == 0) return null // a position fix needs a position
        val rawLat = r.s32() ?: return null
        val rawLon = r.s32() ?: return null
        val lat = rawLat / 10_000_000.0
        val lon = rawLon / 10_000_000.0
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null // out of range is a corrupt frame, not a longitude to wrap
        val pos = LatLon.of(lat, lon) ?: return null

        var elevationM: Double? = null
        if (flags and 0x08 != 0) {
            elevationM = ((r.s24() ?: return null) / 100.0).takeIf { it in -1000.0..20_000.0 }
        }
        var headingDeg: Double? = null
        if (flags and 0x10 != 0) {
            headingDeg = (r.u16() ?: return null).let { mod360(it / 100.0) }
        }
        if (flags and 0x20 != 0) r.skip(1) ?: return null // rolling time
        var utcMs: Long? = null
        if (flags and 0x40 != 0) {
            if (r.remaining < 7) return null
            utcMs = readDateTime(r)
        }

        val nowMs = clock.nowMs()
        var accuracy = quality?.takeIf { it.positionStatus == BlePositionStatus.POSITION_OK || it.positionStatus == BlePositionStatus.ESTIMATED }
            ?.let { it.ehpeM ?: BleFixPolicy.accuracyFromHdop(it.hdop) }
            ?: BleFixPolicy.DEFAULT_ACCURACY_M
        if (status == BlePositionStatus.ESTIMATED) accuracy = maxOf(accuracy, BleFixPolicy.ESTIMATED_ACCURACY_M)

        return Fix(
            position = pos,
            accuracyM = accuracy,
            altitudeM = elevationM,
            altitudeDatum = AltitudeDatum.Ellipsoid,
            verticalAccuracyM = null,
            speedMps = speedMps,
            bearingDeg = headingDeg,
            timeMs = BleFixPolicy.trustedUtc(utcMs, nowMs),
            provider = "ble_lns",
        )
    }

    /**
     * Date Time characteristic format: year uint16, month, day, hours, minutes, seconds (7 bytes), as epoch milliseconds, or
     * null when the receiver reports an unknown or impossible time (year or month 0, a day that does not exist). The caller
     * has already checked that all 7 bytes are present.
     */
    private fun readDateTime(r: LeReader): Long? {
        val year = r.u16() ?: return null
        val month = r.u8() ?: return null
        val day = r.u8() ?: return null
        val hour = r.u8() ?: return null
        val minute = r.u8() ?: return null
        val second = r.u8() ?: return null
        if (year == 0 || month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
        val days = CivilDate.daysFromCivil(year, month, day)
        val (y, mo, d) = CivilDate.civilFromDays(days)
        if (y != year || mo != month || d != day) return null
        return days * 86_400_000L + ((hour * 60L + minute) * 60L + second) * 1000L
    }
}

/** Parses Bluetooth SIG standard Environmental Sensing Service characteristics. */
object BleEssParser {
    /** Pressure (0x2A6D): 32-bit unsigned int in 0.1 Pa -> hPa */
    fun parsePressure(data: ByteArray): Double? {
        val raw = LeReader(data).u32() ?: return null
        val hpa = raw / 1000.0
        return hpa.takeIf { it.isFinite() && it in 300.0..1100.0 }
    }

    /** Temperature (0x2A6E): 16-bit signed int in 0.01 °C -> °C */
    fun parseTemperature(data: ByteArray): Double? {
        val raw = LeReader(data).s16() ?: return null
        val temp = raw / 100.0
        return temp.takeIf { it.isFinite() && it in -60.0..70.0 }
    }

    /** Humidity (0x2A6F): 16-bit unsigned int in 0.01 % -> % */
    fun parseHumidity(data: ByteArray): Double? {
        val raw = LeReader(data).u16() ?: return null
        val hum = raw / 100.0
        return hum.takeIf { it.isFinite() && it in 0.0..100.0 }
    }
}

/** Little-endian cursor over a GATT value; every read returns null instead of running past the end. */
internal class LeReader(private val data: ByteArray) {
    private var offset = 0

    val remaining: Int get() = data.size - offset

    private fun take(n: Int): Int? {
        if (offset + n > data.size) return null
        val start = offset
        offset += n
        return start
    }

    fun skip(n: Int): Unit? = take(n)?.let { }

    fun u8(): Int? = take(1)?.let { data[it].toInt() and 0xFF }

    fun u16(): Int? = take(2)?.let { (data[it].toInt() and 0xFF) or ((data[it + 1].toInt() and 0xFF) shl 8) }

    fun s16(): Int? = take(2)?.let { (data[it].toInt() and 0xFF) or (data[it + 1].toInt() shl 8) }

    fun s24(): Int? = take(3)?.let {
        val raw = (data[it].toInt() and 0xFF) or ((data[it + 1].toInt() and 0xFF) shl 8) or ((data[it + 2].toInt() and 0xFF) shl 16)
        if (raw and 0x800000 != 0) raw or (0xFF shl 24) else raw
    }

    fun s32(): Int? = take(4)?.let {
        (data[it].toInt() and 0xFF) or ((data[it + 1].toInt() and 0xFF) shl 8) or
            ((data[it + 2].toInt() and 0xFF) shl 16) or (data[it + 3].toInt() shl 24)
    }

    fun u32(): Long? = take(4)?.let {
        (data[it].toLong() and 0xFF) or ((data[it + 1].toLong() and 0xFF) shl 8) or
            ((data[it + 2].toLong() and 0xFF) shl 16) or ((data[it + 3].toLong() and 0xFF) shl 24)
    }
}
