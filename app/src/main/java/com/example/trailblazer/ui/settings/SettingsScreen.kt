package com.example.trailblazer.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.example.trailblazer.container
import com.example.trailblazer.data.NorthReference
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.data.TrackingMode
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.nav.DocsRoute
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.weather.OpenMeteoClient
import com.trailblazer.core.geo.CoordinateFormat
import com.trailblazer.core.units.PressureUnit
import com.trailblazer.core.units.SpeedUnit
import com.trailblazer.core.units.TemperatureUnit
import com.trailblazer.core.units.UnitSystem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val c = ctx.container
    val s = c.prefs.settings.collectAsStateWithLifecycle(null).value ?: return
    val fmt = remember(s) { Fmt(ctx, s) }
    val scope = rememberCoroutineScope()
    var wipe by rememberSaveable { mutableStateOf(false) }
    fun set(f: (Settings) -> Settings) = scope.launch { c.prefs.update(f) }
    val stepsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        c.permissions.refresh()
        if (c.permissions.isGranted(AppPermission.ActivityRecognition)) set { it.copy(stepsEnabled = true, stepsBaseline = null) }
    }

    ScreenScaffold(title = "Settings", onBack = { nav.back() }) {
        item { SectionTitle("Units") }
        item {
            GlassCard {
                Choice("Distance & elevation", s.units, UnitSystem.entries, { if (it == UnitSystem.Metric) "Metric (m, km)" else "Imperial (ft, mi)" }) { v -> set { it.copy(units = v) } }
                Choice("Speed", s.speedUnit, SpeedUnit.entries, { it.symbol }) { v -> set { it.copy(speedUnit = v) } }
                Choice("Pressure", s.pressureUnit, PressureUnit.entries, { it.symbol }) { v -> set { it.copy(pressureUnit = v) } }
                Choice("Temperature", s.temperatureUnit, TemperatureUnit.entries, { it.symbol }) { v -> set { it.copy(temperatureUnit = v) } }
                Choice("Coordinates", s.coordinateFormat, CoordinateFormat.entries, {
                    when (it) { CoordinateFormat.Decimal -> "Decimal degrees"; CoordinateFormat.DegreesMinutes -> "Degrees, minutes"; CoordinateFormat.DegreesMinutesSeconds -> "Degrees, minutes, seconds" }
                }) { v -> set { it.copy(coordinateFormat = v) } }
                Choice("Compass north", s.north, NorthReference.entries, { if (it == NorthReference.True) "True north" else "Magnetic north" }) { v -> set { it.copy(north = v) } }
                if (s.levelPitchOffsetDeg != 0.0 || s.levelRollOffsetDeg != 0.0) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Level zero calibration", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Pitch ${fmt.angle(s.levelPitchOffsetDeg)} · Roll ${fmt.angle(s.levelRollOffsetDeg)} (camera visor / case)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { set { it.copy(levelPitchOffsetDeg = 0.0, levelRollOffsetDeg = 0.0) } }) {
                            Text("Reset")
                        }
                    }
                }
            }
        }
        item { SectionTitle("Appearance") }
        item {
            GlassCard {
                Choice("Theme", s.theme, ThemeMode.entries, { it.name }) { v -> set { it.copy(theme = v) } }
                Toggle("Colours from wallpaper", "Android 12 and later", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
                Toggle("Night vision (red)", "Keeps your eyes dark-adapted at camp", s.nightRed) { v -> set { it.copy(nightRed = v) } }
            }
        }
        item { SectionTitle("Tracking") }
        item {
            GlassCard {
                Choice("GPS fix interval", s.trackingMode, TrackingMode.entries, {
                    when (it) {
                        TrackingMode.Continuous -> "Continuous (1 s)"
                        TrackingMode.Balanced -> "Balanced (15 s)"
                        TrackingMode.Expedition -> "Expedition (60 s)"
                        TrackingMode.ExpeditionLong -> "Expedition (5 min)"
                    }
                }) { v -> set { it.copy(trackingMode = v) } }
                Text(
                    if (c.tracking.canRestGps) {
                        "Longer intervals save battery on multi-day trips. Balanced and Expedition also turn GPS off after 5 minutes lying still, and on again when you move. Applies to a recording in progress."
                    } else {
                        "Longer intervals save battery on multi-day trips. Applies to a recording in progress. This phone has no motion sensor that can wake it, so GPS stays on while you are still."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionTitle("Alerts") }
        item {
            GlassCard {
                Toggle("Speed alert", "Vibrates once when you go over the limit, again after slowing down", s.speedAlertEnabled) { v -> set { it.copy(speedAlertEnabled = v) } }
                if (s.speedAlertEnabled) {
                    val unit = s.speedUnit
                    val value = unit.fromMps(s.speedAlertLimitMps)
                    val max = unit.fromMps(200 / 3.6)
                    Text("Limit: ${fmt.speed(s.speedAlertLimitMps)}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = value.toFloat().coerceIn(1f, max.toFloat()),
                        onValueChange = { nv -> set { it.copy(speedAlertLimitMps = unit.toMps(nv.roundToInt().toDouble())) } },
                        valueRange = 1f..max.toFloat(),
                    )
                }
                Toggle("Step counter", "Uses the phone’s step sensor; asks for activity permission", s.stepsEnabled) { v ->
                    if (!v) set { it.copy(stepsEnabled = false, stepsBaseline = null) }
                    else if (c.permissions.isGranted(AppPermission.ActivityRecognition)) set { it.copy(stepsEnabled = true, stepsBaseline = null) }
                    else stepsPermission.launch(AppPermission.ActivityRecognition.manifest)
                }
            }
        }
        item { SectionTitle("Privacy") }
        item {
            GlassCard {
                Toggle("Online forecast", "Sends the viewed place, rounded to ~1 km, to Open-Meteo. Off by default.", s.forecastConsent) { v ->
                    set { it.copy(forecastConsent = v) }
                    if (!v) c.weather.clearCache()
                }
                Toggle("Place search", "Sends only the words you search for, when you ask, to this phone's place-search service (on Pixel, Google); never your location. Off by default.", s.placeSearchConsent) { v ->
                    set { it.copy(placeSearchConsent = v) }
                }
                Text(
                    "TrailBlazer has no account, analytics or ads. Location, tracks and waypoints stay on this phone and are excluded from cloud backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { wipe = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete all data…", color = MaterialTheme.colorScheme.error) }
            }
        }
        item { SectionTitle("About") }
        item {
            GlassCard {
                TextButton(onClick = { nav.go(DocsRoute(null)) }, modifier = Modifier.fillMaxWidth()) { Text("Developer docs") }
                Text(OpenMeteoClient.ATTRIBUTION + ", when the forecast is on.", style = MaterialTheme.typography.bodySmall)
                Text("Sun and moon: NOAA / Meeus algorithms, checked against US Naval Observatory data.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (wipe) AlertDialog(
        onDismissRequest = { wipe = false },
        title = { Text("Delete all data?") },
        text = { Text("Removes every waypoint, trip, track, pressure reading and setting from this phone. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                wipe = false
                scope.launch {
                    c.db.withTransaction {
                        c.db.tracks().deleteAll(); c.db.trips().deleteAll(); c.db.waypoints().deleteAll(); c.db.pressure().deleteAll()
                    }
                    c.prefs.clear()
                    c.weather.clearCache()
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { wipe = false }) { Text("Cancel") } },
    )
}

@Composable
private fun <T> Choice(label: String, value: T, options: List<T>, name: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        AssistChip(
            onClick = { open = true },
            label = { Text(name(value)) },
            trailingIcon = { Icon(TrailIcons.Chevron, null, Modifier.size(14.dp).rotate(90f)) },
        )
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
private fun Toggle(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChange(it)
            },
        )
    }
}
