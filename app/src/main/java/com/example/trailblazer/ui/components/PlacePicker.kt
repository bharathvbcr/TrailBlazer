package com.example.trailblazer.ui.components

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trailblazer.container
import com.example.trailblazer.places.FoundPlace
import com.example.trailblazer.places.SavedPlaces
import com.example.trailblazer.places.SearchResult
import com.trailblazer.core.sensors.Reading
import com.example.trailblazer.ui.Fmt
import com.trailblazer.core.geo.CoordinateParse
import com.trailblazer.core.geo.CoordinateParser
import com.trailblazer.core.geo.ParsedPlace
import kotlinx.coroutines.launch

/**
 * The "use where I am" choice, whose meaning belongs to the caller (add a stop here, or follow the live position).
 * [onHere] returns a message to show in the picker when it could not be done, or null when it was.
 */
data class HereOption(val label: String, val onHere: () -> String?)

/**
 * One place to find any place: type a name or address (searched online only after the user has turned search on),
 * paste coordinates or a map link (read on the phone), take the clipboard, use where you are, or pick one of your
 * waypoints or earlier trip stops, nearest first. [onPick] gets one place, or every stop of a directions link.
 */
@Composable
fun PlacePicker(
    title: String,
    onDismiss: () -> Unit,
    onPick: (List<ParsedPlace>) -> Unit,
    here: HereOption? = null,
    excludeTripId: String? = null,
    /** Text to start with, looked at straight away (what another app shared). */
    initialText: String = "",
) {
    val ctx = LocalContext.current
    val c = ctx.container
    val scope = rememberCoroutineScope()
    val settings = c.prefs.settings.collectAsStateWithLifecycle(null).value
    val fmt = remember(settings) { settings?.let { Fmt(ctx, it) } }
    val waypoints by c.waypoints.all.collectAsStateWithLifecycle(emptyList())
    val trips by c.trips.all.collectAsStateWithLifecycle(emptyList())
    val fix by c.location.fix.collectAsStateWithLifecycle()
    val near = (fix as? Reading.Value)?.value?.position

    var text by rememberSaveable { mutableStateOf(initialText.take(4000)) }
    var started by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var offerLookup by remember { mutableStateOf(false) }
    var askConsent by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Pair<String, List<FoundPlace>>?>(null) }
    // Whether the clipboard holds text, from its description only: the text itself is read when the chip is tapped.
    val clipboard = remember { ctx.getSystemService(ClipboardManager::class.java) }
    val clipHasText = remember { clipboard?.hasPrimaryClip() == true && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true }

    /** [justAgreed] covers the moment between tapping "Turn on search" and the setting arriving from storage. */
    fun search(q: String, justAgreed: Boolean = false) {
        results = null
        message = null
        searching = true
        scope.launch {
            when (val r = c.placeSearch.search(q, justAgreed || settings?.placeSearchConsent == true)) {
                is SearchResult.Ok -> {
                    results = q to r.places
                    if (r.places.isEmpty()) message = "Nothing found for “$q”."
                }
                SearchResult.NotConsented -> askConsent = q
                SearchResult.NotAvailable -> message = "Place search isn’t available on this phone. Paste coordinates or a map link instead."
                is SearchResult.Failed -> message = r.message
                is SearchResult.Refused -> message = r.message
            }
            searching = false
        }
    }

    fun take(r: CoordinateParse, resolvedUrl: String? = null) {
        offerLookup = false
        when (r) {
            is CoordinateParse.Found -> onPick(r.places)
            CoordinateParse.NotRecognized -> search(text)
            is CoordinateParse.Refused -> {
                // A link that only names a place (Google's "maps?q=Eiffel Tower&ftid=…") can still be found by name.
                val name = resolvedUrl?.let(CoordinateParser::placeQuery) ?: CoordinateParser.placeQuery(text.trim())
                if (name != null) {
                    message = "The link names “$name” but has no coordinates. Searching for it by name."
                    search(name)
                } else {
                    message = parseProblem(r)
                    offerLookup = needsLookup(r)
                }
            }
        }
    }

    fun submit() {
        if (text.isBlank()) return
        take(CoordinateParser.parse(text))
    }

    // Waits for settings, so a shared place name is not refused as "search off" before they have loaded.
    LaunchedEffect(settings != null) {
        if (settings != null && !started && text.isNotBlank()) {
            started = true
            submit()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(TrailIcons.Close, "Close") }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(4000); message = null; offerLookup = false; results = null },
                        placeholder = { Text("Place, address, coordinates or map link") },
                        leadingIcon = { Icon(TrailIcons.Search, null) },
                        trailingIcon = {
                            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else if (text.isNotBlank()) TextButton(onClick = ::submit) { Text("Go") }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        here?.let { h -> AssistChip(onClick = { h.onHere()?.let { message = it } }, label = { Text(h.label) }, leadingIcon = { Icon(TrailIcons.MyLocation, null, Modifier.size(18.dp)) }) }
                        if (clipHasText) AssistChip(
                            onClick = {
                                val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
                                if (clip.isNullOrEmpty()) message = "The clipboard is empty." else {
                                    text = clip.take(4000)
                                    take(CoordinateParser.parse(text))
                                }
                            },
                            label = { Text("Paste from clipboard") },
                            leadingIcon = { Icon(TrailIcons.Copy, null, Modifier.size(18.dp)) },
                        )
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
                    if (offerLookup) ShortLinkLookup(text, onParsed = { r, url -> take(r, url) }, onError = { message = it })
                    askConsent?.let { q ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Search online for “$q”?", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "TrailBlazer sends only the words to find to this phone’s place-search service (Android’s geocoder; on Pixel phones it is run by Google). " +
                                        "TrailBlazer does not send your location with them, and searches only when you ask: Go, Search, the clipboard chip, or a place shared to TrailBlazer. You can turn this off in Settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row {
                                    TextButton(onClick = {
                                        askConsent = null
                                        scope.launch { c.prefs.update { it.copy(placeSearchConsent = true) } }
                                        search(q, justAgreed = true)
                                    }) { Text("Turn on search") }
                                    TextButton(onClick = { askConsent = null; message = "Search is off. Paste coordinates or a map link, or pick a saved place." }) { Text("Not now") }
                                }
                            }
                        }
                    }
                    val shown = results
                    val saved = remember(waypoints, trips, near, text, shown) {
                        if (shown == null) SavedPlaces.list(waypoints, trips, near, filter = text.takeIf { CoordinateParser.parse(it) is CoordinateParse.NotRecognized } ?: "", excludeTripId = excludeTripId) else emptyList()
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        if (shown != null) {
                            item { Header("Results for “${shown.first}”") }
                            items(shown.second) { p -> PlaceRow(p, near?.let { com.trailblazer.core.geo.Geo.distanceM(it, p.position) }, fmt) { onPick(listOf(ParsedPlace(p.position, p.name))) } }
                        } else if (saved.isNotEmpty()) {
                            item { Header(if (near != null) "Your places, nearest first" else "Your places") }
                            items(saved) { e -> PlaceRow(e.place, e.distanceM, fmt) { onPick(listOf(ParsedPlace(e.place.position, e.place.name))) } }
                        } else if (text.isBlank()) {
                            item {
                                Text(
                                    "Search for a place by name, or paste what a maps app shares. Tip: in Google Maps, tap Share on a place and choose TrailBlazer.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun PlaceRow(p: FoundPlace, distanceM: Double?, fmt: Fmt?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Choose ${p.name}", onClick = onClick).padding(vertical = 10.dp),
    ) {
        Icon(TrailIcons.Pin, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(p.detail, distanceM?.let { d -> fmt?.distance(d)?.let { "$it away" } }).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(1.dp))
}
