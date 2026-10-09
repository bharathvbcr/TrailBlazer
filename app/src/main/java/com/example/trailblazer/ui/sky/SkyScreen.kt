package com.example.trailblazer.ui.sky

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.trailblazer.weather.ForecastTimes
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trailblazer.container
import com.example.trailblazer.permissions.AppPermission
import com.trailblazer.core.sensors.Reading
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.LocalDays
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.PermissionGate
import com.example.trailblazer.ui.components.RangeBars
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.HereOption
import com.example.trailblazer.ui.components.PlacePicker
import com.example.trailblazer.ui.components.scrubX
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.components.Sparkline
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.components.WindArrow
import com.example.trailblazer.ui.components.ValueTile
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.SettingsRoute
import com.example.trailblazer.ui.nav.StargazeRoute
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.example.trailblazer.weather.ForecastResult
import com.example.trailblazer.weather.OpenMeteoClient
import com.example.trailblazer.weather.WmoCode
import com.trailblazer.core.astro.Band
import com.trailblazer.core.astro.DaySlice
import com.trailblazer.core.astro.DayType
import com.trailblazer.core.astro.DaylightStatus
import com.trailblazer.core.astro.LunarDay
import com.trailblazer.core.astro.LunarPhase
import com.trailblazer.core.astro.SolarEvents
import com.trailblazer.core.astro.MoonPhaseName
import com.trailblazer.core.astro.SolarDay
import com.trailblazer.core.atmo.DewPoint
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.plot.SeriesPlot
import com.trailblazer.core.weather.Tendency
import com.trailblazer.core.weather.TrendBasis
import com.trailblazer.core.weather.TrendResult
import com.trailblazer.core.weather.ZambrettiForecast
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import com.trailblazer.core.math.cardinal16
import com.trailblazer.core.astro.Horizontal

@Composable
fun SkyScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val vm: SkyViewModel = viewModel { SkyViewModel(ctx.container) }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val sky by vm.sky.collectAsStateWithLifecycle()
    val place by vm.place.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val forecast by vm.forecast.collectAsStateWithLifecycle()
    val loading by vm.forecastLoading.collectAsStateWithLifecycle()
    val offset by vm.dayOffset.collectAsStateWithLifecycle()
    val temp by vm.environment.temperatureC.collectAsStateWithLifecycle()
    val humidity by vm.environment.humidityPct.collectAsStateWithLifecycle()
    var placeDialog by rememberSaveable { mutableStateOf(false) }
    var consentDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.forecastConsent, place?.position) {
        if (settings.forecastConsent && place != null) vm.loadForecast(force = false)
    }

    ScreenScaffold(
        title = "Sky",
        actions = { IconButton(onClick = { nav.go(SettingsRoute) }) { Icon(TrailIcons.Settings, "Settings") } },
    ) {
        item {
            GlassCard(padding = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.step(-1) }) { Icon(TrailIcons.Back, "Previous day") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val s = sky
                        Text(if (s != null) fmt.date(LocalDays.window(s.epochDay).first + 12 * 3_600_000L) else "—", style = MaterialTheme.typography.titleMedium)
                        Text(
                            place?.let { p -> p.label ?: Dms.format(p.position, settings.coordinateFormat) + if (p.manual) "" else " (here)" } ?: "No location yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.step(1) }) { Icon(TrailIcons.Chevron, "Next day") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    if (offset != 0) FilterChip(selected = false, onClick = { vm.dayOffset.value = 0 }, label = { Text("Today") })
                    FilterChip(selected = place?.manual == true, onClick = { placeDialog = true }, label = { Text("Change place") })
                }
            }
        }
        val s = sky
        if (s == null) {
            item {
                PermissionGate(AppPermission.Location, "Sun and moon times need a position. Allow location, or choose “Change place” to enter coordinates.") {
                    GlassCard { Text("Waiting for a position fix… You can also enter a place.", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        } else {
            item { SunCard(s, fmt) }
            item { MoonCard(s, fmt) }
            item {
                val pl = s.place
                StargazeLinkCard { nav.go(if (pl.manual) StargazeRoute(pl.position.lat, pl.position.lon, pl.label) else StargazeRoute()) }
            }
        }
        item { SectionTitle("Weather from your barometer") }
        item { WeatherCard(weather, fmt) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ValueTile("Temperature", temp, "thermometer", Modifier.weight(1f)) { fmt.temperature(it) }
                ValueTile("Humidity", humidity, "humidity sensor", Modifier.weight(1f), caption = { h ->
                    (temp as? Reading.Value)?.value?.let { t -> DewPoint.celsius(t, h.value)?.let { "Dew point ${fmt.temperature(it)}" } }
                }) { "${it.roundToInt()} %" }
            }
        }
        item { SectionTitle("Online forecast") }
        item {
            ForecastCard(
                consented = settings.forecastConsent,
                result = forecast,
                loading = loading,
                fmt = fmt,
                hasPlace = place != null,
                onEnable = { consentDialog = true },
                onRefresh = { vm.loadForecast(force = true) },
                onDisable = { vm.setConsent(false) },
            )
        }
    }

    if (placeDialog) PlacePicker(
        title = "Choose a place",
        onDismiss = { placeDialog = false },
        onPick = { places -> places.firstOrNull()?.let { vm.manualPlace.value = SkyPlace(it.position, it.label, true) }; placeDialog = false },
        // Here means follow the live position, not a fixed copy of it.
        here = HereOption("Use my location") { vm.manualPlace.value = null; placeDialog = false; null },
    )
    if (consentDialog) AlertDialog(
        onDismissRequest = { consentDialog = false },
        title = { Text("Turn on online forecast?") },
        text = {
            Text(
                "TrailBlazer will send the position you are viewing, rounded to about 1 km, to api.open-meteo.com over HTTPS " +
                    "to fetch a forecast. Nothing else is sent, and nothing is sent until you turn this on. " +
                    "Open-Meteo is free for non-commercial use; data is licensed CC BY 4.0. You can turn this off in Settings at any time.",
            )
        },
        confirmButton = { TextButton(onClick = { vm.setConsent(true); consentDialog = false }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = { consentDialog = false }) { Text("Not now") } },
    )
}

@Composable
private fun SunCard(s: SkyState, fmt: Fmt) {
    val sun = s.sun
    val colors = sunColors()
    val last = (sun.windowEndMs - 1).coerceAtLeast(sun.windowStartMs)
    val marker = if (s.isToday) s.nowMs else sun.solarNoonMs
    var at by remember(sun.windowStartMs, sun.windowEndMs) { mutableLongStateOf((marker ?: sun.windowStartMs).coerceIn(sun.windowStartMs, last)) }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TrailIcons.Sun, null, tint = LocalStatusColors.current.caution)
            Spacer(Modifier.width(8.dp))
            Text("Sun", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (s.isToday) {
            val headline = when (val st = s.status) {
                is DaylightStatus.DaylightLeft -> "${fmt.duration(st.ms)} of daylight left"
                is DaylightStatus.UntilSunrise -> "Sunrise in ${fmt.duration(st.ms)}"
                DaylightStatus.AfterSunset -> "The sun has set"
                DaylightStatus.PolarDay -> "Midnight sun: up all day"
                DaylightStatus.PolarNight -> "Polar night: below the horizon all day"
            }
            Text(headline, style = MaterialTheme.typography.headlineSmall)
        } else {
            Text(
                when (sun.dayType) {
                    DayType.PolarDay -> "Midnight sun: up all day"
                    DayType.PolarNight -> "Polar night: below the horizon all day"
                    DayType.Normal -> "Day length ${fmt.duration(sun.daylightMs)}"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Spacer(Modifier.height(10.dp))
        SkyArc(
            path = s.sunPath, sunPath = s.sunPath, windowStartMs = sun.windowStartMs, stepMs = PATH_STEP_MS, atMs = at, fmt = fmt,
            lineColor = colors.sun, golden = colors.golden,
            description = "Sun's height through the day, highest ${sun.noonAltitudeDeg?.roundToInt() ?: 0} degrees",
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))
                .scrubX(sun.windowStartMs, sun.windowEndMs) { f -> at = SeriesPlot.fractionToEpoch(f, sun.windowStartMs, sun.windowEndMs) },
        ) { p, r, below -> sunGlyph(p, r, colors, dim = below) }
        Spacer(Modifier.height(8.dp))
        DayPlot(sun, s.moon, marker, at, { at = it }, fmt, heightAt = { t -> altitudeAt(s.sunPath, sun.windowStartMs, t) })
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizonCompass(
                path = s.sunPath,
                riseAzDeg = sun.sunriseAzimuthDeg,
                setAzDeg = sun.sunsetAzimuthDeg,
                now = if (s.isToday) Horizontal(s.sunNow.azimuthDeg, s.sunNow.apparentAltitudeDeg) else null,
                bodyColor = colors.sun,
                description = listOfNotNull(
                    sun.sunriseAzimuthDeg?.let { "Rises in the ${cardinal16(it)}" },
                    sun.sunsetAzimuthDeg?.let { "sets in the ${cardinal16(it)}" },
                ).joinToString(", ").ifEmpty { "Sun's path" },
                modifier = Modifier.size(132.dp),
            ) { p, r, below -> sunGlyph(p, r, colors, dim = below) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                sun.sunriseMs?.let { Fact("Sunrise", fmt.time(it), sun.sunriseAzimuthDeg?.let { a -> "from the ${fmt.bearing(a)}" }) }
                sun.solarNoonMs?.let { Fact("Highest", fmt.time(it), sun.noonAltitudeDeg?.let { a -> "${a.roundToInt()}° up" + (s.sunPath.maxByOrNull { it.altitudeDeg }?.let { p -> " in the ${cardinal16(p.azimuthDeg)}" } ?: "") }) }
                sun.sunsetMs?.let { Fact("Sunset", fmt.time(it), sun.sunsetAzimuthDeg?.let { a -> "to the ${fmt.bearing(a)}" }) }
            }
        }
        Spacer(Modifier.height(12.dp))
        FactGrid(
            listOfNotNull(
                sun.civil.startMs?.let { Triple("First light", fmt.time(it), "civil dawn") },
                sun.civil.endMs?.let { Triple("Last light", fmt.time(it), "civil dusk") },
                sun.goldenEvening?.let { b -> bandText(b, fmt)?.let { Triple("Golden hour", it, "evening") } },
                sun.blueEvening?.let { b -> bandText(b, fmt)?.let { Triple("Blue hour", it, "evening") } },
                if (sun.dayType == DayType.Normal) Triple("Day length", fmt.duration(sun.daylightMs), s.daylightChangeMs?.let { changeText(it) }) else null,
                if (s.isToday) Triple("Now", "${kotlin.math.abs(s.sunNow.apparentAltitudeDeg.roundToInt())}° ${if (s.sunNow.apparentAltitudeDeg >= 0) "up" else "below"}", fmt.bearing(s.sunNow.azimuthDeg)) else null,
                shadowText(s)?.let { Triple("Your shadow", it, "of your height") },
            ),
        )
    }
}

private fun bandText(b: Band, fmt: Fmt): String? {
    val a = b.startMs ?: return null
    val e = b.endMs ?: return null
    return "${fmt.time(a)}–${fmt.time(e)}"
}

/** "+2 min 5 s vs yesterday": near the solstices the change is seconds, so seconds are kept. */
internal fun changeText(ms: Long): String {
    val sign = if (ms >= 0) "+" else "−"
    val s = kotlin.math.abs(ms) / 1000
    val body = if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
    return "$sign$body vs yesterday"
}

/** Shadow length for something upright, now: 1 / tan(altitude); only while the Sun is usefully high. */
private fun shadowText(s: SkyState): String? {
    if (!s.isToday) return null
    val alt = s.sunNow.apparentAltitudeDeg
    if (alt < 3.0) return null
    val ratio = 1 / kotlin.math.tan(Math.toRadians(alt))
    return if (ratio >= 10) "${ratio.roundToInt()}×" else String.format(java.util.Locale.getDefault(), "%.1f×", ratio)
}

/** Linear interpolation in a 15-minute path. */
private fun altitudeAt(path: List<Horizontal>, startMs: Long, t: Long): Double? {
    if (path.size < 2) return null
    val f = ((t - startMs).toDouble() / PATH_STEP_MS).coerceIn(0.0, path.size - 1.0)
    val lo = f.toInt().coerceAtMost(path.size - 2)
    return path[lo].altitudeDeg + (path[lo + 1].altitudeDeg - path[lo].altitudeDeg) * (f - lo)
}

@Composable
private fun Fact(label: String, value: String, detail: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Facts two to a row. */
@Composable
private fun FactGrid(facts: List<Triple<String, String, String?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in facts.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((label, value, detail) in row) Box(Modifier.weight(1f)) { Fact(label, value, detail) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Daylight bands you can scrub. The caption names the innermost band under your finger and the Sun's height. */
@Composable
private fun DayPlot(sun: SolarDay, moon: LunarDay, markerMs: Long?, at: Long, onAt: (Long) -> Unit, fmt: Fmt, heightAt: (Long) -> Double?) {
    val cs = MaterialTheme.colorScheme
    val night = LocalStatusColors.current.isNight
    val day = if (night) cs.primary.copy(alpha = 0.6f) else Color(0xFFFFD27A)
    val golden = if (night) cs.tertiary else Color(0xFFE8A317)
    val blue = if (night) cs.primary.copy(alpha = 0.35f) else Color(0xFF6A8FD4)
    val civil = if (night) cs.primary.copy(alpha = 0.4f) else Color(0xFF8FB8E8)
    val naut = if (night) cs.primary.copy(alpha = 0.25f) else Color(0xFF4F6FA8)
    val astro = if (night) cs.primary.copy(alpha = 0.15f) else Color(0xFF2B3B66)
    val dark = if (night) Color.Black else Color(0xFF141A2E)
    Column {
        Text(
            listOfNotNull(fmt.time(at), sliceLabel(SolarEvents.slice(sun, at)), heightAt(at)?.let { "Sun ${it.roundToInt()}°" }).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = cs.primary,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = "Daylight timeline" }
                .scrubX(sun.windowStartMs, sun.windowEndMs) { f -> onAt(SeriesPlot.fractionToEpoch(f, sun.windowStartMs, sun.windowEndMs)) },
        ) {
            val span = (sun.windowEndMs - sun.windowStartMs).toFloat().coerceAtLeast(1f)
            fun x(ms: Long?) = ms?.let { ((it - sun.windowStartMs) / span * size.width).coerceIn(0f, size.width) }
            drawRect(if (sun.dayType == DayType.PolarDay) day else dark)
            fun seg(b: Band?, c: Color) {
                if (b == null || (b.startMs == null && b.endMs == null)) return
                val a = x(b.startMs) ?: 0f
                val e = x(b.endMs) ?: size.width
                drawRect(c, Offset(a, 0f), Size((e - a).coerceAtLeast(0f), size.height))
            }
            if (sun.dayType != DayType.PolarDay) {
                seg(sun.astronomical, astro)
                seg(sun.nautical, naut)
                seg(sun.civil, civil)
                seg(sun.blueMorning, blue)
                seg(sun.blueEvening, blue)
                if (sun.sunriseMs != null || sun.sunsetMs != null) seg(Band(sun.sunriseMs, sun.sunsetMs), day)
                seg(sun.goldenMorning, golden)
                seg(sun.goldenEvening, golden)
            }
            x(markerMs)?.let { nx -> drawRect(cs.error.copy(alpha = 0.85f), Offset(nx - 1.5f, 0f), Size(3f, size.height)) }
            x(at)?.let { nx ->
                drawRect(cs.onSurface, Offset(nx - 1.5f, 0f), Size(3f, size.height))
                drawCircle(cs.onSurface, 5.dp.toPx(), Offset(nx, size.height / 2f))
            }
            for (ms in listOf(moon.moonriseMs, moon.moonsetMs)) {
                x(ms)?.let { nx -> drawCircle(cs.secondary, 4.dp.toPx(), Offset(nx, 10.dp.toPx())) }
            }
        }
    }
}

private fun sliceLabel(slice: DaySlice) = when (slice) {
    DaySlice.PolarDay -> "Midnight sun"
    DaySlice.PolarNight -> "Polar night"
    DaySlice.Day -> "Day"
    DaySlice.Golden -> "Golden hour"
    DaySlice.Blue -> "Blue hour"
    DaySlice.Civil -> "Civil twilight"
    DaySlice.Nautical -> "Nautical twilight"
    DaySlice.Astronomical -> "Astronomical twilight"
    DaySlice.Night -> "Night"
}

@Composable
private fun MoonCard(s: SkyState, fmt: Fmt) {
    val p = s.phase
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonDisc(p.illumination, LunarPhase.litOnRight(p.waxing, s.place.position.lat), Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(phaseName(p.name), style = MaterialTheme.typography.titleLarge)
                Text("${(p.illumination * 100).roundToInt()} % lit · ${fmt.num(p.ageDays, 1)} days old", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val moons = listOfNotNull(s.nextFullMs?.let { "Full ${fmt.date(it)}" }, s.nextNewMs?.let { "New ${fmt.date(it)}" })
        if (moons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                moons.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        MoonCompass(s, fmt)
        Spacer(Modifier.height(4.dp))
        MoonPhasesDropdown(p, s.upcomingPhases, s.place.position.lat, fmt)
    }
}

/** Where the Moon rises, peaks and sets on the shown day, as compass directions, with its path drawn looking up. */
@Composable
private fun MoonCompass(s: SkyState, fmt: Fmt) {
    val m = s.moon
    val lit = LunarPhase.litOnRight(s.phase.waxing, s.place.position.lat)
    val moonColor = if (LocalStatusColors.current.isNight) MaterialTheme.colorScheme.primary else Color(0xFFE9E1C4)
    val now = if (s.isToday) Horizontal(s.moonNow.azimuthDeg, s.moonNow.apparentAltitudeDeg) else null
    // The unlit part is drawn dark enough to read against the blue dome and the night sky.
    val shade = if (LocalStatusColors.current.isNight) Color.Black else Color(0xFF2B3140)
    val start = s.sun.windowStartMs
    val end = s.sun.windowEndMs
    val initial = (if (s.isToday) s.nowMs else m.transitMs ?: (start + end) / 2).coerceIn(start, end - 1)
    var at by remember(start, end) { mutableLongStateOf(initial) }
    Text(
        listOfNotNull(fmt.time(at), altitudeAt(s.moonPath, start, at)?.let { if (it >= 0) "Moon ${it.roundToInt()}° up" else "Moon below the horizon" }).joinToString(" · "),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    SkyArc(
        path = s.moonPath, sunPath = s.sunPath, windowStartMs = start, stepMs = PATH_STEP_MS, atMs = at, fmt = fmt,
        lineColor = moonColor,
        fillAlpha = 0.18f,
        description = "Moon's height through the day" + (s.moonTransitAltDeg?.let { ", highest ${it.roundToInt()} degrees" } ?: ""),
        modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp))
            .scrubX(start, end) { f -> at = SeriesPlot.fractionToEpoch(f, start, end) },
    ) { c, r, below ->
        drawCircle(Brush.radialGradient(listOf(moonColor.copy(alpha = if (below) 0.1f else 0.35f), Color.Transparent), c, r * 2.8f), r * 2.8f, c)
        drawMoon(c, r * 1.2f, s.phase.illumination, lit, moonColor, shade, alpha = if (below) 0.5f else 1f)
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizonCompass(
            path = s.moonPath,
            riseAzDeg = s.moonriseAzDeg,
            setAzDeg = s.moonsetAzDeg,
            now = now,
            bodyColor = moonColor,
            description = listOfNotNull(
                s.moonriseAzDeg?.let { "Moon rises in the ${cardinal16(it)}" },
                s.moonsetAzDeg?.let { "sets in the ${cardinal16(it)}" },
                now?.let { "now ${it.altitudeDeg.roundToInt()} degrees ${if (it.altitudeDeg >= 0) "up" else "below the horizon"} in the ${cardinal16(it.azimuthDeg)}" },
            ).joinToString(", ").ifEmpty { "Moon's path" },
            modifier = Modifier.size(132.dp),
        ) { c, r, below ->
            drawCircle(Brush.radialGradient(listOf(moonColor.copy(alpha = if (below) 0.15f else 0.4f), Color.Transparent), c, r * 2.6f), r * 2.6f, c)
            drawMoon(c, r * 1.25f, s.phase.illumination, lit, moonColor, shade, alpha = if (below) 0.5f else 1f)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                m.alwaysUp -> Fact("All day", "Up", "never sets on this day")
                m.alwaysDown -> Fact("All day", "Down", "never rises on this day")
            }
            // In time order: on many days the Moon sets in the morning and rises again in the evening.
            val events = listOfNotNull(
                m.moonriseMs?.let { t -> Triple(t, "Moonrise", s.moonriseAzDeg?.let { a -> "from the ${fmt.bearing(a)}" }) },
                m.transitMs?.let { t -> Triple(t, "Highest", s.moonTransitAltDeg?.let { a -> if (a >= 0) "${a.roundToInt()}° up" + (s.moonPath.maxByOrNull { it.altitudeDeg }?.let { p -> " in the ${cardinal16(p.azimuthDeg)}" } ?: "") else "stays below the horizon" }) },
                m.moonsetMs?.let { t -> Triple(t, "Moonset", s.moonsetAzDeg?.let { a -> "to the ${fmt.bearing(a)}" }) },
            ).sortedBy { it.first }
            for ((t, label, detail) in events) Fact(label, fmt.time(t), detail)
            now?.let { Fact("Now", fmt.bearing(it.azimuthDeg), if (it.altitudeDeg >= 0) "${it.altitudeDeg.roundToInt()}° up" else "below the horizon") }
        }
    }
}

internal fun phaseName(n: MoonPhaseName) = when (n) {
    MoonPhaseName.NewMoon -> "New moon"
    MoonPhaseName.WaxingCrescent -> "Waxing crescent"
    MoonPhaseName.FirstQuarter -> "First quarter"
    MoonPhaseName.WaxingGibbous -> "Waxing gibbous"
    MoonPhaseName.FullMoon -> "Full moon"
    MoonPhaseName.WaningGibbous -> "Waning gibbous"
    MoonPhaseName.LastQuarter -> "Last quarter"
    MoonPhaseName.WaningCrescent -> "Waning crescent"
}

/**
 * Moon disc lit by [illumination], on the side the observer sees lit (see [LunarPhase.litOnRight]). [describe] is off
 * where the parent already names the phase, so a screen reader does not read it twice.
 */
@Composable
internal fun MoonDisc(illumination: Double, litOnRight: Boolean, modifier: Modifier, describe: Boolean = true) {
    val lit = if (LocalStatusColors.current.isNight) MaterialTheme.colorScheme.primary else Color(0xFFF2EBD3)
    val shade = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(if (describe) modifier.semantics { contentDescription = "Moon ${(illumination * 100).roundToInt()} percent lit" } else modifier) {
        drawMoon(center, size.minDimension / 2, illumination, litOnRight, lit, shade)
    }
}

@Composable
private fun WeatherCard(w: WeatherState?, fmt: Fmt) {
    GlassCard {
        if (w == null) {
            Text("Reading barometer…")
            return@GlassCard
        }
        if (w.stationHpa == null) {
            Text("This phone has no barometer, so there is no pressure trend. The online forecast below still works.", style = MaterialTheme.typography.bodyMedium)
            return@GlassCard
        }
        val status = LocalStatusColors.current
        if (w.stormAlert) {
            Text("Storm warning: pressure is falling fast", color = status.danger, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
        }
        LabelValue("Station pressure", fmt.pressure(w.stationHpa))
        w.qnh?.let { LabelValue(if (it.approximate) "Sea-level pressure (approx.)" else "Sea-level pressure", fmt.pressure(it.hpa)) }
        when (val t = w.trend) {
            is TrendResult.Insufficient -> Text(
                "Need an hour of readings (${t.spanMinutes} min).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is TrendResult.Trend -> Text(
                "${tendencyName(t.tendency)} · ${fmt.pressureDelta(t.hpaPer3h)} / 3 h · ${
                    when (t.basis) {
                        TrendBasis.Station -> "same elevation"
                        TrendBasis.SeaLevel -> "sea level"
                        TrendBasis.StationElevationUnknown -> "elevation unknown"
                    }
                }",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val series = w.history.map { it.stationHpa as Double? }
        if (SeriesPlot.frame(series, 2.0) != null) {
            Spacer(Modifier.height(8.dp))
            Sparkline(series, minSpan = 2.0, valueText = { fmt.pressure(it) })
        }
        w.zambretti?.let { z ->
            Spacer(Modifier.height(8.dp))
            LabelValue("Local forecast (Zambretti)", zambrettiText(z))
            if (w.qnh?.approximate == true) Text("Uses GPS ellipsoid height; can be off by one step.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun tendencyName(t: Tendency) = when (t) {
    Tendency.FallingVeryRapidly -> "Falling very rapidly"
    Tendency.FallingQuickly -> "Falling quickly"
    Tendency.Falling -> "Falling"
    Tendency.FallingSlowly -> "Falling slowly"
    Tendency.Steady -> "Steady"
    Tendency.RisingSlowly -> "Rising slowly"
    Tendency.Rising -> "Rising"
    Tendency.RisingQuickly -> "Rising quickly"
    Tendency.RisingVeryRapidly -> "Rising very rapidly"
}

private fun zambrettiText(z: ZambrettiForecast): String =
    z.name.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun ForecastCard(
    consented: Boolean,
    result: ForecastResult?,
    loading: Boolean,
    fmt: Fmt,
    hasPlace: Boolean,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    onDisable: () -> Unit,
) {
    val ctx = LocalContext.current
    GlassCard {
        if (!consented) {
            Text("Off. Everything else in TrailBlazer works offline.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onEnable) { Text("Turn on forecast…") }
            return@GlassCard
        }
        if (!hasPlace) {
            Text("Waiting for a position or a chosen place.")
            return@GlassCard
        }
        when (result) {
            null -> Text(if (loading) "Loading forecast…" else "No forecast yet.")
            ForecastResult.NotConsented -> Text("Forecast is off.")
            is ForecastResult.Failed -> Text("Couldn’t load the forecast (${result.message}). You may be offline.", color = LocalStatusColors.current.caution)
            is ForecastResult.Ok -> ForecastBody(result, fmt)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
            TextButton(onClick = onDisable) { Text("Turn off") }
        }
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, OpenMeteoClient.ATTRIBUTION_URL.toUri())) }) {
            Text(OpenMeteoClient.ATTRIBUTION, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A loaded forecast: now, the next hours as a line, the next days as bars. Times are labelled for people, not ISO. */
@Composable
internal fun ForecastBody(result: ForecastResult.Ok, fmt: Fmt) {
    val r = result.forecast.response
    val locale = LocalConfiguration.current.locales[0]
    val offset = r.utcOffsetSeconds
    r.current?.let { cur ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(cur.weatherCode, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cur.temperatureC?.let { fmt.temperature(it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
                Text(WmoCode.describe(cur.weatherCode), style = MaterialTheme.typography.bodyMedium)
            }
            cur.windDirDeg?.let { dir ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WindArrow(dir, Modifier.size(48.dp))
                    cur.windKmh?.let { Text(fmt.speed(it / 3.6), style = MaterialTheme.typography.labelMedium) }
                    Text("from ${cardinal16(dir)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val details = listOfNotNull(
            cur.apparentC?.let { "Feels like ${fmt.temperature(it)}" },
            cur.humidityPct?.let { "Humidity ${it.roundToInt()} %" },
            cur.gustKmh?.let { "Gusts ${fmt.speed(it / 3.6)}" },
        )
        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    r.hourly?.let { h ->
        val hours = h.time.map { ForecastTimes.epochMs(it, offset) }
        if (SeriesPlot.frame(h.temperatureC, 1.0) != null) {
            Spacer(Modifier.height(12.dp))
            Text("Next ${h.time.size} hours", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Sparkline(
                h.temperatureC,
                minSpan = 1.0,
                color = MaterialTheme.colorScheme.tertiary,
                caption = { i, t ->
                    listOfNotNull(
                        hours.getOrNull(i)?.let { fmt.time(it) } ?: (if (i == 0) "Now" else "+$i h"),
                        fmt.temperature(t),
                        h.precipProbPct.getOrNull(i)?.let { "rain ${it.roundToInt()} %" },
                        h.weatherCode.getOrNull(i)?.let { WmoCode.describe(it) },
                    ).joinToString(" · ")
                },
                axis = listOfNotNull(hours.firstOrNull()?.let { fmt.time(it) } ?: "Now", hours.lastOrNull()?.let { fmt.time(it) }),
            )
        }
    }
    r.daily?.let { d ->
        val today = r.current?.time?.take(10) ?: d.time.firstOrNull()
        Spacer(Modifier.height(12.dp))
        Text("Next ${d.time.size} days", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        RangeBars(
            lows = d.minC,
            highs = d.maxC,
            labels = d.time.map { ForecastTimes.dayLabel(it, today, locale) ?: "—" },
            valueLabel = { fmt.temperature(it) },
            caption = { i, bar ->
                listOfNotNull(
                    d.time.getOrNull(i)?.let { ForecastTimes.dayLong(it, locale) },
                    d.weatherCode.getOrNull(i)?.let { WmoCode.describe(it) },
                    "${fmt.temperature(bar.low)} to ${fmt.temperature(bar.high)}",
                    d.precipProbPct.getOrNull(i)?.let { "rain ${it.roundToInt()} %" },
                ).joinToString(" · ")
            },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        listOfNotNull(
            "Updated ${fmt.time(result.forecast.fetchedMs)}" + if (result.fromCache) " (saved copy)" else "",
            if (ForecastTimes.differsFromPhone(offset, result.forecast.fetchedMs)) "hours in your phone's time, days in the place's" else null,
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StargazeLinkCard(onOpen: () -> Unit) {
    GlassCard(onClick = onOpen, onClickLabel = "Open stargazing") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TrailIcons.Telescope, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Tonight’s sky", style = MaterialTheme.typography.titleMedium)
                Text("Dark-sky times, planets, sky chart, meteor showers and polar alignment for this place", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(TrailIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
