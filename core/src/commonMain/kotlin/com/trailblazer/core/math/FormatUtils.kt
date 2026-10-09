package com.trailblazer.core.math

import kotlin.math.abs
import kotlin.math.roundToLong

internal fun formatDecimals(v: Double, decimals: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v < 0.0) "-Infinity" else "Infinity"
    val isNegative = v < 0.0 || (v == 0.0 && 1.0 / v < 0.0)
    val sign = if (isNegative) "-" else ""
    val absV = abs(v)
    var mult = 1.0
    repeat(decimals) { mult *= 10.0 }
    if (absV >= (Long.MAX_VALUE / mult)) {
        val intStr = if (absV < Long.MAX_VALUE.toDouble()) {
            absV.toLong().toString()
        } else {
            val s = absV.toString()
            if (s.contains('E') || s.contains('e')) {
                return "$sign$s"
            }
            s.substringBefore('.')
        }
        val zeros = "0".repeat(decimals)
        return if (decimals > 0) "$sign$intStr.$zeros" else "$sign$intStr"
    }
    val rounded = (absV * mult).roundToLong()
    val multLong = mult.toLong()
    val intPart = rounded / multLong
    val fracPart = rounded % multLong
    return "$sign$intPart.${fracPart.toString().padStart(decimals, '0')}"
}
