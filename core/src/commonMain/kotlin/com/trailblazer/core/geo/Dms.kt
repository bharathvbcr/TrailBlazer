package com.trailblazer.core.geo

import com.trailblazer.core.math.formatDecimals
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
            CoordinateFormat.Decimal -> formatDecimals(value, 6)
            CoordinateFormat.DegreesMinutes -> {
                val totalThousandthMin = (a * 60_000).roundToLong()
                val deg = totalThousandthMin / 60_000
                val minInt = (totalThousandthMin % 60_000) / 1000
                val minFrac = (totalThousandthMin % 60_000) % 1000
                val minStr = "${minInt.toString().padStart(2, '0')}.${minFrac.toString().padStart(3, '0')}"
                "$deg°$minStr′ $hemi"
            }
            CoordinateFormat.DegreesMinutesSeconds -> {
                val totalTenthSec = (a * 36_000).roundToLong()
                val deg = totalTenthSec / 36_000
                val min = (totalTenthSec % 36_000) / 600
                val secInt = (totalTenthSec % 600) / 10
                val secFrac = (totalTenthSec % 600) % 10
                val minStr = min.toString().padStart(2, '0')
                val secStr = "${secInt.toString().padStart(2, '0')}.$secFrac"
                "$deg°$minStr′$secStr″ $hemi"
            }
        }
    }

    fun format(p: LatLon, format: CoordinateFormat): String =
        if (format == CoordinateFormat.Decimal) "${formatDecimals(p.lat, 6)}, ${formatDecimals(p.lon, 6)}"
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
