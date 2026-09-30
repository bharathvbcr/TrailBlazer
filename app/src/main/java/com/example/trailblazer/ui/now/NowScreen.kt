package com.example.trailblazer.ui.now

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trailblazer.container
import com.example.trailblazer.data.NorthReference
import com.example.trailblazer.data.Settings
import com.example.trailblazer.location.AltitudeDatum
import com.example.trailblazer.location.Fix
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.sensors.Accuracy
import com.example.trailblazer.sensors.Heading
import com.example.trailblazer.sensors.HeadingSource
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.Dial
import com.example.trailblazer.ui.components.DialMarker
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.SatelliteSky
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.PermissionGate
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.components.ValueTile
import com.example.trailblazer.ui.label
import com.example.trailblazer.ui.nav.LevelRoute
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.SettingsRoute
import com.example.trailblazer.ui.nav.SightingRoute
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.atmo.Isa
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.plot.SkyBody
import com.trailblazer.core.plot.SkyPolar
import com.trailblazer.core.intents.MapLinks
import com.trailblazer.core.math.angleDiff
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun NowScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val vm: NowViewModel = viewModel { NowViewModel(ctx.container) }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val heading by vm.heading.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    val gnss by vm.gnss.collectAsStateWithLifecycle()
    val pressure by vm.pressure.collectAsStateWithLifecycle()
    val field by vm.magneticField.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val sky by vm.sky.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    var markOpen by rememberSaveable { mutableStateOf(false) }
    var calibrationOpen by rememberSaveable { mutableStateOf(false) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    // Only while the screen is started: the alert must never hold GPS on in the background (see NowViewModelTest).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.speedAlerts.collect { v ->
                vibrate(ctx)
                lastMessage = "Over speed limit: ${fmt.speed(v)}"
            }
        }
    }

    ScreenScaffold(
        title = "Now",
        actions = { IconButton(onClick = { nav.go(SettingsRoute) }) { Icon(TrailIcons.Settings, "Settings") } },
    ) {
        item {
            CompassCard(
                heading = heading,
                settings = settings,
                fmt = fmt,
                sky = sky,
                target = target,
                fix = fix,
                onToggleNorth = { vm.toggleNorth() },
                onCalibrate = { calibrationOpen = true },
                onSetLevelZero = { p, r -> vm.setLevelZero(p, r) },
                onResetLevelZero = { vm.resetLevelZero() },
                fieldAccuracy = (field as? Reading.Value)?.accuracy,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { markOpen = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = CircleShape,
                    enabled = fix is Reading.Value,
                ) {
                    Icon(TrailIcons.Pin, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Mark waypoint")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { nav.go(SightingRoute) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = CircleShape,
                    ) {
                        Icon(TrailIcons.Camera, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sight")
                    }
                    FilledTonalButton(
                        onClick = { nav.go(LevelRoute) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = CircleShape,
                    ) {
                        Icon(TrailIcons.Level, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Level")
                    }
                }
            }
        }
        lastMessage?.let { msg ->
            item {
                GlassCard(onClick = { lastMessage = null }, onClickLabel = "Dismiss") {
                    Text(msg, color = LocalStatusColors.current.danger, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        target?.let { t -> item { TargetCard(t.name, fix, t.position, heading, fmt, onClear = { vm.setTarget(null) }) } }
        item {
            PermissionGate(AppPermission.Location, "Location shows your position, speed and GPS altitude, gives true north, and lets you mark waypoints. It stays on this phone.") {
                PositionCard(fix, gnss, settings, fmt, ctx, onCompact = { vm.setCompactPosition(it) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ValueTile("GPS altitude", fix, "GPS", Modifier.weight(1f), TrailIcons.Mountain, caption = { v ->
                    val datum = if (v.value.altitudeDatum == AltitudeDatum.SeaLevel) "Above sea level" else "Above ellipsoid (not sea level)"
                    v.value.verticalAccuracyM?.let { "$datum · ±${fmt.elevation(it)}" } ?: datum
                }) { f -> f.altitudeM?.let { fmt.elevation(it) } ?: "Not reported" }
                val calib = settings.calibration
                ValueTile("Baro altitude", pressure, "barometer", Modifier.weight(1f), TrailIcons.Mountain, caption = {
                    if (calib != null) "Calibrated ${fmt.date(calib.setAtMs)}" else "Standard atmosphere, uncalibrated"
                }) { p -> Isa.altitudeM(p, calib?.qnhHpa ?: Isa.SEA_LEVEL_HPA)?.let { fmt.elevation(it) } ?: "Out of range" }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ValueTile("Speed", fix, "GPS", Modifier.weight(1f), TrailIcons.Speed, caption = {
                    if (settings.speedAlertEnabled) "Alert above ${fmt.speed(settings.speedAlertLimitMps)}" else null
                }) { f -> f.speedMps?.let { fmt.speed(it) } ?: "Not reported" }
                ValueTile("Pressure", pressure, "barometer", Modifier.weight(1f), TrailIcons.Waves) { fmt.pressure(it) }
            }
        }
        steps?.let { r ->
            item { ValueTile("Steps", r, "step counter", caption = { "Since counting was turned on" }) { "%,d".format(it) } }
        }
    }

    if (markOpen) MarkDialog(onDismiss = { markOpen = false }) { name ->
        vm.markWaypoint(name) { ok -> lastMessage = if (ok) "Saved “$name”" else "No current position to save" }
        markOpen = false
    }
    if (calibrationOpen) CalibrationDialog { calibrationOpen = false }
}

@Composable
private fun CompassCard(
    heading: Reading<Heading>,
    settings: Settings,
    fmt: Fmt,
    sky: SkyMarks?,
    target: com.example.trailblazer.data.Waypoint?,
    fix: Reading<Fix>,
    onToggleNorth: () -> Unit,
    onCalibrate: () -> Unit,
    onSetLevelZero: (Double, Double) -> Unit,
    onResetLevelZero: () -> Unit,
    fieldAccuracy: Accuracy?,
) {
    var zeroLevelDialogOpen by rememberSaveable { mutableStateOf(false) }
    val h = (heading as? Reading.Value)?.value

    GlassCard {
        val wantTrue = settings.north == NorthReference.True
        val shown: Double? = h?.let { if (wantTrue) it.trueDeg ?: it.magneticDeg else it.magneticDeg }
        val isTrue = wantTrue && h?.trueDeg != null
        // Markers are true bearings; shift them onto a magnetic card when showing magnetic north.
        val offset = if (isTrue) 0.0 else -(h?.declinationDeg ?: 0.0)
        val markers = buildList {
            val status = LocalStatusColors.current
            sky?.let {
                if (it.sunAltDeg > -1) add(DialMarker(it.sunAzDeg + offset, status.caution, "Sun"))
                if (it.moonAltDeg > -1) add(DialMarker(it.moonAzDeg + offset, MaterialTheme.colorScheme.secondary, "Moon"))
            }
            val f = (fix as? Reading.Value)?.value
            if (target != null && f != null) add(DialMarker(Geo.initialBearing(f.position, target.position) + offset, MaterialTheme.colorScheme.primary, target.name.take(10)))
        }
        val haptic = LocalHapticFeedback.current
        val isLevel = h != null && !h.upright && h.isLevel
        var wasLevel by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(isLevel) {
            if (isLevel && !wasLevel) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            wasLevel = isLevel
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Dial(
                headingDeg = shown,
                markers = markers,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.86f).padding(8.dp),
                dimmed = heading !is Reading.Value || heading.stale,
                pitchDeg = h?.calibratedPitchDeg,
                rollDeg = h?.calibratedRollDeg,
                accuracyDeg = h?.accuracyDeg,
                upright = h?.upright == true,
            )
        }
        Spacer(Modifier.height(8.dp))
        when (heading) {
            is Reading.Value -> {
                Text(
                    fmt.bearing(shown ?: 0.0),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Heading ${fmt.bearing(shown ?: 0.0)}" },
                )
                val sub = buildString {
                    append(if (isTrue) "True north" else "Magnetic north")
                    if (wantTrue && h?.trueDeg == null) append(" · true north needs a location fix")
                    h?.declinationDeg?.let { append(" · declination ${fmt.angle(abs(it))} ${if (it >= 0) "E" else "W"}") }
                    h?.accuracyDeg?.let { append(" · ±${fmt.angle(it, 0)}") }
                    if (h?.upright == true) {
                        append(" · camera direction")
                    } else if (h != null) {
                        if (h.isLevel) {
                            append(if (h.isCalibrated) " · Level (zero calibrated)" else " · Level (accurate)")
                        } else if (h.tiltDeg >= 3.0) {
                            append(" · Hold level for accuracy")
                        }
                    }
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Reading.Acquiring -> Text("Starting compass…", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            is Reading.Unavailable -> Text(heading.reason.label("compass"), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            AssistChip(
                onClick = onToggleNorth,
                label = { Text(if (wantTrue) "True N" else "Magnetic N") },
                leadingIcon = { Icon(TrailIcons.Compass, null, Modifier.size(16.dp)) },
            )
            if (h != null && !h.upright) {
                if (h.isLevel) {
                    AssistChip(
                        onClick = { zeroLevelDialogOpen = true },
                        label = { Text(if (h.isCalibrated) "Level · Zeroed" else "Level") },
                        leadingIcon = { Icon(TrailIcons.Check, null, Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = LocalStatusColors.current.good,
                            leadingIconContentColor = LocalStatusColors.current.good,
                        ),
                    )
                } else if (h.tiltDeg >= 2.0) {
                    AssistChip(
                        onClick = { zeroLevelDialogOpen = true },
                        label = { Text("Tilt ${fmt.angle(h.tiltDeg, 0)}") },
                        leadingIcon = { Icon(TrailIcons.Level, null, Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = LocalStatusColors.current.caution,
                            leadingIconContentColor = LocalStatusColors.current.caution,
                        ),
                    )
                }
            }
            if (h?.source == HeadingSource.GameRotation) {
                AssistChip(onClick = {}, label = { Text("Relative only: no magnetometer") })
            } else if (fieldAccuracy == Accuracy.Low || fieldAccuracy == Accuracy.Unreliable) {
                AssistChip(
                    onClick = onCalibrate,
                    label = { Text("Calibrate compass") },
                    leadingIcon = { Icon(TrailIcons.Warning, null, Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = LocalStatusColors.current.caution, leadingIconContentColor = LocalStatusColors.current.caution),
                )
            }
        }
    }

    if (zeroLevelDialogOpen) {
        val rawPitch = h?.pitchDeg ?: 0.0
        val rawRoll = h?.rollDeg ?: 0.0
        AlertDialog(
            onDismissRequest = { zeroLevelDialogOpen = false },
            title = { Text("Level Zero Calibration") },
            text = {
                Column {
                    Text("Place the device flat on a known level surface. Setting zero compensates for camera bars (like on Google Pixel devices) or uneven cases so the compass reaches peak leveling accuracy.")
                    Spacer(Modifier.height(10.dp))
                    Text("Current sensor tilt: Pitch ${fmt.angle(rawPitch)}, Roll ${fmt.angle(rawRoll)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (h?.isCalibrated == true) {
                        Spacer(Modifier.height(4.dp))
                        Text("Active offsets: Pitch ${fmt.angle(h.pitchOffsetDeg)}, Roll ${fmt.angle(h.rollOffsetDeg)}", style = MaterialTheme.typography.labelSmall, color = LocalStatusColors.current.good)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSetLevelZero(rawPitch, rawRoll)
                    zeroLevelDialogOpen = false
                }) {
                    Text("Set zero")
                }
            },
            dismissButton = {
                if (h?.isCalibrated == true) {
                    TextButton(onClick = {
                        onResetLevelZero()
                        zeroLevelDialogOpen = false
                    }) {
                        Text("Reset offset")
                    }
                } else {
                    TextButton(onClick = { zeroLevelDialogOpen = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }
}

@Composable
private fun TargetCard(name: String, fix: Reading<Fix>, dest: com.trailblazer.core.geo.LatLon, heading: Reading<Heading>, fmt: Fmt, onClear: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Navigating to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onClear) { Text("Stop") }
        }
        val f = (fix as? Reading.Value)?.value
        if (f == null) {
            Text("Waiting for your position…", style = MaterialTheme.typography.bodyMedium)
            return@GlassCard
        }
        val bearing = Geo.initialBearing(f.position, dest)
        val dist = Geo.distanceM(f.position, dest)
        val h = (heading as? Reading.Value)?.value
        val facing = h?.trueDeg
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (facing != null) {
                val rel = angleDiff(facing, bearing)
                Icon(TrailIcons.Navigate, "Direction to target", Modifier.size(56.dp).rotate(rel.toFloat()), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
            }
            Column {
                Text(fmt.distance(dist), style = MaterialTheme.typography.headlineMedium)
                Text("Bearing ${fmt.bearing(bearing)} true", style = MaterialTheme.typography.bodyMedium)
                if (facing != null) {
                    val rel = angleDiff(facing, bearing)
                    val turn = when {
                        abs(rel) < 5 -> "Straight ahead"
                        rel > 0 -> "Turn ${fmt.angle(rel, 0)} right"
                        else -> "Turn ${fmt.angle(-rel, 0)} left"
                    }
                    Text(turn, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("Arrow needs compass and position", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PositionCard(
    fix: Reading<Fix>,
    gnss: Reading<com.example.trailblazer.location.GnssSnapshot>,
    settings: Settings,
    fmt: Fmt,
    ctx: Context,
    onCompact: (Boolean) -> Unit,
) {
    val compact = settings.compactPosition
    val sats = (gnss as? Reading.Value)?.value
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(TrailIcons.MyLocation, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Position", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            val v = fix as? Reading.Value
            if (v != null) {
                IconButton(onClick = { copy(ctx, Dms.format(v.value.position, settings.coordinateFormat)) }) { Icon(TrailIcons.Copy, "Copy coordinates") }
                IconButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, MapLinks.shareText("My position", v.value.position)), "Share position"))
                }) { Icon(TrailIcons.Share, "Share position") }
            }
            IconButton(onClick = { onCompact(!compact) }) {
                Icon(if (compact) TrailIcons.Down else TrailIcons.Up, if (compact) "Show satellites and details" else "Show position on one line")
            }
        }
        if (compact) {
            val line = when (fix) {
                is Reading.Value -> listOfNotNull(
                    Dms.format(fix.value.position, settings.coordinateFormat),
                    fix.value.accuracyM?.let { "±${fmt.elevation(it)}" },
                    sats?.let { "${it.used}/${it.inView} sats" },
                    if (fix.stale) "last known" else null,
                ).joinToString(" · ")
                Reading.Acquiring -> "Searching for satellites…"
                is Reading.Unavailable -> fix.reason.label("location")
            }
            Text(line, style = MaterialTheme.typography.bodyLarge, color = if (fix is Reading.Value && fix.stale) LocalStatusColors.current.caution else MaterialTheme.colorScheme.onSurface)
            return@GlassCard
        }
        when (fix) {
            is Reading.Value -> {
                Text(Dms.format(fix.value.position, settings.coordinateFormat), style = MaterialTheme.typography.titleLarge)
                fix.value.accuracyM?.let { LabelValue("Accuracy", "±${fmt.elevation(it)}") }
                LabelValue("Fix time", DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(fix.value.timeMs)))
                if (fix.stale) Text("Last known position", style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.caution)
            }
            Reading.Acquiring -> Text("Searching for satellites…", style = MaterialTheme.typography.bodyLarge)
            is Reading.Unavailable -> Text(fix.reason.label("location"), style = MaterialTheme.typography.bodyLarge)
        }
        when (gnss) {
            is Reading.Value -> {
                val bodies = gnss.value.satellites.map {
                    SkyBody(
                        it.azimuthDeg,
                        it.elevationDeg,
                        it.usedInFix,
                        "${constellationName(it.constellation)} ${it.svid} · ${it.cn0DbHz.roundToInt()} dB",
                    )
                }
                if (SkyPolar.layout(bodies).isEmpty()) {
                    Text("No satellites above the horizon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${gnss.value.used} of ${gnss.value.inView} satellites used", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        SatelliteSky(bodies, Modifier.widthIn(max = 240.dp))
                    }
                }
            }
            is Reading.Unavailable -> Text(gnss.reason.label("GNSS status"), style = MaterialTheme.typography.bodyMedium)
            Reading.Acquiring -> Text("Waiting for satellites…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MarkDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val default = remember { "Waypoint " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) }
    var name by rememberSaveable { mutableStateOf(default) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark waypoint") },
        text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name.ifBlank { default }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CalibrationDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate the compass") },
        text = {
            Text(
                "Android reports low magnetometer accuracy. Move away from metal cases, magnets, car mounts, and electronics. " +
                    "Slowly trace a figure-8 in the air until recalibrated. Holding the phone level also ensures the most accurate direction.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

private fun constellationName(constellation: Int) = when (constellation) {
    1 -> "GPS"
    3 -> "GLONASS"
    5 -> "BeiDou"
    6 -> "Galileo"
    else -> "Sat"
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Coordinates", text))
}

private fun vibrate(ctx: Context) {
    val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    if (v?.hasVibrator() != true) return
    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
    else @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 300, 150, 300), -1)
}
