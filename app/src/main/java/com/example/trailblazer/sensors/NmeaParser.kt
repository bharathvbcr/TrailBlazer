package com.example.trailblazer.sensors

import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.CivilDate

/** What one validated NMEA 0183 sentence says about a position. Times are UTC. */
internal data class NmeaSentence(
    val kind: Kind,
    val timeOfDayMs: Long,
    /** Days since 1970-01-01; only RMC carries a date. */
    val epochDay: Long?,
    val position: LatLon,
    val altitudeM: Double? = null,
    val hdop: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
) {
    enum class Kind { GGA, RMC, GLL }
}

/**
 * Parses standard NMEA 0183 sentences (GGA, RMC, GLL). A sentence is accepted only if it has a correct checksum, a valid fix
 * status, an in-range position and a valid UTC time; anything else is dropped rather than repaired.
 */
object NmeaParser {
    private val latPattern = Regex("""(\d{2})(\d{2}(?:\.\d+)?)""")
    private val lonPattern = Regex("""(\d{3})(\d{2}(?:\.\d+)?)""")
    private val timePattern = Regex("""(\d{2})(\d{2})(\d{2})(?:\.(\d{1,6}))?""")
    private val datePattern = Regex("""(\d{2})(\d{2})(\d{2})""")

    /** FAA mode indicators that mean "no usable fix": N not valid, E estimated, M manual, S simulator. */
    private const val UNUSABLE_MODES = "NEMS"

    private const val KNOTS_TO_MPS = 0.5144444444444445

    /** A single sentence as a fix (no merging with the rest of its epoch). Test convenience; production goes through [BleLocationPipeline]. */
    internal fun parse(sentence: String, clock: Clock = Clock.System): Fix? {
        val s = parseSentence(sentence) ?: return null
        return NmeaEpoch(s.timeOfDayMs).also { it.add(s) }.toFix(clock.nowMs())
    }

    internal fun parseSentence(sentence: String): NmeaSentence? {
        val trimmed = sentence.trim()
        if (!trimmed.startsWith("$")) return null
        val star = trimmed.lastIndexOf('*')
        if (star < 0) return null // a sentence without a checksum cannot be told from line noise
        val hex = trimmed.substring(star + 1)
        if (hex.length != 2 || !hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
        val payload = trimmed.substring(1, star)
        if (payload.any { it.code < 0x20 || it.code > 0x7E || it == '$' || it == '*' }) return null
        var cs = 0
        for (ch in payload) cs = cs xor ch.code
        if (cs != hex.toInt(16)) return null

        val parts = payload.split(',')
        val type = parts[0].uppercase()
        if (type.length != 5 || !type.all { it in 'A'..'Z' }) return null
        return when (type.substring(2)) {
            "GGA" -> parseGga(parts)
            "RMC" -> parseRmc(parts)
            "GLL" -> parseGll(parts)
            else -> null
        }
    }

    private fun parseGga(p: List<String>): NmeaSentence? {
        if (p.size < 10) return null
        val quality = p[6].toIntOrNull() ?: return null
        if (quality !in 1..5) return null // 0 no fix; 6 dead reckoning, 7 manual and 8 simulation are not measured positions
        val time = parseTime(p[1]) ?: return null
        val pos = parsePosition(p[2], p[3], p[4], p[5]) ?: return null
        return NmeaSentence(
            kind = NmeaSentence.Kind.GGA,
            timeOfDayMs = time,
            epochDay = null,
            position = pos,
            altitudeM = p[9].toDoubleOrNull()?.takeIf { it.isFinite() && it in -1000.0..20_000.0 },
            hdop = p[8].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 },
        )
    }

    private fun parseRmc(p: List<String>): NmeaSentence? {
        if (p.size < 9) return null
        if (p[2] != "A") return null // A = active, V = void
        if (p.size > 12 && p[12].isNotEmpty() && p[12][0] in UNUSABLE_MODES) return null
        val time = parseTime(p[1]) ?: return null
        val pos = parsePosition(p[3], p[4], p[5], p[6]) ?: return null
        return NmeaSentence(
            kind = NmeaSentence.Kind.RMC,
            timeOfDayMs = time,
            epochDay = if (p.size > 9) parseDate(p[9]) else null,
            position = pos,
            speedMps = p[7].toDoubleOrNull()?.times(KNOTS_TO_MPS)?.takeIf { it.isFinite() && it in 0.0..400.0 },
            bearingDeg = p[8].toDoubleOrNull()?.takeIf { it.isFinite() }?.let { mod360(it) },
        )
    }

    private fun parseGll(p: List<String>): NmeaSentence? {
        if (p.size < 7) return null
        if (p[6] != "A") return null
        if (p.size > 7 && p[7].isNotEmpty() && p[7][0] in UNUSABLE_MODES) return null
        val time = parseTime(p[5]) ?: return null
        val pos = parsePosition(p[1], p[2], p[3], p[4]) ?: return null
        return NmeaSentence(kind = NmeaSentence.Kind.GLL, timeOfDayMs = time, epochDay = null, position = pos)
    }

    private fun parsePosition(lat: String, ns: String, lon: String, ew: String): LatLon? {
        val la = parseCoord(lat, ns, latPattern, "N", "S", 90.0) ?: return null
        val lo = parseCoord(lon, ew, lonPattern, "E", "W", 180.0) ?: return null
        return LatLon.of(la, lo)
    }

    /** ddmm.mmmm / dddmm.mmmm plus hemisphere. Minutes must be below 60 and the result inside the valid range. */
    private fun parseCoord(value: String, hemisphere: String, pattern: Regex, positive: String, negative: String, limit: Double): Double? {
        val m = pattern.matchEntire(value) ?: return null
        val minutes = m.groupValues[2].toDoubleOrNull() ?: return null
        if (!minutes.isFinite() || minutes < 0.0 || minutes >= 60.0) return null
        val degrees = m.groupValues[1].toDouble() + minutes / 60.0
        if (degrees > limit) return null
        return when (hemisphere) {
            positive -> degrees
            negative -> -degrees
            else -> null
        }
    }

    /** hhmmss or hhmmss.sss to milliseconds since UTC midnight, or null for an impossible time. */
    internal fun parseTime(value: String): Long? {
        val m = timePattern.matchEntire(value) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val s = m.groupValues[3].toInt()
        if (h > 23 || min > 59 || s > 59) return null
        val frac = m.groupValues[4]
        val fracMs = if (frac.isEmpty()) 0L else (("0." + frac).toDouble() * 1000.0).toLong()
        return ((h * 60L + min) * 60L + s) * 1000L + fracMs
    }

    /** ddmmyy to days since 1970-01-01, or null for an impossible calendar date. Two-digit years 80..99 are 19xx (the GPS era). */
    internal fun parseDate(value: String): Long? {
        val m = datePattern.matchEntire(value) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val yy = m.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val year = if (yy >= 80) 1900 + yy else 2000 + yy
        val days = CivilDate.daysFromCivil(year, month, day)
        val (y, mo, d) = CivilDate.civilFromDays(days)
        return if (y == year && mo == month && d == day) days else null // rejects 31 February and the like
    }
}

/** The sentences of one receiver epoch (one UTC second), merged into a single [Fix]. */
internal class NmeaEpoch(val timeOfDayMs: Long) {
    var gga: NmeaSentence? = null
        private set
    var rmc: NmeaSentence? = null
        private set
    var gll: NmeaSentence? = null
        private set

    /** A receiver that sends GGA and RMC has said everything it will say about this epoch. */
    val complete: Boolean get() = gga != null && rmc != null

    /** The first sentence of each kind wins, so a second constellation's duplicate cannot overwrite the first. */
    fun add(s: NmeaSentence) {
        when (s.kind) {
            NmeaSentence.Kind.GGA -> if (gga == null) gga = s
            NmeaSentence.Kind.RMC -> if (rmc == null) rmc = s
            NmeaSentence.Kind.GLL -> if (gll == null) gll = s
        }
    }

    fun toFix(nowMs: Long): Fix {
        val g = gga
        val r = rmc
        val position = (g ?: r ?: gll ?: error("empty NMEA epoch")).position
        return Fix(
            position = position,
            accuracyM = BleFixPolicy.accuracyFromHdop(g?.hdop) ?: BleFixPolicy.DEFAULT_ACCURACY_M,
            altitudeM = g?.altitudeM,
            altitudeDatum = AltitudeDatum.SeaLevel, // GGA altitude is above mean sea level
            verticalAccuracyM = null,
            speedMps = r?.speedMps,
            bearingDeg = r?.bearingDeg,
            timeMs = BleFixPolicy.resolveTimeOfDay(timeOfDayMs, r?.epochDay, nowMs),
            provider = "ble_nmea",
        )
    }
}

/**
 * Groups sentences by the receiver's UTC time and emits one fix per epoch. An epoch is emitted as soon as it holds both GGA
 * and RMC, or when a sentence of a later epoch arrives, or on [flush]; a receiver that sends only one position sentence
 * therefore delivers each fix when the next epoch starts or the owner flushes. Sentences of an epoch already emitted (or of
 * the 30 s before it, or older than the open epoch) are dropped, so a late straggler never produces a second, half-empty or
 * backwards fix. Not thread-safe.
 */
internal class NmeaEpochMerger(private val clock: Clock) {
    private var open: NmeaEpoch? = null
    private var lastClosedTimeOfDayMs: Long? = null

    val hasPending: Boolean get() = open != null

    fun accept(s: NmeaSentence): List<Fix> {
        val current = open
        val closed = lastClosedTimeOfDayMs
        if (current != null) {
            if (s.timeOfDayMs < current.timeOfDayMs && current.timeOfDayMs - s.timeOfDayMs <= STRAGGLER_WINDOW_MS) return emptyList()
        } else if (closed != null && s.timeOfDayMs <= closed && closed - s.timeOfDayMs <= STRAGGLER_WINDOW_MS) {
            return emptyList()
        }
        val out = ArrayList<Fix>(1)
        if (current != null && current.timeOfDayMs != s.timeOfDayMs) flush()?.let { out += it }
        val epoch = open ?: NmeaEpoch(s.timeOfDayMs).also { open = it }
        epoch.add(s)
        if (epoch.complete) flush()?.let { out += it }
        return out
    }

    /** Emits the open epoch, if any, and closes it. */
    fun flush(): Fix? {
        val epoch = open ?: return null
        open = null
        lastClosedTimeOfDayMs = epoch.timeOfDayMs
        return epoch.toFix(clock.nowMs())
    }

    fun reset() {
        open = null
        lastClosedTimeOfDayMs = null
    }

    private companion object {
        const val STRAGGLER_WINDOW_MS = 30_000L
    }
}

/**
 * Reassembles NMEA lines from BLE notifications of any size (20-byte chunks under the default MTU split sentences anywhere).
 * Pending text is capped: a stream with no line terminator can never grow without bound. Thread-safe, because GATT
 * callbacks arrive on binder threads while connect and disconnect run on the main thread.
 */
class NmeaStreamBuffer {
    private val buffer = StringBuilder()

    /** Appends [chunk] and returns every line it completed, each starting at its last `$` (a lost chunk cannot poison it). */
    @Synchronized
    fun appendAndExtractLines(chunk: String): List<String> {
        buffer.append(chunk)
        val lines = ArrayList<String>()
        while (true) {
            val end = buffer.indexOfAny(charArrayOf('\r', '\n'))
            if (end == -1) break
            val line = buffer.substring(0, end)
            buffer.delete(0, end + 1)
            val start = line.lastIndexOf('$')
            if (start >= 0) lines += line.substring(start)
        }
        if (buffer.length > MAX_PENDING_CHARS) {
            // No terminator in sight: keep the text from the last '$' (a sentence in progress) if it is plausibly one, else drop it.
            val start = buffer.lastIndexOf("$")
            if (start > 0) buffer.delete(0, start)
            if (buffer.length > MAX_PENDING_CHARS) buffer.setLength(0)
        }
        return lines
    }

    @Synchronized
    fun clear() {
        buffer.setLength(0)
    }

    @Synchronized
    fun pendingLength(): Int = buffer.length

    companion object {
        /** An NMEA sentence is at most 82 characters; 1 KiB leaves room for several sentences in flight. */
        const val MAX_PENDING_CHARS = 1024
    }
}
