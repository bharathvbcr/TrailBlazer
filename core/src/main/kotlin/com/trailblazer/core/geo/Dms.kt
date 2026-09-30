package com.trailblazer.core.geo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

enum class CoordinateFormat { Decimal, DegreesMinutes, DegreesMinutesSeconds }

object Dms {
    /** Formats one axis. Rounding carries correctly (59.9995″ becomes the next minute, never 60″). */
    fun formatAxis(value: Double, isLatitude: Boolean, format: CoordinateFormat): String {
        val hemi = if (isLatitude) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "W")
        val a = abs(value)
        return when (format) {
            CoordinateFormat.Decimal -> String.format(Locale.ROOT, "%.6f", value)
            CoordinateFormat.DegreesMinutes -> {
                val totalThousandthMin = (a * 60_000).roundToLong()
                val deg = totalThousandthMin / 60_000
                val min = (totalThousandthMin % 60_000) / 1000.0
                String.format(Locale.ROOT, "%d°%06.3f′ %s", deg, min, hemi)
            }
            CoordinateFormat.DegreesMinutesSeconds -> {
                val totalTenthSec = (a * 36_000).roundToLong()
                val deg = totalTenthSec / 36_000
                val min = (totalTenthSec % 36_000) / 600
                val sec = (totalTenthSec % 600) / 10.0
                String.format(Locale.ROOT, "%d°%02d′%04.1f″ %s", deg, min, sec, hemi)
            }
        }
    }

    fun format(p: LatLon, format: CoordinateFormat): String =
        if (format == CoordinateFormat.Decimal) String.format(Locale.ROOT, "%.6f, %.6f", p.lat, p.lon)
        else "${formatAxis(p.lat, true, format)}  ${formatAxis(p.lon, false, format)}"

    /** Degrees + minutes + seconds to decimal degrees; null for out-of-range parts. */
    fun toDecimal(deg: Double, min: Double, sec: Double, negative: Boolean): Double? {
        if (!deg.isFinite() || !min.isFinite() || !sec.isFinite()) return null
        if (deg < 0 || min < 0 || min >= 60 || sec < 0 || sec >= 60) return null
        if (deg != floor(deg) && (min != 0.0 || sec != 0.0)) return null
        if (min != floor(min) && sec != 0.0) return null
        val v = deg + min / 60 + sec / 3600
        return if (negative) -v else v
    }
}
