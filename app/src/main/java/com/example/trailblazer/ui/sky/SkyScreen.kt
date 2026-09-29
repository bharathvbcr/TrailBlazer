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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trailblazer.container
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.LocalDays
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.PermissionGate
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.components.Sparkline
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.components.ValueTile
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.SettingsRoute
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.example.trailblazer.weather.ForecastResult
import com.example.trailblazer.weather.OpenMeteoClient
import com.example.trailblazer.weather.WmoCode
import com.trailblazer.core.astro.Band
import com.trailblazer.core.astro.DayType
import com.trailblazer.core.astro.DaylightStatus
import com.trailblazer.core.astro.MoonPhaseName
import com.trailblazer.core.astro.SolarDay
import com.trailblazer.core.atmo.DewPoint
import com.trailblazer.core.geo.CoordinateParse
import com.trailblazer.core.geo.CoordinateParser
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.geo.RefusalReason
import com.trailblazer.core.math.cardinal16
import com.trailblazer.core.weather.Tendency
import com.trailblazer.core.weather.TrendBasis
import com.trailblazer.core.weather.TrendResult
import com.trailblazer.core.weather.ZambrettiForecast
import kotlin.math.roundToInt

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

    if (placeDialog) PlaceDialog(
        onDismiss = { placeDialog = false },
        onUseHere = { vm.manualPlace.value = null; placeDialog = false },
        onPick = { vm.manualPlace.value = it; placeDialog = false },
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
        DayBar(sun, if (s.isToday) s.nowMs else null)
        Spacer(Modifier.height(10.dp))
        fun t(ms: Long?) = ms?.let { fmt.time(it) } ?: "—"
        fun az(d: Double?) = d?.let { " · ${it.roundToInt()}° ${cardinal16(it)}" } ?: ""
        LabelValue("Sunrise", t(sun.sunriseMs) + az(sun.sunriseAzimuthDeg))
        LabelValue("Solar noon", t(sun.solarNoonMs) + (sun.noonAltitudeDeg?.let { " · ${fmt.angle(it)} high" } ?: ""))
        LabelValue("Sunset", t(sun.sunsetMs) + az(sun.sunsetAzimuthDeg))
        if (sun.dayType == DayType.Normal) LabelValue("Day length", fmt.duration(sun.daylightMs))
        fun band(b: Band?) = b?.let { "${t(it.startMs)} – ${t(it.endMs)}" } ?: "—"
        LabelValue("Golden hour (am)", band(sun.goldenMorning))
        LabelValue("Golden hour (pm)", band(sun.goldenEvening))
        LabelValue("Blue hour (am)", band(sun.blueMorning))
        LabelValue("Blue hour (pm)", band(sun.blueEvening))
        LabelValue("Civil twilight", "${t(sun.civil.startMs)} / ${t(sun.civil.endMs)}")
        LabelValue("Nautical twilight", "${t(sun.nautical.startMs)} / ${t(sun.nautical.endMs)}")
        LabelValue("Astronomical twilight", "${t(sun.astronomical.startMs)} / ${t(sun.astronomical.endMs)}")
        if (s.isToday) LabelValue("Sun now", "${fmt.bearing(s.sunNow.azimuthDeg)} · ${fmt.angle(s.sunNow.apparentAltitudeDeg)} elevation")
    }
}

/** A 24-hour band: night → twilights → day, with a marker at the current time. */
@Composable
private fun DayBar(sun: SolarDay, nowMs: Long?) {
    val cs = MaterialTheme.colorScheme
    val night = LocalStatusColors.current.isNight
    val day = if (night) cs.primary.copy(alpha = 0.6f) else Color(0xFFFFD27A)
    val civil = if (night) cs.primary.copy(alpha = 0.4f) else Color(0xFF8FB8E8)
    val naut = if (night) cs.primary.copy(alpha = 0.25f) else Color(0xFF4F6FA8)
    val astro = if (night) cs.primary.copy(alpha = 0.15f) else Color(0xFF2B3B66)
    val dark = if (night) Color.Black else Color(0xFF141A2E)
    Canvas(
        Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))
            .semantics { contentDescription = "Daylight timeline" },
    ) {
        val span = (sun.windowEndMs - sun.windowStartMs).toFloat()
        fun x(ms: Long?) = ms?.let { ((it - sun.windowStartMs) / span * size.width).coerceIn(0f, size.width) }
        drawRect(if (sun.dayType == DayType.PolarDay) day else dark)
        fun seg(b: Band, c: Color) {
            val a = x(b.startMs) ?: 0f
            val e = x(b.endMs) ?: size.width
            if (b.startMs == null && b.endMs == null) return
            drawRect(c, Offset(a, 0f), Size((e - a).coerceAtLeast(0f), size.height))
        }
        if (sun.dayType != DayType.PolarDay) {
            seg(sun.astronomical, astro)
            seg(sun.nautical, naut)
            seg(sun.civil, civil)
            if (sun.sunriseMs != null || sun.sunsetMs != null) seg(Band(sun.sunriseMs, sun.sunsetMs), day)
        }
        x(nowMs)?.let { nx -> drawRect(cs.error, Offset(nx - 1.5f, 0f), Size(3f, size.height)) }
    }
}

@Composable
private fun MoonCard(s: SkyState, fmt: Fmt) {
    val p = s.phase
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonDisc(p.illumination, p.waxing, Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(phaseName(p.name), style = MaterialTheme.typography.titleLarge)
                Text("${(p.illumination * 100).roundToInt()} % lit · ${fmt.num(p.ageDays, 1)} days old", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        val m = s.moon
        fun t(ms: Long?) = ms?.let { fmt.time(it) } ?: "—"
        when {
            m.alwaysUp -> LabelValue("Moon", "Above the horizon all day")
            m.alwaysDown -> LabelValue("Moon", "Below the horizon all day")
            else -> {
                LabelValue("Moonrise", t(m.moonriseMs))
                LabelValue("Moonset", t(m.moonsetMs))
            }
        }
        LabelValue("Highest (transit)", t(m.transitMs))
        LabelValue("Next full moon", s.nextFullMs?.let { fmt.dateTime(it) } ?: "—")
        LabelValue("Next new moon", s.nextNewMs?.let { fmt.dateTime(it) } ?: "—")
        if (s.isToday) LabelValue("Moon now", "${fmt.bearing(s.moonNow.azimuthDeg)} · ${fmt.angle(s.moonNow.altitudeDeg)} elevation")
        val dark = p.illumination < 0.25
        Text(
            if (dark) "Dark sky: good for stars and the Milky Way." else "Bright moonlight washes out faint stars.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun phaseName(n: MoonPhaseName) = when (n) {
    MoonPhaseName.NewMoon -> "New moon"
    MoonPhaseName.WaxingCrescent -> "Waxing crescent"
    MoonPhaseName.FirstQuarter -> "First quarter"
    MoonPhaseName.WaxingGibbous -> "Waxing gibbous"
    MoonPhaseName.FullMoon -> "Full moon"
    MoonPhaseName.WaningGibbous -> "Waning gibbous"
    MoonPhaseName.LastQuarter -> "Last quarter"
    MoonPhaseName.WaningCrescent -> "Waning crescent"
}

/** Moon disc lit by [illumination], on the right when waxing (northern-hemisphere view). */
@Composable
private fun MoonDisc(illumination: Double, waxing: Boolean, modifier: Modifier) {
    val lit = if (LocalStatusColors.current.isNight) MaterialTheme.colorScheme.primary else Color(0xFFF2EBD3)
    val shade = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.semantics { contentDescription = "Moon ${(illumination * 100).roundToInt()} percent lit" }.rotate(if (waxing) 0f else 180f)) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(shade, r, c)
        // Terminator: an ellipse whose half-width goes from +r (new) through 0 (quarter) to −r (full).
        val k = (1 - 2 * illumination).toFloat()
        val path = Path().apply {
            moveTo(c.x, c.y - r)
            arcTo(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, 180f, false)
            val w = r * k
            arcTo(androidx.compose.ui.geometry.Rect(c.x - kotlin.math.abs(w), c.y - r, c.x + kotlin.math.abs(w), c.y + r), 90f, if (w >= 0) 180f else -180f, false)
            close()
        }
        drawPath(path, lit)
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
                "Collecting history: the trend needs an hour of readings (have ${t.spanMinutes} min, ${t.samples} samples). " +
                    "Readings are saved every 10 minutes while the app is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is TrendResult.Trend -> {
                LabelValue("3-hour trend", "${tendencyName(t.tendency)} (${fmt.pressureDelta(t.hpaPer3h)} / 3 h)")
                Text(
                    when (t.basis) {
                        TrendBasis.Station -> "You stayed at about the same elevation, so this is the raw barometer trend."
                        TrendBasis.SeaLevel -> "Elevation changed, so readings were corrected to sea level using GPS height; treat small changes with caution."
                        TrendBasis.StationElevationUnknown -> "No GPS height recorded: only valid if you stayed at the same elevation."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val series = w.history.map { it.stationHpa }
        if (series.size >= 2) {
            Spacer(Modifier.height(8.dp))
            Text("Last 24 h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Sparkline(series, minSpan = 2.0)
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
            is ForecastResult.Ok -> {
                val r = result.forecast.response
                r.current?.let { cur ->
                    Text(WmoCode.describe(cur.weatherCode), style = MaterialTheme.typography.titleLarge)
                    cur.temperatureC?.let { LabelValue("Temperature", fmt.temperature(it) + (cur.apparentC?.let { a -> " (feels ${fmt.temperature(a)})" } ?: "")) }
                    cur.windKmh?.let { LabelValue("Wind", fmt.speed(it / 3.6) + (cur.windDirDeg?.let { d -> " from ${cardinal16(d)}" } ?: "") + (cur.gustKmh?.let { g -> ", gusts ${fmt.speed(g / 3.6)}" } ?: "")) }
                    cur.precipitation?.let { LabelValue("Precipitation", "${fmt.num(it, 1)} mm") }
                    cur.pressureMslHpa?.let { LabelValue("Sea-level pressure", fmt.pressure(it)) }
                }
                r.daily?.let { d ->
                    Spacer(Modifier.height(8.dp))
                    d.time.indices.forEach { i ->
                        val hi = d.maxC.getOrNull(i)
                        val lo = d.minC.getOrNull(i)
                        val pp = d.precipProbPct.getOrNull(i)
                        LabelValue(
                            d.time[i],
                            listOfNotNull(
                                WmoCode.describe(d.weatherCode.getOrNull(i)),
                                if (hi != null && lo != null) "${fmt.temperature(lo)}–${fmt.temperature(hi)}" else null,
                                pp?.let { "${it.roundToInt()} % rain" },
                            ).joinToString(" · "),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Updated ${fmt.time(result.forecast.fetchedMs)}" + if (result.fromCache) " (cached)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

@Composable
private fun PlaceDialog(onDismiss: () -> Unit, onUseHere: () -> Unit, onPick: (SkyPlace) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a place") },
        text = {
            Column {
                Text("Paste coordinates or a map link (Google Maps, OpenStreetMap, Apple Maps, geo:). Nothing is looked up online.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it.take(2000); error = null }, label = { Text("Coordinates or link") }, isError = error != null, supportingText = error?.let { e -> { Text(e) } })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = CoordinateParser.parse(text)) {
                    is CoordinateParse.Found -> r.places.first().let { onPick(SkyPlace(it.position, it.label, true)) }
                    is CoordinateParse.Refused -> error = when (r.reason) {
                        RefusalReason.ShortLinkNeedsNetwork -> "Short links need a lookup online. Open the link and share the full URL instead."
                        RefusalReason.NoCoordinatesInLink -> "This link has no coordinates."
                    }
                    CoordinateParse.NotRecognized -> error = "Not recognised. Try 46.5582, 7.8352"
                }
            }) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onUseHere) { Text("Use my location") } },
    )
}
