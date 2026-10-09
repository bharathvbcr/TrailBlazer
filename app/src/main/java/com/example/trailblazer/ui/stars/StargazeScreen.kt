package com.example.trailblazer.ui.stars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trailblazer.container
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.PermissionGate
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.components.pickPoint
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.astro.BodyNight
import com.trailblazer.core.astro.NightPlan
import com.trailblazer.core.astro.PolarAlignment
import com.trailblazer.core.astro.SkyProjection
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.geo.LatLon
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dark adaptation takes 20–30 minutes; 20 is the point where most of the gain is in. */
private const val ADAPT_MS = 20 * 60_000L

@Composable
fun StargazeScreen(nav: Navigator, lat: Double?, lon: Double?, label: String?) {
    val ctx = LocalContext.current
    val routePlace = if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) StarPlace(LatLon(lat, lon), label, true) else null
    val vm: StargazeViewModel = viewModel { StargazeViewModel(ctx.container, routePlace) }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val place by vm.place.collectAsStateWithLifecycle()
    val tonight by vm.tonight.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val facing by vm.facing.collectAsStateWithLifecycle()
    val polar by vm.polar.collectAsStateWithLifecycle()
    val offset by vm.chartOffsetMin.collectAsStateWithLifecycle()
    val follow by vm.followCompass.collectAsStateWithLifecycle()
    var picked by rememberSaveable { mutableStateOf<String?>(null) }

    ScreenScaffold(title = "Stargazing", onBack = { nav.back() }) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Night vision (red)", style = MaterialTheme.typography.titleMedium)
                        Text("Keeps your eyes dark-adapted. Turn the screen brightness down too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.nightRed, onCheckedChange = { vm.setNightRed(it) })
                }
            }
        }
        val p = place
        if (p == null) {
            item {
                PermissionGate(AppPermission.Location, "Stargazing needs your position to work out what is above you. Allow location, or open it from the Sky tab with a chosen place.") {
                    GlassCard(Modifier.fillMaxWidth()) { Text("Waiting for a position fix…", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            return@ScreenScaffold
        }
        item {
            Text(
                (p.label ?: Dms.format(p.position, settings.coordinateFormat)) + when {
                    p.fromRoute -> ""
                    p.lastKnown -> " (last known; waiting for GPS)"
                    else -> " (here)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val t = tonight
        item { TonightCard(t?.plan, fmt, chart?.timeMs) }
        item { SectionTitle("Sky chart") }
        item {
            GlassCard(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val c = chart
                if (c == null) {
                    Text("Working out the sky…")
                } else {
                    val effectiveFacing = if (follow) (facing ?: SkyProjection.defaultFacing(p.position.lat)) else SkyProjection.defaultFacing(p.position.lat)
                    SkyChart(c, effectiveFacing, picked, { picked = it }, Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(8.dp))
                    PickedLine(c, picked, fmt)
                    Text(
                        if (offset == 0) "Now, ${fmt.time(c.timeMs)}" else "In ${fmt.duration(offset * 60_000L)}, ${fmt.time(c.timeMs)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Slider(
                        value = offset.toFloat(),
                        onValueChange = { vm.chartOffsetMin.value = (it / 15).roundToInt() * 15 },
                        valueRange = 0f..720f,
                        steps = 47,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Time ahead" },
                    )
                    if (c.sunAltitudeDeg > -6) {
                        Text("The Sun is up or it is still twilight: only the brightest objects will show.", style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.caution, textAlign = TextAlign.Center)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = follow,
                            onClick = { vm.followCompass.value = !follow },
                            enabled = vm.compassAvailable,
                            label = { Text(if (vm.compassAvailable) "Follow compass" else "No compass") },
                        )
                    }
                    Text(
                        if (follow && facing == null) "Waiting for the compass and a position fix (true north needs both)…"
                        else "Hold it overhead, facing the bottom edge. Named dots are the brightest stars and the planets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (t != null) {
            item { SectionTitle("Planets tonight") }
            item { PlanetsCard(t, fmt, chart?.timeMs) }
            item { SectionTitle("Milky Way core") }
            item { GalacticCard(t, fmt, chart?.timeMs) }
            if (t.showers.isNotEmpty()) {
                item { SectionTitle("Meteor showers") }
                item { ShowersCard(t, fmt) }
            }
        }
        polar?.let { pa -> item { SectionTitle("Polar alignment") }; item { PolarCard(pa, fmt) } }
        item { SectionTitle("Dark adaptation") }
        item { DarkAdaptationCard() }
    }
}

@Composable
private fun TonightCard(plan: NightPlan?, fmt: Fmt, nowMs: Long?) {
    GlassCard {
        if (plan == null) {
            Text("Working out tonight’s darkness…", style = MaterialTheme.typography.bodyMedium)
            return@GlassCard
        }
        val status = LocalStatusColors.current
        val moonless = plan.moonlessDarkMs
        val (headline, color) = when {
            plan.astronomicalDark.isEmpty() && plan.nauticalDark.isEmpty() -> "Twilight all night" to status.caution
            plan.astronomicalDark.isEmpty() -> "Never fully dark" to status.caution
            moonless >= 3 * 3_600_000L -> "${fmt.duration(moonless)} of moon-free dark" to status.good
            moonless > 0 -> "${fmt.duration(moonless)} of moon-free dark" to MaterialTheme.colorScheme.onSurface
            else -> "Moonlit all night" to status.caution
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Tonight", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(headline, style = MaterialTheme.typography.headlineSmall, color = color)
            }
            MoonBadge(plan.moonIllumination)
        }
        Spacer(Modifier.height(10.dp))
        NightBar(plan, nowMs, fmt)
    }
}

/** The Moon's lit fraction as a filled ring, since moonlight is what decides a dark-sky night. */
@Composable
private fun MoonBadge(illumination: Double) {
    val cs = MaterialTheme.colorScheme
    val pct = (illumination.coerceIn(0.0, 1.0) * 100).roundToInt()
    Box(Modifier.size(56.dp).semantics { contentDescription = "Moon $pct percent lit" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(56.dp)) {
            val w = 5.dp.toPx()
            val tl = Offset(w / 2, w / 2)
            val sz = Size(size.width - w, size.height - w)
            drawArc(cs.onSurface.copy(alpha = 0.12f), 0f, 360f, false, tl, sz, style = Stroke(w))
            drawArc(cs.primary, -90f, 3.6f * pct, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$pct%", style = MaterialTheme.typography.titleSmall)
            Text("Moon", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun SkyChart(state: ChartState, facingDeg: Double, picked: String?, onPick: (String?) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val night = status.isNight
    val sky = if (night) Color.Black else Color(0xFF0A1024)
    val grid = if (night) cs.outline else Color(0xFF3A4670)
    val starColor = if (night) cs.primary else Color(0xFFF4F1E8)
    val planetColor = if (night) cs.tertiary else Color(0xFFFFC56B)
    val moonColor = if (night) cs.primary else Color(0xFFE9E4D0)
    val labelColor = if (night) cs.primary else Color(0xFFD8DEF0)
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 10.sp, color = labelColor)
    val cardinal = TextStyle(fontSize = 13.sp, color = if (night) cs.primary else Color(0xFFFF8A80))
    val visible = state.objects.count { it.altitudeDeg >= 0 && it.kind != ChartKind.GalacticCentre }
    val ring = if (night) cs.primary else Color(0xFF7FB4FF)
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val cardinalLayouts = remember(measurer, cardinal) {
        listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W").map { (az, name) ->
            az to measurer.measure(name, cardinal)
        }
    }
    val objectLayouts = remember(state.objects, measurer, small) {
        state.objects.mapNotNull { o ->
            o.label?.let { text -> o to measurer.measure(text, small) }
        }.toMap()
    }
    Canvas(
        modifier.semantics { contentDescription = "Sky chart, $visible objects above the horizon. Tap an object to name it." }
            .onSizeChanged { sizePx = it }
            // Tap names an object; press and hold, then slide, to sweep the pick across the sky without scrolling the page.
            .pickPoint(state, facingDeg) { tap ->
                val r = sizePx.width.coerceAtMost(sizePx.height) / 2f * 0.92f
                if (r <= 0f) return@pickPoint
                val ux = (tap.x - sizePx.width / 2f) / r
                val uy = (tap.y - sizePx.height / 2f) / r
                // A finger covers ~7 % of a phone-sized chart; that is the pick radius.
                onPick(identify(state.objects, facingDeg, ux.toDouble(), uy.toDouble(), maxDistance = 0.09)?.name)
            },
    ) {
        val r = size.minDimension / 2 * 0.92f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(sky, r, c)
        for (alt in listOf(30.0, 60.0)) drawCircle(grid, (r * (90 - alt) / 90).toFloat(), c, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
        drawCircle(grid, r, c, style = Stroke(1.5f))
        fun at(az: Double, alt: Double): Offset? = SkyProjection.project(az, alt, facingDeg)?.let { Offset(c.x + (it.x * r).toFloat(), c.y + (it.y * r).toFloat()) }
        for ((az, l) in cardinalLayouts) {
            val rel = Math.toRadians(az - facingDeg)
            val q = Offset(c.x + (sin(rel) * (r + 1)).toFloat(), c.y + (cos(rel) * (r + 1)).toFloat())
            val out = Offset(c.x + (sin(rel) * (r + l.size.height * 0.75f)).toFloat(), c.y + (cos(rel) * (r + l.size.height * 0.75f)).toFloat())
            drawCircle(grid, 2f, q)
            drawText(l, topLeft = Offset(out.x - l.size.width / 2f, out.y - l.size.height / 2f))
        }
        for (o in state.objects) {
            val p = at(o.azimuthDeg, o.altitudeDeg) ?: continue
            when (o.kind) {
                ChartKind.Star -> drawCircle(starColor, (2.8f - 0.5f * o.magnitude.toFloat()).coerceIn(0.7f, 3.6f).dp.toPx(), p)
                ChartKind.Planet -> drawCircle(planetColor, 3.2.dp.toPx(), p)
                ChartKind.Moon -> drawCircle(moonColor, 6.dp.toPx(), p)
                ChartKind.GalacticCentre -> drawCircle(grid, 5.dp.toPx(), p, style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
            }
            if (o.name == picked) drawCircle(ring, 11.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            objectLayouts[o]?.let { l ->
                // The core sits among Scorpius and Sagittarius; label it underneath so it does not cover their stars.
                val topLeft = if (o.kind == ChartKind.GalacticCentre) Offset(p.x - l.size.width / 2f, p.y + 6.dp.toPx())
                else Offset(p.x + 5.dp.toPx(), p.y - l.size.height / 2f)
                drawText(l, topLeft = topLeft)
            }
        }
    }
}

@Composable
private fun PlanetsCard(t: Tonight, fmt: Fmt, nowMs: Long?) {
    GlassCard {
        val (up, down) = t.planets.partition { it.night.bestAltitudeDeg != null }
        if (up.isEmpty()) {
            Text("No planets are up in the dark tonight.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val shown = up.sortedBy { it.night.bestTimeMs }
            val series = shown.mapIndexed { i, pt -> PlotSeries(pt.planet.label, planetColor(pt.planet, i), pt.track) }
            NightPlot(t.plan, series, nowMs, fmt)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                shown.forEachIndexed { i, pt ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        // Dot size follows brightness: magnitude −4 is big, +8 is a speck.
                        val dot = (11 - pt.magnitude).coerceIn(3.0, 14.0).dp
                        val color = series[i].color
                        Canvas(Modifier.size(14.dp)) { drawCircle(color, dot.toPx() / 2) }
                        Text(
                            pt.planet.label + " " + fmt.num(pt.magnitude, 1) + if (!pt.planet.nakedEye) " · binoculars" else "",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        if (down.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Not up in the dark: " + down.joinToString { p -> p.planet.label + if (p.elongationDeg < 15) " (by the Sun)" else "" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GalacticCard(t: Tonight, fmt: Fmt, nowMs: Long?) {
    GlassCard {
        val n = t.galacticCentre
        val best = n.bestAltitudeDeg
        NightPlot(t.plan, listOf(PlotSeries("Core", galacticColor(), t.galacticTrack)), nowMs, fmt, height = 120.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                best == null -> "Below the horizon while it is dark."
                best < 15 -> "Low: needs a clear, flat horizon."
                else -> "Best ${fmt.time(n.bestTimeMs!!)}, ${best.roundToInt()}° up, with no Moon."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ShowersCard(t: Tonight, fmt: Fmt) {
    val scale = t.showers.maxOf { (it.outlook.shower.zhr ?: 10).toDouble() }.coerceAtLeast(10.0)
    val color = if (LocalStatusColors.current.isNight) MaterialTheme.colorScheme.primary else Color(0xFF7FB4FF)
    GlassCard {
        for (s in t.showers) {
            val o = s.outlook
            val rate = s.bestRatePerHour
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(o.shower.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    rate?.let { "~${it.roundToInt()}/h" } ?: "–",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { contentDescription = rate?.let { "about ${it.roundToInt()} an hour from here" } ?: "radiant too low from here" },
                )
            }
            Spacer(Modifier.height(4.dp))
            RateBar(((rate ?: 0.0) / scale).toFloat(), ((o.shower.zhr ?: 0) / scale).toFloat(), color)
            Spacer(Modifier.height(2.dp))
            Text(
                (if (o.activeNow) "Active · " else "") + "peak ${fmt.date(o.peakMs)} · Moon ${(o.moonIllumination * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }
        Text("Faint bar: peak rate under a perfect sky. Data: IMO.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PolarCard(pa: PolarAlignment, fmt: Fmt) {
    val cs = MaterialTheme.colorScheme
    GlassCard(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val clock = pa.clockHour
        val h = clock.toInt().let { if (it == 0) 12 else it }
        val m = ((clock - clock.toInt()) * 60).roundToInt().let { if (it == 60) 0 else it }
        Text("${pa.starLabel} at ${h}:${"%02d".format(m)} on the clock", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = "Pole star position around the celestial pole" }) {
                val r = size.minDimension / 2 * 0.9f
                val c = center
                drawCircle(cs.outline, r, c, style = Stroke(1.5f))
                for (i in 0 until 12) {
                    val a = Math.toRadians(i * 30.0)
                    val o = Offset(c.x + (sin(a) * r).toFloat(), c.y - (cos(a) * r).toFloat())
                    val inner = Offset(c.x + (sin(a) * r * 0.88f).toFloat(), c.y - (cos(a) * r * 0.88f).toFloat())
                    drawLine(cs.outline, inner, o, 2f)
                }
                drawLine(cs.outlineVariant, Offset(c.x - 8f, c.y), Offset(c.x + 8f, c.y), 2f)
                drawLine(cs.outlineVariant, Offset(c.x, c.y - 8f), Offset(c.x, c.y + 8f), 2f)
                val a = Math.toRadians(pa.angleFromUpDeg)
                drawCircle(cs.primary, 7.dp.toPx(), Offset(c.x + (sin(a) * r * 0.7f).toFloat(), c.y - (cos(a) * r * 0.7f).toFloat()))
            }
        }
        LabelValue("Hour angle", "${fmt.num(pa.hourAngleHours, 2)} h")
        LabelValue("Distance from the pole", fmt.angle(pa.poleDistanceDeg, 2))
        LabelValue("Pole altitude", fmt.angle(pa.poleAltitudeDeg, 1))
        Text(
            "Facing the pole, zenith up, as the naked eye or a right-way-up finder shows it. Most polar scopes turn the view " +
                "upside down: there the star sits 6 hours round the clock from this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** What a tap on the chart found: name, height, direction and brightness, following the time slider. */
@Composable
private fun PickedLine(c: ChartState, picked: String?, fmt: Fmt) {
    val o = picked?.let { name -> c.objects.firstOrNull { it.name == name } }
    val text = when {
        picked == null -> "Tap a dot to name it"
        o == null || o.altitudeDeg < 0 -> "$picked is below the horizon"
        else -> listOfNotNull(
            o.name,
            "${o.altitudeDeg.roundToInt()}° up",
            fmt.bearing(o.azimuthDeg),
            o.magnitude.takeIf { it < 50 && o.kind != ChartKind.Moon }?.let { "mag ${fmt.num(it, 1)}" },
        ).joinToString(" · ")
    }
    Text(
        text,
        style = if (o != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
        color = if (o != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DarkAdaptationCard() {
    var startedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            now = System.currentTimeMillis()
            if (now - (startedAt ?: now) >= ADAPT_MS) break
            delay(1_000)
        }
    }
    GlassCard(horizontalAlignment = Alignment.CenterHorizontally) {
        val s = startedAt
        val elapsed = if (s == null) 0L else (now - s).coerceIn(0L, ADAPT_MS)
        val done = s != null && elapsed >= ADAPT_MS
        AdaptRing(
            progress = elapsed.toFloat() / ADAPT_MS,
            done = done,
            centre = when {
                s == null -> "20 min"
                done -> "Ready"
                else -> "${(ADAPT_MS - elapsed) / 60_000 + 1} min"
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (done) "Dark-adapted: look for faint stars" else "Any white light restarts the wait.",
            style = MaterialTheme.typography.bodySmall,
            color = if (done) LocalStatusColors.current.good else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        if (s == null) {
            FilledTonalButton(onClick = { startedAt = System.currentTimeMillis(); now = startedAt!! }) { Text("Start timer") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { startedAt = System.currentTimeMillis(); now = startedAt!! }) { Text("Restart") }
                OutlinedButton(onClick = { startedAt = null }) { Text("Stop") }
            }
        }
    }
}
