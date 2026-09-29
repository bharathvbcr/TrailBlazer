package com.example.trailblazer.ui.trips

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.trailblazer.AppContainer
import com.example.trailblazer.container
import com.example.trailblazer.data.GeoFormat
import com.example.trailblazer.data.IoResult
import com.example.trailblazer.data.Settings
import com.example.trailblazer.data.TrackSummary
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.components.Sparkline
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.nav.Navigator
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.geo.LatLon
import com.trailblazer.core.math.DEG
import com.trailblazer.core.track.DouglasPeucker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos

/** Chart series derived from a track, each with at most [TrackDetailViewModel.BUCKETS] points. */
data class TrackCharts(val elevationByDistance: List<Double>, val speedByTime: List<Double>, val path: List<LatLon>)

class TrackDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)
    val settings: StateFlow<Settings?> = c.prefs.settings.stateIn(viewModelScope, started, null)
    val track: StateFlow<TrackSummary?> = c.tracks.observe(id).stateIn(viewModelScope, started, null)
    val charts = MutableStateFlow<TrackCharts?>(null)
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { charts.value = withContext(Dispatchers.Default) { build() } }
    }

    /** Streams the points once, averaging into fixed buckets so memory stays flat for any track length. */
    private suspend fun build(): TrackCharts {
        val t = c.db.tracks().get(id) ?: return TrackCharts(emptyList(), emptyList(), emptyList())
        val totalDist = t.distanceM.coerceAtLeast(1.0)
        val totalTime = ((t.endedMs ?: c.clock.nowMs()) - t.startedMs).coerceAtLeast(1L)
        val eleSum = DoubleArray(BUCKETS)
        val eleN = IntArray(BUCKETS)
        val spdSum = DoubleArray(BUCKETS)
        val spdN = IntArray(BUCKETS)
        val path = ArrayList<LatLon>()
        var prev: LatLon? = null
        var dist = 0.0
        var index = 0
        val stride = t.pointCount / MAX_PATH_POINTS + 1
        c.tracks.forEachPoint(id) { p ->
            prev?.let { dist += Geo.distanceM(it, p.position) }
            prev = p.position
            if (index++ % stride == 0) path += p.position
            p.elevationM?.let { e ->
                val b = ((dist / totalDist) * (BUCKETS - 1)).toInt().coerceIn(0, BUCKETS - 1)
                eleSum[b] += e; eleN[b]++
            }
            p.speedMps?.let { v ->
                val b = (((p.epochMs - t.startedMs).toDouble() / totalTime) * (BUCKETS - 1)).toInt().coerceIn(0, BUCKETS - 1)
                spdSum[b] += v; spdN[b]++
            }
        }
        val ele = (0 until BUCKETS).filter { eleN[it] > 0 }.map { eleSum[it] / eleN[it] }
        val spd = (0 until BUCKETS).filter { spdN[it] > 0 }.map { spdSum[it] / spdN[it] }
        prev?.let { if (path.lastOrNull() != it) path += it }
        return TrackCharts(ele, spd, DouglasPeucker.simplifyToMax(path, 500) { it })
    }

    fun rename(name: String) = viewModelScope.launch { c.tracks.rename(id, name) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { c.tracks.delete(id); onDone() }

    fun export(uri: Uri, format: GeoFormat) = viewModelScope.launch {
        message.value = when (val r = c.importExport.exportTrack(id, uri, format)) {
            is IoResult.Ok -> "Exported ${r.value} points"
            is IoResult.Failed -> r.message
        }
    }

    companion object {
        const val BUCKETS = 240
        /** Path points sampled (every n-th point) for the sketch before simplification, bounding memory for long tracks. */
        const val MAX_PATH_POINTS = 20_000
    }
}

@Composable
fun TrackDetailScreen(nav: Navigator, trackId: String) {
    val ctx = LocalContext.current
    val vm: TrackDetailViewModel = viewModel(key = "track-$trackId") { TrackDetailViewModel(ctx.container, trackId) }
    val settings = vm.settings.collectAsStateWithLifecycle().value ?: return
    val fmt = remember(settings) { Fmt(ctx, settings) }
    val t by vm.track.collectAsStateWithLifecycle()
    val charts by vm.charts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var format by rememberSaveable { mutableStateOf(GeoFormat.Gpx) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { vm.export(it, format) } }
    val track = t

    ScreenScaffold(
        title = track?.name ?: "Track",
        onBack = { nav.back() },
        actions = {
            IconButton(onClick = { renaming = true }) { Icon(TrailIcons.Edit, "Rename") }
            IconButton(onClick = { deleting = true }) { Icon(TrailIcons.Delete, "Delete") }
        },
    ) {
        if (track == null) {
            item { Text("Track not found.") }
            return@ScreenScaffold
        }
        message?.let { m -> item { GlassCard(onClick = { vm.message.value = null }) { Text(m) } } }
        item {
            GlassCard {
                LabelValue("Date", fmt.dateTime(track.startedMs))
                LabelValue("Distance", fmt.distance(track.distanceM))
                LabelValue("Moving time", fmt.duration(track.movingMs))
                track.endedMs?.let { LabelValue("Total time", fmt.duration(it - track.startedMs)) }
                if (track.movingMs > 0) LabelValue("Average moving speed", fmt.speed(track.distanceM / (track.movingMs / 1000.0)))
                track.maxSpeedMps?.let { LabelValue("Max speed", fmt.speed(it)) }
                LabelValue("Climb / descent", "↑${fmt.elevation(track.gainM)} ↓${fmt.elevation(track.lossM)}")
                LabelValue("Points", track.pointCount.toString())
            }
        }
        val ch = charts
        if (ch == null) item { Text("Preparing charts…") }
        else {
            if (ch.path.size >= 2) item { GlassCard(padding = 8.dp) { TrackPath(ch.path) } }
            if (ch.elevationByDistance.size >= 2) {
                item { SectionTitle("Elevation over distance") }
                item {
                    GlassCard {
                        Sparkline(ch.elevationByDistance, minSpan = 20.0)
                        Text("${fmt.elevation(ch.elevationByDistance.min())} – ${fmt.elevation(ch.elevationByDistance.max())}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (ch.speedByTime.size >= 2) {
                item { SectionTitle("Speed over time") }
                item {
                    GlassCard {
                        Sparkline(ch.speedByTime, minSpan = 1.0, color = MaterialTheme.colorScheme.secondary)
                        Text("Up to ${fmt.speed(ch.speedByTime.max())} (averaged)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { format = GeoFormat.Gpx; exporter.launch("${track.name}.gpx") }) { Text("Export GPX") }
                OutlinedButton(onClick = { format = GeoFormat.Kml; exporter.launch("${track.name}.kml") }) { Text("Export KML") }
            }
        }
    }

    if (renaming && track != null) {
        var text by remember { mutableStateOf(track.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename track") },
            text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete this track?") },
        text = { Text("This cannot be undone. Export it first if you want a copy.") },
        confirmButton = { TextButton(onClick = { deleting = false; vm.delete { nav.back() } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}

/** The track's shape on a north-up local plane (no map tiles). */
@Composable
private fun TrackPath(path: List<LatLon>) {
    val cs = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.4f).semantics { contentDescription = "Track shape" }) {
        val lat0 = path.map { it.lat }.average()
        val kx = cos(lat0 * DEG)
        val xs = path.map { it.lon * kx }
        val ys = path.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val span = maxOf(maxX - minX, maxY - minY, 1e-6)
        val pad = 16.dp.toPx()
        val s = (minOf(size.width, size.height) - 2 * pad) / span
        fun o(i: Int) = Offset(
            (size.width / 2 + (xs[i] - (minX + maxX) / 2) * s).toFloat(),
            (size.height / 2 - (ys[i] - (minY + maxY) / 2) * s).toFloat(),
        )
        val p = Path().apply { moveTo(o(0).x, o(0).y); for (i in 1 until path.size) lineTo(o(i).x, o(i).y) }
        drawPath(p, cs.primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(cs.primary, 6.dp.toPx(), o(0))
        drawCircle(cs.error, 6.dp.toPx(), o(path.size - 1))
    }
}
