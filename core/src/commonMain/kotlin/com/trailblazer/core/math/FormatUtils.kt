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
    // Too large to scale into a Long: every double this big is a whole number, so print its integer digits.
    if (absV >= (Long.MAX_VALUE / mult)) {
        val intStr = if (absV < Long.MAX_VALUE.toDouble()) absV.toLong().toString() else wholeNumberDigits(absV)
        val zeros = "0".repeat(decimals)
        return if (decimals > 0) "$sign$intStr.$zeros" else "$sign$intStr"
    }
    val rounded = (absV * mult).roundToLong()
    val multLong = mult.toLong()
    val intPart = rounded / multLong
    val fracPart = rounded % multLong
    return "$sign$intPart.${fracPart.toString().padStart(decimals, '0')}"
}

/**
 * The plain decimal digits of a finite whole [v] of 2^63 or more, which `toString` renders as `1.0E25`. XML decimals
 * (GPX `<ele>`) have no exponent form. The digits are the shortest that round-trip, padded with zeros.
 */
private fun wholeNumberDigits(v: Double): String {
    val s = v.toString()
    val e = s.indexOfFirst { it == 'E' || it == 'e' }
    if (e < 0) return s.substringBefore('.')
    val exponent = s.substring(e + 1).toInt()
    val mantissa = s.substring(0, e)
    val dot = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
    val digits = mantissa.replace(".", "")
    val intLen = dot + exponent
    return if (digits.length <= intLen) digits + "0".repeat(intLen - digits.length) else digits.substring(0, intLen)
}
