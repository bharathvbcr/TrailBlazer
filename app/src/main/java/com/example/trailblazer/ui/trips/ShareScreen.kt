package com.example.trailblazer.ui.trips

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trailblazer.container
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.PlacePicker
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.SeedPlace
import com.example.trailblazer.ui.nav.TripEditRoute
import com.trailblazer.core.geo.CoordinateParse
import com.trailblazer.core.geo.CoordinateParser
import com.trailblazer.core.geo.Dms
import com.trailblazer.core.geo.ParsedPlace
import com.trailblazer.core.trip.TripRules

/**
 * What another app shared (Google Maps' Share → TrailBlazer): the place is worked out first — read on the phone, or
 * through the place picker when it is a short link or only a name — then added to a new trip or before an existing
 * trip's destination, in the trip editor, where it is saved like any other change.
 */
@Composable
fun ShareScreen(nav: Navigator, text: String) {
    val ctx = LocalContext.current
    val c = ctx.container
    val settings = c.prefs.settings.collectAsStateWithLifecycle(null).value ?: return
    val trips by c.trips.all.collectAsStateWithLifecycle(emptyList())
    val offline = remember(text) { CoordinateParser.parse(text) }
    var places by remember(text) { mutableStateOf((offline as? CoordinateParse.Found)?.places) }
    // Anything that is not already a position goes through the picker, which offers the lookup or the search.
    var picking by rememberSaveable(text) { mutableStateOf(places == null) }

    fun open(route: TripEditRoute) {
        nav.back()
        nav.go(route)
    }

    val seed = places.orEmpty().map { SeedPlace(it.position.lat, it.position.lon, it.label) }
    ScreenScaffold(title = "Add to a trip", onBack = { nav.back() }) {
        item { SectionTitle("Shared place") }
        item {
            GlassCard {
                val ps = places
                if (ps == null) {
                    Text("No position yet from what was shared.", style = MaterialTheme.typography.bodyMedium)
                    Text(text.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { picking = true }) { Text("Find it") }
                } else {
                    ps.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(TrailIcons.Pin, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.label ?: "Shared place", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(Dms.format(p.position, settings.coordinateFormat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    OutlinedButton(onClick = { picking = true }) { Text("Not this place?") }
                }
            }
        }
        if (places != null) {
            item { SectionTitle("Add it to") }
            item {
                Button(onClick = { open(TripEditRoute(null, seed)) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(TrailIcons.Route, null)
                    Spacer(Modifier.width(8.dp))
                    Text("A new trip")
                }
            }
            items(trips, key = { it.id }) { t ->
                val full = t.stops.size + seed.size > TripRules.MAX_STOPS
                GlassCard(onClick = if (full) null else ({ open(TripEditRoute(t.id, seed)) }), onClickLabel = "Add to ${t.name}") {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            full -> "Full: a trip holds ${TripRules.MAX_STOPS} stops"
                            t.stops.size >= 2 -> "${t.stops.size} stops · goes in before ${t.stops.last().name}"
                            else -> "${t.stops.size} stop" + if (t.stops.size == 1) "" else "s"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }

    if (picking) PlacePicker(
        title = "Find the shared place",
        onDismiss = { picking = false },
        onPick = { found: List<ParsedPlace> -> places = found; picking = false },
        initialText = if (places == null) text else "",
    )
}
