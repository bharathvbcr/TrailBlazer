package com.example.trailblazer.ui.stars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trailblazer.AppContainer
import com.example.trailblazer.data.Settings
import com.example.trailblazer.sensors.Declination
import com.example.trailblazer.sensors.Hold
import com.trailblazer.core.sensors.Reading
import com.example.trailblazer.ui.LocalDays
import com.trailblazer.core.astro.ApparentPlace
import com.trailblazer.core.astro.BodyNight
import com.trailblazer.core.astro.BrightStars
import com.trailblazer.core.astro.HorizontalTransform
import com.trailblazer.core.astro.LunarPhase
import com.trailblazer.core.astro.LunarPosition
import com.trailblazer.core.astro.MeteorShowers
import com.trailblazer.core.astro.NightPlan
import com.trailblazer.core.astro.NightSky
import com.trailblazer.core.astro.Planet
import com.trailblazer.core.astro.Planets
import com.trailblazer.core.astro.PolarAlignment
import com.trailblazer.core.astro.ShowerOutlook
import com.trailblazer.core.astro.SkyProjection
import com.trailblazer.core.astro.SolarPosition
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.mod360
import com.trailblazer.core.time.JulianDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the stargazing screen computes for. [lastKnown] marks a stored position used while GPS searches. */
data class StarPlace(val position: LatLon, val label: String?, val fromRoute: Boolean, val lastKnown: Boolean = false)

/** How old a stored position may be to stand in for a live fix: the sky shifts visibly only after many km. */
internal const val LAST_KNOWN_MAX_AGE_MS = 24 * 3_600_000L

/** Apparent altitude sampled every [stepMs] from [startMs], for plotting a body across the night. */
data class AltitudeTrack(val startMs: Long, val stepMs: Long, val altitudesDeg: List<Float>) {
    val endMs: Long get() = startMs + stepMs * (altitudesDeg.size - 1).coerceAtLeast(0)
}

data class PlanetTonight(val planet: Planet, val night: BodyNight, val magnitude: Double, val elongationDeg: Double, val track: AltitudeTrack)

data class ShowerTonight(val outlook: ShowerOutlook, val bestRatePerHour: Double?, val bestTimeMs: Long?)

/** Everything that depends only on the place and the night, computed once per night. */
data class Tonight(
    val place: StarPlace,
    val nightDay: Long,
    val plan: NightPlan,
    val planets: List<PlanetTonight>,
    val galacticCentre: BodyNight,
    val galacticTrack: AltitudeTrack,
    val showers: List<ShowerTonight>,
)

enum class ChartKind { Star, Planet, Moon, GalacticCentre }

/** [label] is drawn on the chart (bright objects only); [name] is what a tap on it identifies. */
data class ChartObject(val label: String?, val name: String, val azimuthDeg: Double, val altitudeDeg: Double, val magnitude: Double, val kind: ChartKind)

data class ChartState(val timeMs: Long, val objects: List<ChartObject>, val facingDeg: Double?, val sunAltitudeDeg: Double)

@OptIn(ExperimentalCoroutinesApi::class)
class StargazeViewModel(private val c: AppContainer, routePlace: StarPlace?) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)
    val settings: StateFlow<Settings?> = c.prefs.settings.stateIn(viewModelScope, started, null)

    /** Minutes ahead of now shown on the chart (0 … 12 h). */
    val chartOffsetMin = MutableStateFlow(0)
    val followCompass = MutableStateFlow(false)

    private val minuteTicker: Flow<Long> = flow {
        while (true) {
            emit(c.clock.nowMs())
            delay(60_000L - c.clock.nowMs() % 60_000L)
        }
    }

    val place: StateFlow<StarPlace?> = (
        if (routePlace != null) flowOf(routePlace)
        else c.location.fix.map { r ->
            // Rounded to ~1 km: star positions do not change visibly, and a jittering fix must not recompute the night.
            fun rounded(p: LatLon) = LatLon(Math.round(p.lat * 100) / 100.0, Math.round(p.lon * 100) / 100.0)
            when (r) {
                is Reading.Value -> StarPlace(rounded(r.value.position), null, false)
                // Only while location is on and permitted, and still searching: a switched-off location stays off.
                Reading.Acquiring -> c.location.lastKnown(LAST_KNOWN_MAX_AGE_MS, c.clock.nowMs())?.let { StarPlace(rounded(it.position), null, false, lastKnown = true) }
                is Reading.Unavailable -> null
            }
        }.distinctUntilChanged()
        ).stateIn(viewModelScope, started, routePlace)

    private val nightDay: Flow<Long> = minuteTicker.map { LocalDays.nightOf(it) }.distinctUntilChanged()

    val tonight: StateFlow<Tonight?> = combine(place, nightDay) { p, d -> p to d }
        .distinctUntilChanged()
        .map { (p, d) -> p?.let { compute(it, d) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, started, null)

    val polar: StateFlow<PolarAlignment?> = combine(place, minuteTicker) { p, now ->
        p?.let { NightSky.polarAlignment(it.position.lat, it.position.lon, now) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, started, null)

    /** True heading while "follow compass" is on; the sensors run only then. */
    val facing: StateFlow<Double?> = followCompass.flatMapLatest { on ->
        if (!on) flowOf(null)
        else combine(c.orientation.orientation(Hold.Flat), c.location.fix) { o, f ->
            val v = (o as? Reading.Value)?.value ?: return@combine null
            val decl = (f as? Reading.Value)?.value?.let { Declination.degrees(it) } ?: return@combine null
            mod360(v.azimuthDeg + decl)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, started, null)

    val chart: StateFlow<ChartState?> = combine(place, chartOffsetMin, minuteTicker) { p, off, now ->
        p?.let { chartAt(it.position, now + off * 60_000L) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, started, null)

    /** Whether a "follow compass" chart can have a true heading at all: it needs both a compass and a position fix. */
    val compassAvailable: Boolean get() = c.orientation.headingSource != null

    fun setNightRed(on: Boolean) = viewModelScope.launch { c.prefs.update { it.copy(nightRed = on) } }

    private fun compute(p: StarPlace, nightDay: Long): Tonight {
        val (start, end) = LocalDays.noonToNoon(nightDay)
        val lat = p.position.lat
        val lon = p.position.lon
        val planets = Planet.entries.map { planet ->
            val mid = Planets.position(planet, start + (end - start) / 2)
            val track = track(start, end) { t -> Planets.horizontal(planet, t, lat, lon).apparentAltitudeDeg }
            PlanetTonight(planet, NightSky.planetNight(planet, lat, lon, start, end), mid.magnitude, mid.elongationDeg, track)
        }
        val now = c.clock.nowMs()
        val showers = MeteorShowers.upcoming(now)
            .filter { it.activeNow || it.peakMs - now < 45L * 86_400_000L }
            .take(4)
            .map { o -> bestRate(o, lat, lon) }
        return Tonight(
            place = p,
            nightDay = nightDay,
            plan = NightSky.plan(lat, lon, start, end),
            planets = planets,
            galacticCentre = NightSky.galacticCentreNight(lat, lon, start, end),
            galacticTrack = galacticTrack(lat, lon, start, end),
            showers = showers,
        )
    }

    private fun track(start: Long, end: Long, altitude: (Long) -> Double): AltitudeTrack {
        val n = ((end - start) / TRACK_STEP_MS).toInt() + 1
        return AltitudeTrack(start, TRACK_STEP_MS, List(n) { i -> altitude(start + i * TRACK_STEP_MS).toFloat() })
    }

    private fun galacticTrack(lat: Double, lon: Double, start: Long, end: Long): AltitudeTrack {
        // Precession and aberration move the core by arcseconds over a night: one apparent place serves the whole track.
        val gc = ApparentPlace.fromJ2000(NightSky.GALACTIC_CENTRE_RA, NightSky.GALACTIC_CENTRE_DEC, JulianDay.ephemeris(start))
        val eq = com.trailblazer.core.astro.Equatorial(gc.raDeg, gc.decDeg, Double.POSITIVE_INFINITY)
        return track(start, end) { t -> HorizontalTransform.toHorizontal(t, lat, lon, eq).apparentAltitudeDeg }
    }

    /** The best hourly rate on the peak night: the radiant's highest point while the Sun is below −12°. */
    private fun bestRate(o: ShowerOutlook, lat: Double, lon: Double): ShowerTonight {
        val (start, end) = LocalDays.noonToNoon(LocalDays.nightOf(o.peakMs))
        var best: Double? = null
        var bestT: Long? = null
        var t = start
        while (t < end) {
            if (SolarPosition.horizontal(t, lat, lon).altitudeDeg < -12.0) {
                val rate = MeteorShowers.expectedRate(o.shower, MeteorShowers.radiantAltitude(o.shower, t, lat, lon))
                if (rate != null && (best == null || rate > best)) {
                    best = rate
                    bestT = t
                }
            }
            t += 30 * 60_000L
        }
        return ShowerTonight(o, best, bestT)
    }

    private fun chartAt(p: LatLon, t: Long): ChartState {
        val jde = JulianDay.ephemeris(t)
        val apparentCtx = com.trailblazer.core.astro.ApparentContext(jde)
        val horizCtx = com.trailblazer.core.astro.HorizontalContext(t, p.lon)
        val out = ArrayList<ChartObject>(BrightStars.all.size + 10)
        fun add(label: String?, name: String, ra: Double, dec: Double, mag: Double, kind: ChartKind) {
            val eq = com.trailblazer.core.astro.Equatorial(ra, dec, Double.POSITIVE_INFINITY)
            val h = horizCtx.toHorizontal(p.lat, eq)
            if (h.altitudeDeg > -1.0) out += ChartObject(label, name, h.azimuthDeg, h.apparentAltitudeDeg, mag, kind)
        }
        for (s in BrightStars.all) {
            val eq = s.apparent(apparentCtx)
            add(if (s.name != null && s.vmag < 1.6) s.label else null, s.label, eq.raDeg, eq.decDeg, s.vmag, ChartKind.Star)
        }
        val gc = apparentCtx.apply(NightSky.GALACTIC_CENTRE_RA, NightSky.GALACTIC_CENTRE_DEC)
        add("Milky Way core", "Milky Way core", gc.raDeg, gc.decDeg, 99.0, ChartKind.GalacticCentre)
        for (planet in Planet.entries) {
            val pos = Planets.position(planet, t)
            add(planet.label, planet.label, pos.apparent.raDeg, pos.apparent.decDeg, pos.magnitude, ChartKind.Planet)
        }
        val moon = LunarPosition.horizontal(t, p.lat, p.lon)
        if (moon.altitudeDeg > -1.0) {
            val moonLabel = "Moon ${(LunarPhase.at(t).illumination * 100).toInt()} %"
            out += ChartObject(moonLabel, moonLabel, moon.azimuthDeg, moon.apparentAltitudeDeg, -12.0, ChartKind.Moon)
        }
        return ChartState(t, out, null, SolarPosition.horizontal(t, p.lat, p.lon).apparentAltitudeDeg)
    }
}

private const val TRACK_STEP_MS = 15 * 60_000L

/**
 * The chart object nearest a tap at unit-disc coordinates ([x], [y]) (the rim at radius 1, as [SkyProjection] draws),
 * or null when nothing above the horizon is within [maxDistance]. On a near tie the brighter object wins, so a tap on
 * a planet among faint stars finds the planet.
 */
fun identify(objects: List<ChartObject>, facingDeg: Double, x: Double, y: Double, maxDistance: Double = 0.08): ChartObject? {
    if (!x.isFinite() || !y.isFinite()) return null
    return objects.mapNotNull { o ->
        SkyProjection.project(o.azimuthDeg, o.altitudeDeg, facingDeg)?.let { p -> o to kotlin.math.hypot(p.x - x, p.y - y) }
    }.filter { it.second <= maxDistance }
        .minByOrNull { (o, d) -> d + 0.004 * o.magnitude.coerceIn(-5.0, 6.0) }?.first
}
