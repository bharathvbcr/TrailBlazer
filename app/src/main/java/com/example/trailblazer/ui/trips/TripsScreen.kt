package com.example.trailblazer.ui.trips

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trailblazer.container
import com.example.trailblazer.data.GeoFormat
import com.example.trailblazer.data.TrackState
import com.example.trailblazer.data.Waypoint
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.tracking.InterruptReason
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.EmptyState
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.SettingsRoute
import com.example.trailblazer.ui.nav.TrackDetailRoute
import com.example.trailblazer.ui.nav.TripEditRoute
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.intents.MapLinks
import com.trailblazer.core.trip.LegCalculator

private enum class Segment(val label: String) { Trips("Trips"), Waypoints("Waypoints"), Tracks("Tracks") }

@Composable
fun TripsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val vm: TripsViewModel = viewModel { TripsViewModel(ctx.container) }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val trips by vm.trips.collectAsStateWithLifecycle()
    val waypoints by vm.waypoints.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val open by vm.openTrack.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val interrupted by vm.interrupted.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val granted by ctx.container.permissions.granted.collectAsStateWithLifecycle()
    var segment by rememberSaveable { mutableStateOf(Segment.Trips) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Waypoint?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var pendingFormat by rememberSaveable { mutableStateOf(GeoFormat.Gpx) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.exportWaypoints(it, pendingFormat) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ctx.container.permissions.refresh()
        if (ctx.container.permissions.isGranted(AppPermission.Location)) vm.startRecording()
    }

    /** Location is required; the notification permission (API 33+) is asked alongside but is optional. */
    fun startRecording() {
        val needs = buildList {
            if (AppPermission.Location !in granted) addAll(AppPermission.Location.manifest)
            if (AppPermission.Notifications !in granted) addAll(AppPermission.Notifications.manifest)
        }
        if (needs.isEmpty()) vm.startRecording() else permLauncher.launch(needs.toTypedArray())
    }

    ScreenScaffold(
        title = "Trips",
        actions = {
            IconButton(onClick = { menu = true }) { Icon(TrailIcons.Import, "Import or export") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Import GPX or KML…") }, onClick = {
                    menu = false
                    importer.launch(arrayOf("application/gpx+xml", "application/vnd.google-earth.kml+xml", "application/xml", "text/xml", "application/octet-stream"))
                })
                DropdownMenuItem(text = { Text("Export waypoints as GPX…") }, onClick = { menu = false; pendingFormat = GeoFormat.Gpx; exporter.launch("waypoints.gpx") })
                DropdownMenuItem(text = { Text("Export waypoints as KML…") }, onClick = { menu = false; pendingFormat = GeoFormat.Kml; exporter.launch("waypoints.kml") })
            }
            IconButton(onClick = { nav.go(SettingsRoute) }) { Icon(TrailIcons.Settings, "Settings") }
        },
    ) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Segment.entries.forEachIndexed { i, s ->
                    SegmentedButton(selected = segment == s, onClick = { segment = s }, shape = SegmentedButtonDefaults.itemShape(i, Segment.entries.size)) { Text(s.label) }
                }
            }
        }
        message?.let { m ->
            item { GlassCard(onClick = { vm.message.value = null }, onClickLabel = "Dismiss") { Text(m, style = MaterialTheme.typography.bodyMedium) } }
        }
        when (segment) {
            Segment.Trips -> {
                item {
                    Button(onClick = { nav.go(TripEditRoute(null)) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(TrailIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("New trip")
                    }
                }
                if (trips.isEmpty()) item { EmptyState("No trips yet", "Plan a route from A to B with night stops and places to visit. It opens in any map app — no map download needed.") }
                items(trips, key = { it.id }) { t ->
                    GlassCard(onClick = { nav.go(TripEditRoute(t.id)) }, onClickLabel = "Edit trip") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${t.stops.size} stops · ${fmt.distance(LegCalculator.totalM(t.stops))} straight-line",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { confirmDelete = "Delete trip “${t.name}”?" to { vm.deleteTrip(t.id); Unit } }) { Icon(TrailIcons.Delete, "Delete trip") }
                        }
                    }
                }
            }
            Segment.Waypoints -> {
                if (waypoints.isEmpty()) item { EmptyState("No waypoints", "Use “Mark waypoint” on the Now tab, or import a GPX/KML file.") }
                val here = (fix as? Reading.Value)?.value?.position
                items(waypoints, key = { it.id }) { w ->
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(w.name, style = MaterialTheme.typography.titleMedium)
                                Text(Dms.format(w.position, settings.coordinateFormat), style = MaterialTheme.typography.bodySmall)
                                if (here != null) {
                                    Text(
                                        "${fmt.distance(Geo.distanceM(here, w.position))} · ${fmt.bearing(Geo.initialBearing(here, w.position))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                w.elevationM?.let { Text(fmt.elevation(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { vm.navigateTo(w.id); nav.tab(com.example.trailblazer.ui.nav.NowRoute) }) { Text("Navigate") }
                            IconButton(onClick = {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, MapLinks.geo(w.position, w.name).toUri()).let { Intent.createChooser(it, "Open in map app") })
                            }) { Icon(TrailIcons.OpenExternal, "Open in map app") }
                            IconButton(onClick = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, MapLinks.shareText(w.name, w.position)), "Share"))
                            }) { Icon(TrailIcons.Share, "Share") }
                            IconButton(onClick = { rename = w }) { Icon(TrailIcons.Edit, "Rename") }
                            IconButton(onClick = { confirmDelete = "Delete waypoint “${w.name}”?" to { vm.deleteWaypoint(w.id); Unit } }) { Icon(TrailIcons.Delete, "Delete") }
                        }
                    }
                }
            }
            Segment.Tracks -> {
                item {
                    RecorderCard(
                        open = open,
                        recording = recording,
                        interrupted = interrupted,
                        fmt = fmt,
                        onStart = { startRecording() },
                        onPause = { vm.pauseRecording() },
                        onResume = { if (!vm.resumeRecording()) startRecording() },
                        onStop = { vm.stopRecording() },
                    )
                }
                val finished = tracks.filter { it.state == TrackState.Finished }
                if (finished.isEmpty()) item { EmptyState("No recorded tracks", "Recording keeps going with the screen off and survives the app being closed.") }
                items(finished, key = { it.id }) { t ->
                    GlassCard(onClick = { nav.go(TrackDetailRoute(t.id)) }, onClickLabel = "Open track") {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${fmt.date(t.startedMs)} · ${fmt.distance(t.distanceM)} · ↑${fmt.elevation(t.gainM)} · ${fmt.duration(t.movingMs)} moving",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    rename?.let { w ->
        var text by remember(w.id) { mutableStateOf(w.name) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename waypoint") },
            text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameWaypoint(w.id, text); rename = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { (question, action) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(question) },
            text = { Text("This cannot be undone.") },
            confirmButton = { TextButton(onClick = { action(); confirmDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecorderCard(
    open: com.example.trailblazer.data.TrackSummary?,
    recording: Boolean,
    interrupted: InterruptReason?,
    fmt: Fmt,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    GlassCard {
        val status = LocalStatusColors.current
        if (open == null) {
            Text("Record a track", style = MaterialTheme.typography.titleMedium)
            Text("Uses GPS in the background with a notification you can pause or stop from.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onStart) { Icon(TrailIcons.Record, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Start recording") }
            return@GlassCard
        }
        Text(open.name, style = MaterialTheme.typography.titleMedium)
        val live = recording && open.state == TrackState.Recording
        Text(
            when {
                live -> "Recording"
                open.state == TrackState.Recording -> "Interrupted — tap Resume"
                else -> "Paused"
            },
            color = if (live) status.danger else status.caution,
            style = MaterialTheme.typography.labelLarge,
        )
        interrupted?.let {
            Text(
                when (it) {
                    InterruptReason.LocationPermissionRevoked -> "Stopped because location permission was removed."
                    InterruptReason.LocationDisabled -> "Stopped because location was turned off."
                },
                style = MaterialTheme.typography.bodySmall,
                color = status.caution,
            )
        }
        Text("${fmt.distance(open.distanceM)} · ↑${fmt.elevation(open.gainM)} · ${open.pointCount} points", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) FilledTonalButton(onClick = onPause) { Icon(TrailIcons.Pause, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Pause") }
            else FilledTonalButton(onClick = onResume) { Icon(TrailIcons.Play, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Resume") }
            OutlinedButton(onClick = onStop) { Icon(TrailIcons.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Finish") }
        }
    }
}
