package com.example.trailblazer.ui

import android.content.Context
import android.text.format.DateFormat
import com.example.trailblazer.data.Settings
import com.trailblazer.core.sensors.Accuracy
import com.trailblazer.core.sensors.UnavailableReason
import com.trailblazer.core.math.cardinal16
import com.trailblazer.core.math.mod360
import com.trailblazer.core.units.Length
import com.trailblazer.core.units.UnitSystem
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** All user-visible number formatting. Values arrive in SI and leave in the user's units. */
class Fmt(private val context: Context, val settings: Settings) {
    private val locale: Locale get() = Locale.getDefault()

    fun num(v: Double, decimals: Int): String = String.format(locale, "%.${decimals}f", v)

    fun distance(m: Double): String {
        val (v, u) = Length.distance(m, settings.units)
        val d = when {
            u == "m" || u == "ft" -> 0
            v < 10 -> 2
            v < 100 -> 1
            else -> 0
        }
        return "${num(v, d)} $u"
    }

    fun elevation(m: Double): String {
        val (v, u) = Length.elevation(m, settings.units)
        return "${num(v, 0)} $u"
    }

    fun elevationUnit(): String = if (settings.units == UnitSystem.Metric) "m" else "ft"
    fun elevationValue(m: Double): String = num(Length.elevation(m, settings.units).first, 0)

    fun speedValue(mps: Double): String = num(settings.speedUnit.fromMps(mps), if (settings.speedUnit.fromMps(mps) < 10) 1 else 0)
    fun speed(mps: Double): String = "${speedValue(mps)} ${settings.speedUnit.symbol}"

    fun pressure(hpa: Double): String {
        val u = settings.pressureUnit
        val d = when (u.symbol) { "inHg" -> 2; "mmHg" -> 0; else -> 1 }
        return "${num(u.fromHpa(hpa), d)} ${u.symbol}"
    }

    fun pressureDelta(hpa: Double): String {
        val u = settings.pressureUnit
        val v = u.fromHpa(hpa)
        val d = when (u.symbol) { "inHg" -> 3; "mmHg" -> 1; else -> 1 }
        return (if (v > 0) "+" else "") + num(v, d) + " " + u.symbol
    }

    fun temperature(c: Double): String {
        val u = settings.temperatureUnit
        return "${num(u.fromC(c), 0)}${u.symbol}"
    }

    fun bearing(deg: Double): String = "${mod360(deg).roundToInt() % 360}° ${cardinal16(deg)}"

    fun angle(deg: Double, decimals: Int = 1): String = num(deg, decimals) + "°"

    /** Local wall time in the device time zone, 12/24 h per system setting. */
    fun time(ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

    fun date(ms: Long): String = DateFormat.getMediumDateFormat(context).format(Date(ms))

    fun dateTime(ms: Long): String = "${date(ms)} ${time(ms)}"

    fun duration(ms: Long): String {
        val totalMin = (abs(ms) / 60_000.0).roundToLong()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) String.format(locale, "%d h %02d min", h, m) else String.format(locale, "%d min", m)
    }

    fun clock(ms: Long): String {
        val s = abs(ms) / 1000
        return String.format(locale, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
}

/** Local-day windows in the device time zone; DST days are 23 or 25 hours long. */
typealias LocalDays = com.trailblazer.core.time.LocalDays

fun LocalDays.today(nowMs: Long, tz: TimeZone): Long =
    today(nowMs, com.trailblazer.core.time.PlatformTimeZone.of(tz))

fun LocalDays.window(epochDay: Long, tz: TimeZone): Pair<Long, Long> =
    window(epochDay, com.trailblazer.core.time.PlatformTimeZone.of(tz))

fun LocalDays.noonToNoon(epochDay: Long, tz: TimeZone): Pair<Long, Long> =
    noonToNoon(epochDay, com.trailblazer.core.time.PlatformTimeZone.of(tz))

fun LocalDays.nightOf(nowMs: Long, tz: TimeZone): Long =
    nightOf(nowMs, com.trailblazer.core.time.PlatformTimeZone.of(tz))

fun UnavailableReason.label(what: String): String = when (this) {
    UnavailableReason.NoHardware -> "No $what on this device"
    UnavailableReason.PermissionDenied -> "Permission needed"
    UnavailableReason.Disabled -> "Turned off in settings"
}

fun Accuracy.label(): String = when (this) {
    Accuracy.Unreliable -> "Unreliable"
    Accuracy.Low -> "Low accuracy"
    Accuracy.Medium -> "Medium accuracy"
    Accuracy.High -> "High accuracy"
    Accuracy.Unknown -> "Accuracy not reported"
}
