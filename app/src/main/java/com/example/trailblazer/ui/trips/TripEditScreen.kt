package com.example.trailblazer.ui.trips

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.trailblazer.ui.components.HereOption
import com.example.trailblazer.ui.components.PlacePicker
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.components.stopLetter
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.SeedPlace
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.geo.ParsedPlace
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.intents.MapLinks
import com.trailblazer.core.intents.TravelMode
import com.trailblazer.core.time.CivilDate
import com.trailblazer.core.trip.LegCalculator
import com.trailblazer.core.trip.StopKind
import com.trailblazer.core.trip.TripFiles
import com.trailblazer.core.trip.TripRules

@Composable
fun TripEditScreen(nav: Navigator, tripId: String?, seed: List<SeedPlace> = emptyList()) {
    val ctx = LocalContext.current
    // Keyed on the seed too: sharing a second place to the same trip must build a fresh editor that adds it.
    val vm: TripEditViewModel = viewModel(key = "trip-$tripId-${seed.hashCode()}") {
        TripEditViewModel(ctx.container, tripId, seed.mapNotNull { s -> LatLon.of(s.lat, s.lon)?.let { ParsedPlace(it, s.name) } })
    }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val name by vm.name.collectAsStateWithLifecycle()
    val stops by vm.stops.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val problems by vm.problems.collectAsStateWithLifecycle()
    val daylight by vm.daylight.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val removed by vm.lastRemoved.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
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
        actions = { TextButton(onClick = { vm.save { nav.back() } }, enabled = stops.size >= 2 && problems.isEmpty()) { Text("Save") } },
    ) {
        item {
            OutlinedTextField(name, vm::rename, label = { Text("Trip name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (stops.isNotEmpty()) {
            item {
                Text(
                    tripMeta(stops.size, stops.count { it.kind == StopKind.Night }, fmt.distance(LegCalculator.totalM(stops))) + if (stops.size >= 2) " in a straight line" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { GlassCard(padding = 8.dp) { RouteSketch(stops, { fmt.distance(it) }) } }
        }
        (message ?: info)?.let { m -> item { GlassCard(onClick = { vm.message.value = null; info = null }, onClickLabel = "Dismiss") { Text(m) } } }
        if (problems.isNotEmpty()) item {
            GlassCard {
                problems.forEach { p ->
                    Text(
                        when (p) {
                            TripRules.Problem.TooFewStops -> "Add at least two stops (A and B)."
                            TripRules.Problem.TooManyStops -> "A trip holds at most ${TripRules.MAX_STOPS} stops."
                            is TripRules.Problem.DuplicateAdjacent -> "Stops ${stopLetter(p.index - 1)} and ${stopLetter(p.index)} are the same place."
                            is TripRules.Problem.DatesOutOfOrder -> "Stop ${stopLetter(p.index)} is dated before an earlier stop."
                            is TripRules.Problem.DuplicateId -> "Stop ${stopLetter(p.index)} reuses another stop's id."
                        },
                        color = LocalStatusColors.current.caution,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item { SectionTitle("Stops") }
        removed?.let { r ->
            item(key = "undo") {
                GlassCard(padding = 8.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Removed “${r.stop.name}”", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        TextButton(onClick = { vm.undoRemove() }) { Text("Undo") }
                    }
                }
            }
        }
        if (stops.isEmpty()) {
            item {
                Text(
                    "Add where you start (A), then where you are going. Stops in between can be places to visit or nights.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val legs = LegCalculator.legs(stops)
        itemsIndexed(stops, key = { _, s -> s.id }) { i, s ->
            Column {
                legs.getOrNull(i - 1)?.let { leg -> LegConnector("${fmt.distance(leg.distanceM)} · ${fmt.bearing(leg.initialBearingDeg)}", stopAccent(s.kind)) }
                StopCard(i, stops.size, s, fmt, settings.coordinateFormat, ctx, vm)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (stops.size) {
                        0 -> "Add the start"
                        1 -> "Add the destination"
                        else -> "Add another stop (it becomes the new destination)"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddButton("Find a place", TrailIcons.Search, Modifier.weight(2f)) { picking = true }
                    AddButton("Here", TrailIcons.MyLocation, Modifier.weight(1f)) { if (!vm.addHere()) info = "No current position yet." }
                }
            }
        }
        if (stops.size >= 2) {
            item { SectionTitle("Open in maps") }
            item {
                GlassCard {
                    val points = stops.map { it.position }
                    if (problems.isNotEmpty()) {
                        Text("Fix the problems above before opening this route in a map app.", style = MaterialTheme.typography.bodyMedium, color = LocalStatusColors.current.caution)
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            fun open(links: List<String>) {
                                if (links.isEmpty()) info = "This route needs two different places."
                                else if (links.size == 1) openUrl(ctx, links[0]) { info = it } else linksOpen = links
                            }
                        OutlinedButton(onClick = { open(MapLinks.googleDirections(points, TravelMode.Driving)) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Drive") }
                        OutlinedButton(onClick = { open(MapLinks.googleDirections(points, TravelMode.Walking)) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Walk") }
                        OutlinedButton(onClick = { open(MapLinks.osmDirections(points, TravelMode.Driving)) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("OSM") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = {
                            val text = vm.trip().let { t ->
                                t.name + "\n" + t.stops.mapIndexed { i, s -> "${stopLetter(i)}. ${s.name}: ${MapLinks.osmView(s.position)}" }.joinToString("\n")
                            }
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share trip"))
                        }, modifier = Modifier.weight(1.2f).height(48.dp)) { Icon(TrailIcons.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                        OutlinedButton(onClick = { exportFormat = GeoFormat.Gpx; exporter.launch("${TripFiles.exportBase(name)}.gpx") }, modifier = Modifier.weight(0.9f).height(48.dp)) { Text("GPX") }
                        OutlinedButton(onClick = { exportFormat = GeoFormat.Kml; exporter.launch("${TripFiles.exportBase(name)}.kml") }, modifier = Modifier.weight(0.9f).height(48.dp)) { Text("KML") }
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

    if (picking) PlacePicker(
        title = when (stops.size) {
            0 -> "Add the start"
            1 -> "Add the destination"
            else -> "Add a stop"
        },
        onDismiss = { picking = false },
        onPick = { places -> vm.addPlaces(places); picking = false },
        here = HereOption("Where I am") { if (vm.addHere()) { picking = false; null } else "No position yet. Try again outside, or pick another way." },
        excludeTripId = vm.id,
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
        title = { Text(if (problems.isEmpty()) "Save changes?" else "This trip still has problems") },
        text = if (problems.isEmpty()) null else {
            { Text("Saving stays off until those are fixed. Keep editing, or discard these changes.") }
        },
        confirmButton = {
            if (problems.isEmpty()) TextButton(onClick = { discardAsk = false; vm.save { nav.back() } }) { Text("Save") }
            else TextButton(onClick = { discardAsk = false }) { Text("Keep editing") }
        },
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
    var renaming by remember { mutableStateOf(false) }
    val accent = stopAccent(s.kind)
    val middle = i != 0 && i != count - 1
    GlassCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(stopLetter(i), style = MaterialTheme.typography.titleMedium, color = accent, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Rename stop") { renaming = true }) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindLabel(s.kind), style = MaterialTheme.typography.labelLarge, color = accent)
                Text(Dms.format(s.position, format), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column {
                IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(TrailIcons.Up, "Move up") }
                IconButton(onClick = { vm.move(i, 1) }, enabled = i < count - 1) { Icon(TrailIcons.Down, "Move down") }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (middle) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.kind == StopKind.Visit, onClick = { vm.setKind(i, StopKind.Visit) }, label = { Text("Visit") })
                FilterChip(selected = s.kind == StopKind.Night, onClick = { vm.setKind(i, StopKind.Night) }, label = { Text("Night") })
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dateText = s.plannedDay?.let { fmt.date(LocalDays.window(it).first + 43_200_000L) }
            TextButton(onClick = {
                val today = s.plannedDay ?: LocalDays.today(System.currentTimeMillis())
                val (y, m, d) = CivilDate.civilFromDays(today)
                DatePickerDialog(ctx, { _, yy, mm, dd -> vm.setDay(i, CivilDate.daysFromCivil(yy, mm + 1, dd)) }, y, m - 1, d).show()
            }) { Text(dateText ?: "Add a date") }
            if (s.plannedDay != null) TextButton(onClick = { vm.setDay(i, null) }) { Text("Clear") }
            Spacer(Modifier.weight(1f))
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

/** Between two stop cards: a short rail in the next stop's colour, with the straight-line leg to it. */
@Composable
private fun LegConnector(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 30.dp, top = 2.dp, bottom = 2.dp).height(28.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.5f)))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(48.dp), shape = CircleShape, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun stopAccent(kind: StopKind): Color = when (kind) {
    StopKind.Start -> MaterialTheme.colorScheme.primary
    StopKind.End -> MaterialTheme.colorScheme.error
    StopKind.Night -> MaterialTheme.colorScheme.secondary
    StopKind.Visit -> MaterialTheme.colorScheme.tertiary
}

private fun kindLabel(k: StopKind) = when (k) {
    StopKind.Start -> "Start (A)"
    StopKind.End -> "Destination"
    StopKind.Night -> "Night stop"
    StopKind.Visit -> "Place to visit"
}
