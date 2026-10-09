package com.trailblazer.core.location

import com.trailblazer.core.math.mod360

/** One satellite as seen by the GNSS receiver. */
data class Satellite(
    val constellation: Int,
    val svid: Int,
    val cn0DbHz: Double,
    val elevationDeg: Double,
    val azimuthDeg: Double,
    val usedInFix: Boolean,
) {
    companion object {
        const val CONSTELLATION_GPS = 1
        const val CONSTELLATION_SBAS = 2
        const val CONSTELLATION_GLONASS = 3
        const val CONSTELLATION_QZSS = 4
        const val CONSTELLATION_BEIDOU = 5
        const val CONSTELLATION_GALILEO = 6
        const val CONSTELLATION_IRNSS = 7

        /** Canonical name for standard GNSS constellations. */
        fun constellationName(constellation: Int): String = when (constellation) {
            CONSTELLATION_GPS -> "GPS"
            CONSTELLATION_SBAS -> "SBAS"
            CONSTELLATION_GLONASS -> "GLONASS"
            CONSTELLATION_QZSS -> "QZSS"
            CONSTELLATION_BEIDOU -> "BeiDou"
            CONSTELLATION_GALILEO -> "Galileo"
            CONSTELLATION_IRNSS -> "NavIC"
            else -> "Other"
        }

        /**
         * Validates a chipset report: a satellite with no real position in the sky is dropped, azimuth is wrapped into
         * [0, 360), and signal strength (C/N0) is clamped to 0–99 dB-Hz, with an unreadable one shown as no signal.
         */
        fun of(constellation: Int, svid: Int, cn0: Float, elevation: Float, azimuth: Float, used: Boolean): Satellite? {
            val el = elevation.toDouble().takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
            val az = azimuth.toDouble().takeIf { it.isFinite() }?.let { mod360(it) } ?: return null
            val signal = cn0.toDouble().takeIf { it.isFinite() }?.coerceIn(0.0, 99.0) ?: 0.0
            return Satellite(constellation, svid, signal, el, az, used)
        }
    }
}
