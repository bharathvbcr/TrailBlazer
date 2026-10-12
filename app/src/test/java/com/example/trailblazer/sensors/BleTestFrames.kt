package com.example.trailblazer.sensors

import com.trailblazer.core.time.CivilDate

/** Little-endian byte builder for GATT characteristic values. */
internal class Frame {
    private val bytes = ArrayList<Byte>()

    fun u8(v: Int) = apply { bytes += v.toByte() }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun u24(v: Int) = apply { u8(v); u8(v shr 8); u8(v shr 16) }
    fun s32(v: Int) = apply { u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24) }
    fun u32(v: Long) = s32(v.toInt())
    fun toBytes(): ByteArray = bytes.toByteArray()
}

/** Location and Speed (0x2A67) flag bits, per the Bluetooth SIG characteristic definition. */
internal object LnsFlags {
    const val SPEED = 0x01
    const val DISTANCE = 0x02
    const val LOCATION = 0x04
    const val ELEVATION = 0x08
    const val HEADING = 0x10
    const val ROLLING_TIME = 0x20
    const val UTC_TIME = 0x40
    fun status(s: Int) = s shl 7
}

/** An LNS frame with a location and the given Position Status. */
internal fun lnsLocation(lat: Double, lon: Double, status: Int = BlePositionStatus.POSITION_OK, extraFlags: Int = 0, tail: Frame.() -> Unit = {}): ByteArray =
    Frame()
        .u16(LnsFlags.LOCATION or LnsFlags.status(status) or extraFlags)
        .s32(Math.round(lat * 10_000_000).toInt())
        .s32(Math.round(lon * 10_000_000).toInt())
        .apply(tail)
        .toBytes()

/** Position Quality (0x2A69) with EHPE (bit 3) and optionally HDOP (bit 5). */
internal fun positionQuality(status: Int = BlePositionStatus.POSITION_OK, ehpeM: Double? = null, hdop: Double? = null): ByteArray {
    var flags = status shl 7
    if (ehpeM != null) flags = flags or 0x08
    if (hdop != null) flags = flags or 0x20
    val f = Frame().u16(flags)
    if (ehpeM != null) f.u32(Math.round(ehpeM * 100))
    if (hdop != null) f.u8(Math.round(hdop / 0.2).toInt())
    return f.toBytes()
}

/** An NMEA sentence from its body (between `$` and `*`) with the correct checksum appended. */
internal fun nmea(body: String): String {
    var cs = 0
    for (c in body) cs = cs xor c.code
    return "\$$body*%02X".format(cs)
}

internal fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
    CivilDate.daysFromCivil(year, month, day) * 86_400_000L + ((hour * 60L + minute) * 60L + second) * 1000L
