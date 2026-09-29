package com.example.trailblazer.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trailblazer.container
import com.example.trailblazer.data.AltimeterCalibration
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.sos.TorchController
import com.example.trailblazer.sos.WhistlePlayer
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.components.ValueTile
import com.example.trailblazer.ui.label
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.atmo.AirDensity
import com.trailblazer.core.atmo.BoilingPoint
import com.trailblazer.core.atmo.DensityAltitude
import com.trailblazer.core.atmo.Isa
import com.trailblazer.core.motion.Inclination
import com.trailblazer.core.sos.Morse
import com.trailblazer.core.units.Length
import com.trailblazer.core.units.UnitSystem
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

// ---------------------------------------------------------------- Level

@Composable
fun LevelScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val settings = ctx.container.prefs.settings.collectAsStateWithLifecycle(null).value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val gravity by ctx.container.motion.gravity.collectAsStateWithLifecycle()
    var vehicle by rememberSaveable { mutableStateOf(false) }
    var zeroPitch by rememberSaveable { mutableDoubleStateOf(0.0) }
    var zeroRoll by rememberSaveable { mutableDoubleStateOf(0.0) }
    KeepScreenOn()

    val raw = (gravity as? Reading.Value)?.value?.let { Inclination.fromGravity(it, flat = !vehicle) }
    val inc = raw?.let { it - Inclination(zeroPitch, zeroRoll) }

    ScreenScaffold(title = "Level & tilt", onBack = { nav.back() }) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!vehicle, { vehicle = false; zeroPitch = 0.0; zeroRoll = 0.0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Flat (bubble)") }
                SegmentedButton(vehicle, { vehicle = true; zeroPitch = 0.0; zeroRoll = 0.0 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Upright (vehicle)") }
            }
        }
        item {
            GlassCard {
                when (gravity) {
                    is Reading.Unavailable -> Text((gravity as Reading.Unavailable).reason.label("accelerometer"))
                    Reading.Acquiring -> Text("Starting…")
                    is Reading.Value -> if (inc == null) Text("Hold still — no stable gravity reading") else {
                        if (!vehicle) Bubble(inc) else VehicleGauge(inc)
                        Spacer(Modifier.height(12.dp))
                        val level = abs(inc.pitchDeg) < 0.5 && abs(inc.rollDeg) < 0.5
                        Text(
                            if (level) "Level" else "Pitch ${fmt.angle(inc.pitchDeg)} · Roll ${fmt.angle(inc.rollDeg)}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (level) LocalStatusColors.current.good else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (vehicle) {
                            LabelValue("Road gradient", "${fmt.num(inc.gradePercent, 1)} %")
                            LabelValue("Side tilt", fmt.angle(inc.rollDeg))
                            val warn = abs(inc.rollDeg) > 20 || abs(inc.pitchDeg) > 25
                            if (warn) Text("Steep: check your vehicle’s rated limits.", color = LocalStatusColors.current.danger)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { raw?.let { zeroPitch = it.pitchDeg; zeroRoll = it.rollDeg } }, enabled = raw != null) { Text("Set zero") }
                    OutlinedButton(onClick = { zeroPitch = 0.0; zeroRoll = 0.0 }) { Text("Reset") }
                }
                Text(
                    if (vehicle) "Mount the phone upright facing forward, park on level ground and tap Set zero so the mount’s angle is ignored."
                    else "Lay the phone on its back. Tap Set zero on a known-level surface to correct for a bumpy case.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Bubble(inc: Inclination) {
    val cs = MaterialTheme.colorScheme
    val good = LocalStatusColors.current.good
    Canvas(Modifier.fillMaxWidth(0.8f).aspectRatio(1f).semantics { contentDescription = "Bubble level" }) {
        val r = min(size.width, size.height) / 2
        val c = center
        drawCircle(cs.surfaceContainerHighest, r, c)
        drawCircle(cs.outline, r, c, style = Stroke(2f))
        drawCircle(cs.outline, r * 0.33f, c, style = Stroke(2f))
        drawLine(cs.outlineVariant, Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(cs.outlineVariant, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        // 10° of tilt moves the bubble to the rim; the bubble floats uphill.
        val dx = (-inc.rollDeg / 10.0).coerceIn(-1.0, 1.0).toFloat() * (r * 0.85f)
        val dy = (inc.pitchDeg / 10.0).coerceIn(-1.0, 1.0).toFloat() * (r * 0.85f)
        val level = abs(inc.pitchDeg) < 0.5 && abs(inc.rollDeg) < 0.5
        drawCircle(if (level) good else cs.primary, r * 0.13f, Offset(c.x + dx, c.y + dy))
    }
}

@Composable
private fun VehicleGauge(inc: Inclination) {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "Vehicle tilt" }) {
        val c = center
        val w = size.width * 0.35f
        // Horizon line rotated by roll; offset by pitch.
        val rollRad = Math.toRadians(inc.rollDeg)
        val dy = (inc.pitchDeg / 30.0 * size.height / 2).toFloat()
        val dx = (w * kotlin.math.cos(rollRad)).toFloat()
        val ddy = (w * kotlin.math.sin(rollRad)).toFloat()
        drawLine(cs.primary, Offset(c.x - dx, c.y + dy + ddy), Offset(c.x + dx, c.y + dy - ddy), 6f)
        drawLine(cs.outline, Offset(c.x - w, c.y), Offset(c.x + w, c.y), 2f)
        drawCircle(cs.onSurface, 6f, c)
    }
}

// ---------------------------------------------------------------- Altimeter

@Composable
fun AltimeterScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val c = ctx.container
    val settings = c.prefs.settings.collectAsStateWithLifecycle(null).value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val pressure by c.barometer.pressureHpa.collectAsStateWithLifecycle()
    val ambient by c.environment.temperatureC.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var elevationText by rememberSaveable { mutableStateOf("") }
    var qnhText by rememberSaveable { mutableStateOf("") }
    var oatText by rememberSaveable { mutableStateOf("") }
    val calib = settings.calibration
    val p = (pressure as? Reading.Value)?.value
    val metric = settings.units == UnitSystem.Metric
    val oat: Double? = oatText.replace(',', '.').toDoubleOrNull()?.let { settings.temperatureUnit.toC(it) } ?: (ambient as? Reading.Value)?.value

    ScreenScaffold(title = "Altimeter", onBack = { nav.back() }) {
        item {
            ValueTile("Barometric altitude", pressure, "barometer", caption = {
                if (calib != null) "QNH ${fmt.pressure(calib.qnhHpa)}, set ${fmt.dateTime(calib.setAtMs)}" else "Standard atmosphere (QNH 1013.25 hPa) — calibrate for accuracy"
            }) { v -> Isa.altitudeM(v, calib?.qnhHpa ?: Isa.SEA_LEVEL_HPA)?.let { fmt.elevation(it) } ?: "Out of range" }
        }
        if (calib != null && c.clock.nowMs() - calib.setAtMs > 6 * 3_600_000L) item {
            Text("Calibration is over 6 hours old. Weather moves pressure, and 1 hPa is about 8 m of altitude.", color = LocalStatusColors.current.caution, style = MaterialTheme.typography.bodySmall)
        }
        item { SectionTitle("Calibrate") }
        item {
            GlassCard {
                Text("Enter your current elevation from a map or trail sign, or the local QNH from a weather report.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    elevationText, { elevationText = it.take(8) },
                    label = { Text("Known elevation (${if (metric) "m" else "ft"})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Button(enabled = p != null && elevationText.replace(',', '.').toDoubleOrNull() != null, onClick = {
                    val e = elevationText.replace(',', '.').toDouble().let { if (metric) it else it * Length.M_PER_FT }
                    Isa.qnhHpa(p!!, e)?.let { q -> scope.launch { c.prefs.update { it.copy(calibration = AltimeterCalibration(q, c.clock.nowMs())) } } }
                }) { Text("Set from elevation") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    qnhText, { qnhText = it.take(8) },
                    label = { Text("QNH (${settings.pressureUnit.symbol})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = qnhText.replace(',', '.').toDoubleOrNull()?.let { settings.pressureUnit.toHpa(it) in 900.0..1100.0 } == true, onClick = {
                        val q = settings.pressureUnit.toHpa(qnhText.replace(',', '.').toDouble())
                        scope.launch { c.prefs.update { it.copy(calibration = AltimeterCalibration(q, c.clock.nowMs())) } }
                    }) { Text("Set QNH") }
                    TextButton(onClick = { scope.launch { c.prefs.update { it.copy(calibration = null) } } }, enabled = calib != null) { Text("Reset") }
                }
            }
        }
        item { SectionTitle("From station pressure") }
        item {
            GlassCard {
                if (p == null) {
                    Text(when (val r = pressure) { is Reading.Unavailable -> r.reason.label("barometer"); else -> "Waiting for barometer…" })
                } else {
                    LabelValue("Station pressure", fmt.pressure(p))
                    Isa.pressureAltitudeM(p)?.let { LabelValue("Pressure altitude", fmt.elevation(it)) }
                    BoilingPoint.celsius(p)?.let { LabelValue("Water boils at", fmt.num(settings.temperatureUnit.fromC(it), 1) + settings.temperatureUnit.symbol) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        oatText, { oatText = it.take(6) },
                        label = { Text("Outside air temperature (${settings.temperatureUnit.symbol})") }, singleLine = true,
                        supportingText = { Text(if ((ambient as? Reading.Value) != null && oatText.isBlank()) "Using the phone’s temperature sensor" else "Needed for density altitude and air density") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    LabelValue("Density altitude", DensityAltitude.meters(p, oat)?.let { fmt.elevation(it) } ?: "Enter temperature")
                    LabelValue("Air density", AirDensity.kgPerM3(p, oat)?.let { "${fmt.num(it, 3)} kg/m³" } ?: "Enter temperature")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- SOS

private enum class SosMode { Torch, Whistle, Screen }

@Composable
fun SosScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val torch = remember { TorchController(ctx) }
    val whistle = remember { WhistlePlayer() }
    var active by rememberSaveable { mutableStateOf<SosMode?>(null) }
    var lit by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    KeepScreenOn()
    // Everything stops as soon as the app leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { active = null }

    LaunchedEffect(active) {
        lit = false
        when (active) {
            SosMode.Torch -> if (!torch.play(Morse.SOS) { lit = it }) { failed = "The torch is unavailable (no flash, or the camera is in use)."; active = null }
            SosMode.Whistle -> whistle.play(Morse.whistleTimeline()) { lit = it }
            SosMode.Screen -> torchlessScreen(Morse.SOS) { lit = it }
            null -> Unit
        }
    }

    ScreenScaffold(title = "SOS", onBack = { nav.back() }) {
        item {
            GlassCard {
                Text("SOS ${Morse.code("SOS")}", style = MaterialTheme.typography.headlineSmall)
                Text("Three short, three long, three short — repeated. Three whistle blasts is the international distress call on land.", style = MaterialTheme.typography.bodySmall)
            }
        }
        failed?.let { f -> item { Text(f, color = LocalStatusColors.current.caution) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in SosMode.entries) {
                    val on = active == m
                    Button(
                        onClick = { failed = null; active = if (on) null else m },
                        enabled = m != SosMode.Torch || torch.cameraId != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (on) "Stop" else m.name) }
                }
            }
        }
        if (torch.cameraId == null) item { Text("No torch on this phone.", style = MaterialTheme.typography.bodySmall) }
        item {
            Box(
                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (active == SosMode.Screen && lit) Color.White else if (lit) LocalStatusColors.current.danger else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .semantics { contentDescription = if (lit) "Signal on" else "Signal off" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (active) { null -> "Choose a signal"; SosMode.Screen -> ""; else -> if (lit) "ON" else "" },
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        item {
            Text(
                "Screen signal flashes at most about 2.5 times a second. If you are sensitive to flashing light, use the torch or whistle instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Screen-only morse (for phones without a torch): drives [onPulse] on the same timeline, until cancelled. */
private suspend fun torchlessScreen(timeline: List<com.trailblazer.core.sos.Pulse>, onPulse: (Boolean) -> Unit) {
    try {
        while (true) for (p in timeline) {
            onPulse(p.on)
            kotlinx.coroutines.delay(p.durationMs)
        }
    } finally {
        onPulse(false)
    }
}
