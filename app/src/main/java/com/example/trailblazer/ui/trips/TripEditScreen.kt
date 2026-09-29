package com.example.trailblazer.ui.trips

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.LocalDays
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.RouteSketch
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.components.stopLetter
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.geo.CoordinateParse
import com.trailblazer.core.geo.CoordinateParser
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.geo.RefusalReason
import com.trailblazer.core.intents.MapLinks
import com.trailblazer.core.intents.TravelMode
import com.trailblazer.core.time.CivilDate
import com.trailblazer.core.trip.LegCalculator
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.TripRules

@Composable
fun TripEditScreen(nav: Navigator, tripId: String?) {
    val ctx = LocalContext.current
    val vm: TripEditViewModel = viewModel(key = "trip-$tripId") { TripEditViewModel(ctx.container, tripId) }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val name by vm.name.collectAsStateWithLifecycle()
    val stops by vm.stops.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val problems by vm.problems.collectAsStateWithLifecycle()
    val daylight by vm.daylight.collectAsStateWithLifecycle()
    val waypoints by vm.waypoints.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var addMenu by remember { mutableStateOf(false) }
    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    var pickWaypoint by rememberSaveable { mutableStateOf(false) }
    var linksOpen by remember { mutableStateOf<List<String>?>(null) }
    var discardAsk by remember { mutableStateOf(false) }
    var exportFormat by rememberSaveable { mutableStateOf(GeoFormat.Gpx) }
    var info by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { vm.export(it, exportFormat) }
    }

    BackHandler(enabled = dirty) { discardAsk = true }

    ScreenScaffold(
        title = if (vm.isNew) "New trip" else name,
        onBack = { if (dirty) discardAsk = true else nav.back() },
        actions = { TextButton(onClick = { vm.save { nav.back() } }, enabled = stops.isNotEmpty()) { Text("Save") } },
    ) {
        item {
            OutlinedTextField(name, vm::rename, label = { Text("Trip name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (stops.isNotEmpty()) {
            item { GlassCard(padding = 8.dp) { RouteSketch(stops, { fmt.distance(it) }) } }
        }
        (message ?: info)?.let { m -> item { GlassCard(onClick = { vm.message.value = null; info = null }, onClickLabel = "Dismiss") { Text(m) } } }
        if (problems.isNotEmpty()) item {
            GlassCard {
                problems.forEach { p ->
                    Text(
                        when (p) {
                            TripRules.Problem.TooFewStops -> "Add at least two stops (A and B)."
                            is TripRules.Problem.DuplicateAdjacent -> "Stops ${stopLetter(p.index - 1)} and ${stopLetter(p.index)} are the same place."
                            is TripRules.Problem.DatesOutOfOrder -> "Stop ${stopLetter(p.index)} is dated before the stop before it."
                        },
                        color = LocalStatusColors.current.caution,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item { SectionTitle("Stops") }
        itemsIndexed(stops, key = { _, s -> s.id }) { i, s ->
            StopCard(i, stops.size, s, fmt, settings.coordinateFormat, ctx, vm)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { addMenu = true }) { Icon(TrailIcons.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add stop") }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(text = { Text("My current location") }, onClick = {
                        addMenu = false
                        if (!vm.addHere()) info = "No current position yet."
                    })
                    DropdownMenuItem(text = { Text("A saved waypoint…") }, onClick = { addMenu = false; pickWaypoint = true })
                    DropdownMenuItem(text = { Text("Coordinates or map link…") }, onClick = { addMenu = false; pasteOpen = true })
                }
            }
        }
        if (stops.size >= 2) {
            item { SectionTitle("Legs (straight line)") }
            item {
                GlassCard {
                    LegCalculator.legs(stops).forEachIndexed { i, leg ->
                        LabelValue("${stopLetter(i)} → ${stopLetter(i + 1)}", "${fmt.distance(leg.distanceM)} · ${fmt.bearing(leg.initialBearingDeg)}")
                    }
                    LabelValue("Total", fmt.distance(LegCalculator.totalM(stops)))
                    Text("Road distance is longer; open in a map app for driving or walking directions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionTitle("Open in maps") }
            item {
                GlassCard {
                    val points = stops.map { it.position }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fun open(links: List<String>) {
                            if (links.size == 1) openUrl(ctx, links[0]) { info = it } else linksOpen = links
                        }
                        OutlinedButton(onClick = { open(MapLinks.googleDirections(points, TravelMode.Driving)) }) { Text("Drive") }
                        OutlinedButton(onClick = { open(MapLinks.googleDirections(points, TravelMode.Walking)) }) { Text("Walk") }
                        OutlinedButton(onClick = { open(MapLinks.osmDirections(points, TravelMode.Driving)) }) { Text("OSM") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val text = vm.trip().let { t ->
                                t.name + "\n" + t.stops.mapIndexed { i, s -> "${stopLetter(i)}. ${s.name}: ${MapLinks.osmView(s.position)}" }.joinToString("\n")
                            }
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share trip"))
                        }) { Icon(TrailIcons.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                        OutlinedButton(onClick = { exportFormat = GeoFormat.Gpx; exporter.launch("${name.ifBlank { "trip" }}.gpx") }) { Text("GPX") }
                        OutlinedButton(onClick = { exportFormat = GeoFormat.Kml; exporter.launch("${name.ifBlank { "trip" }}.kml") }) { Text("KML") }
                    }
                }
            }
        }
        if (daylight.isNotEmpty()) {
            item { SectionTitle("Daylight at dated stops") }
            item {
                GlassCard {
                    daylight.forEach { row ->
                        val i = stops.indexOfFirst { it.id == row.stop.id }
                        val d = row.day
                        val rise = d.sunriseMs?.let { fmt.time(it) } ?: "—"
                        val set = d.sunsetMs?.let { fmt.time(it) } ?: "—"
                        LabelValue("${stopLetter(i)} ${fmt.date(d.windowStartMs + 43_200_000L)}", "↑ $rise  ↓ $set")
                    }
                    Text("Times are in this phone’s time zone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (pasteOpen) PasteDialog(onDismiss = { pasteOpen = false }) { places -> vm.addPlaces(places); pasteOpen = false }
    if (pickWaypoint) AlertDialog(
        onDismissRequest = { pickWaypoint = false },
        title = { Text("Add a waypoint") },
        text = {
            if (waypoints.isEmpty()) Text("No saved waypoints.")
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(waypoints, key = { it.id }) { w ->
                    TextButton(onClick = { vm.addWaypoint(w); pickWaypoint = false }, modifier = Modifier.fillMaxWidth()) { Text(w.name, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { pickWaypoint = false }) { Text("Close") } },
    )
    linksOpen?.let { links ->
        AlertDialog(
            onDismissRequest = { linksOpen = null },
            title = { Text("Route in ${links.size} parts") },
            text = {
                Column {
                    Text("Map links can carry only a few stops at a time, so the route is split. Open each part in order.", style = MaterialTheme.typography.bodySmall)
                    links.forEachIndexed { i, l -> TextButton(onClick = { openUrl(ctx, l) { info = it } }) { Text("Part ${i + 1}") } }
                }
            },
            confirmButton = { TextButton(onClick = { linksOpen = null }) { Text("Done") } },
        )
    }
    if (discardAsk) AlertDialog(
        onDismissRequest = { discardAsk = false },
        title = { Text("Save changes?") },
        confirmButton = { TextButton(onClick = { discardAsk = false; vm.save { nav.back() } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { discardAsk = false; nav.back() }) { Text("Discard") } },
    )
}

private fun openUrl(ctx: Context, url: String, onError: (String) -> Unit) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        onError("No app can open map links on this phone.")
    }
}

@Composable
private fun StopCard(i: Int, count: Int, s: com.trailblazer.core.trip.Stop, fmt: Fmt, format: com.trailblazer.core.geo.CoordinateFormat, ctx: Context, vm: TripEditViewModel) {
    var kindMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    GlassCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stopLetter(i), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                Text(Dms.format(s.position, format), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val dateText = s.plannedDay?.let { fmt.date(LocalDays.window(it).first + 43_200_000L) } ?: "No date"
                Text("${kindLabel(s.kind)} · $dateText", style = MaterialTheme.typography.bodySmall)
            }
            Column {
                IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(TrailIcons.Up, "Move up") }
                IconButton(onClick = { vm.move(i, 1) }, enabled = i < count - 1) { Icon(TrailIcons.Down, "Move down") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { kindMenu = true }, enabled = i != 0 && i != count - 1) { Text("Type") }
            DropdownMenu(kindMenu, { kindMenu = false }) {
                listOf(StopKind.Visit, StopKind.Night).forEach { k ->
                    DropdownMenuItem(text = { Text(kindLabel(k)) }, onClick = { vm.setKind(i, k); kindMenu = false })
                }
            }
            TextButton(onClick = {
                val today = s.plannedDay ?: LocalDays.today(System.currentTimeMillis())
                val (y, m, d) = CivilDate.civilFromDays(today)
                DatePickerDialog(ctx, { _, yy, mm, dd -> vm.setDay(i, CivilDate.daysFromCivil(yy, mm + 1, dd)) }, y, m - 1, d).show()
            }) { Text("Date") }
            if (s.plannedDay != null) TextButton(onClick = { vm.setDay(i, null) }) { Text("Clear date") }
            IconButton(onClick = { renaming = true }) { Icon(TrailIcons.Edit, "Rename stop") }
            IconButton(onClick = { vm.remove(i) }) { Icon(TrailIcons.Delete, "Remove stop") }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Stop name") },
            text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameStop(i, text.ifBlank { s.name }); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

private fun kindLabel(k: StopKind) = when (k) {
    StopKind.Start -> "Start (A)"
    StopKind.End -> "Destination"
    StopKind.Night -> "Night stop"
    StopKind.Visit -> "Place to visit"
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onAdd: (List<com.trailblazer.core.geo.ParsedPlace>) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add by coordinates or link") },
        text = {
            Column {
                Text("Paste coordinates (46.5582, 7.8352 or 46°33′N 7°50′E) or a Google Maps / OpenStreetMap / Apple Maps / geo: link. Directions links add every stop.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it.take(4000); error = null }, isError = error != null, supportingText = error?.let { e -> { Text(e) } })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = CoordinateParser.parse(text)) {
                    is CoordinateParse.Found -> onAdd(r.places)
                    is CoordinateParse.Refused -> error = if (r.reason == RefusalReason.ShortLinkNeedsNetwork) "Short links can’t be read offline. Open it and share the full link." else "No coordinates in this link."
                    CoordinateParse.NotRecognized -> error = "Not recognised."
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
