package com.example.trailblazer.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.scrubX
import com.trailblazer.core.plot.SeriesPlot
import com.trailblazer.core.trip.ElevationProfile
import com.trailblazer.core.trip.ElevationStats
import com.trailblazer.core.trip.Extremum
import com.trailblazer.core.trip.ExtremumKind
import com.trailblazer.core.trip.SteepSection
import kotlin.math.abs

/** What the trip page knows about the elevation of the route being edited. */
sealed interface TripElevation {
    /** Fewer than two stops: there is no route to profile yet. */
    data object NotEnoughStops : TripElevation

    data object Loading : TripElevation

    data class Ready(
        val profile: ElevationProfile,
        val stats: ElevationStats,
        val markers: List<Extremum>,
        val steep: List<SteepSection>,
    ) : TripElevation
}

/** Gradient above which a stretch of route is called steep, in percent. */
internal const val STEEP_WARNING_PERCENT = 25.0

/**
 * The Elevation card of the trip page: totals, the plot with peak and valley markers, and warnings; or a plain
 * explanation, with the way to fix it, when no elevation tile covers the route.
 */
@Composable
fun ElevationSection(state: TripElevation, fmt: Fmt, onImportTiles: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        TripElevation.NotEnoughStops -> Unit
        TripElevation.Loading -> GlassCard(modifier) { Text("Reading elevation…", style = MaterialTheme.typography.bodyMedium) }
        is TripElevation.Ready -> GlassCard(modifier) {
            val stats = state.stats
            if (!stats.hasData) {
                Text("No elevation data for this route — import HGT tiles", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Elevation comes from SRTM 1 or 3 arc-second tiles (.hgt) stored on this phone. Download the tiles that cover the route, unzip them, and import them. Nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Button(onClick = onImportTiles) { Text("Import HGT tiles") }
                return@GlassCard
            }
            LabelValue("Climb / descent", "↑${fmt.elevation(stats.gainM)} ↓${fmt.elevation(stats.lossM)}")
            LabelValue("Highest", fmt.elevation(stats.maxM ?: 0.0))
            LabelValue("Lowest", fmt.elevation(stats.minM ?: 0.0))
            Spacer(Modifier.height(8.dp))
            ElevationProfilePlot(state.profile, state.markers, fmt)
            if (stats.hasGaps) {
                val missing = ((stats.totalSamples - stats.knownSamples) * 100.0 / stats.totalSamples).toInt().coerceAtLeast(1)
                Text(
                    "No elevation data along about $missing% of the route (the gaps in the plot). Climb and descent count only the parts that have data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.steep.isNotEmpty()) {
                val steepest = state.steep.maxOf { abs(it.gradePercent) }
                val where = state.steep.take(3).joinToString(", ") { "${fmt.distance(it.startM)}–${fmt.distance(it.endM)}" }
                Text(
                    "Steep ground: ${state.steep.size} ${if (state.steep.size == 1) "stretch" else "stretches"} above ${STEEP_WARNING_PERCENT.toInt()}% (steepest about ${steepest.toInt()}%), at $where from the start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.profile.spacingWidened) {
                Text(
                    "This route is long, so elevation is sampled every ${fmt.distance(state.profile.spacingM)}; short peaks and dips can be missed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** What a screen reader says for the plot: the route length, the range, and the climb and descent. */
internal fun profileDescription(profile: ElevationProfile, stats: ElevationStats, markers: List<Extremum>, fmt: Fmt): String = buildString {
    append("Elevation profile over ${fmt.distance(profile.totalDistanceM)}. ")
    append("Lowest ${fmt.elevation(stats.minM ?: 0.0)}, highest ${fmt.elevation(stats.maxM ?: 0.0)}, ")
    append("climb ${fmt.elevation(stats.gainM)}, descent ${fmt.elevation(stats.lossM)}.")
    val peaks = markers.count { it.kind == ExtremumKind.Peak }
    val valleys = markers.count { it.kind == ExtremumKind.Valley }
    if (peaks + valleys > 0) append(" $peaks ${if (peaks == 1) "peak" else "peaks"} and $valleys ${if (valleys == 1) "valley" else "valleys"} marked.")
    if (stats.hasGaps) append(" Some of the route has no elevation data.")
}

/**
 * Elevation against distance along the route. Stretches with no data are left as shaded gaps, never drawn as 0 m.
 * Peaks and valleys are marked (the highest and lowest are labelled); tap or drag sideways to read a point.
 */
@Composable
fun ElevationProfilePlot(profile: ElevationProfile, markers: List<Extremum>, fmt: Fmt, modifier: Modifier = Modifier) {
    val samples = profile.samples
    val frame = remember(profile) { SeriesPlot.frame(samples.map { it.elevationM }, minSpan = 20.0) }
    val stats = remember(profile) { profile.stats() }
    if (frame == null) {
        Text("Too little elevation data to draw a profile.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val total = profile.totalDistanceM.coerceAtLeast(1.0)
    var scrub by remember(profile) { mutableStateOf<Int?>(null) }
    val line = MaterialTheme.colorScheme.primary
    val peakColor = MaterialTheme.colorScheme.tertiary
    val valleyColor = MaterialTheme.colorScheme.secondary
    val gapColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val description = remember(profile, markers, fmt.settings) { profileDescription(profile, stats, markers, fmt) }

    Column(modifier) {
        val shown = scrub?.let { samples[it] }
        Text(
            shown?.let { "${fmt.distance(it.distanceM)} · ${it.elevationM?.let(fmt::elevation) ?: "no data"}" } ?: "Tap or drag along the profile",
            style = MaterialTheme.typography.labelLarge,
            color = line,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = description }
                .scrubX(profile) { f ->
                    val target = f * total
                    scrub = samples.indices.minByOrNull { abs(samples[it].distanceM - target) }
                },
        ) {
            val top = 14.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            fun x(distanceM: Double) = (size.width * distanceM / total).toFloat()
            fun y(elevationM: Double) = bottom - (bottom - top) * SeriesPlot.yFraction(elevationM, frame.lo, frame.hi).toFloat()

            drawLine(grid, Offset(0f, bottom), Offset(size.width, bottom), 1f)
            // Gaps: from the last sample with data to the next one with data (or to the ends of the route).
            var i = 0
            while (i < samples.size) {
                if (samples[i].elevationM != null) {
                    i++
                    continue
                }
                var j = i
                while (j + 1 < samples.size && samples[j + 1].elevationM == null) j++
                val from = if (i == 0) 0.0 else samples[i - 1].distanceM
                val to = if (j == samples.size - 1) total else samples[j + 1].distanceM
                drawRect(gapColor, Offset(x(from), top), Size(x(to) - x(from), bottom - top))
                i = j + 1
            }
            // Runs of known samples.
            var start = 0
            while (start < samples.size) {
                if (samples[start].elevationM == null) {
                    start++
                    continue
                }
                var end = start
                while (end + 1 < samples.size && samples[end + 1].elevationM != null) end++
                if (end == start) {
                    drawCircle(line, 3.dp.toPx(), Offset(x(samples[start].distanceM), y(samples[start].elevationM!!)))
                } else {
                    val stroke = Path()
                    val fill = Path()
                    for (k in start..end) {
                        val p = Offset(x(samples[k].distanceM), y(samples[k].elevationM!!))
                        if (k == start) {
                            stroke.moveTo(p.x, p.y)
                            fill.moveTo(p.x, p.y)
                        } else {
                            stroke.lineTo(p.x, p.y)
                            fill.lineTo(p.x, p.y)
                        }
                    }
                    fill.lineTo(x(samples[end].distanceM), bottom)
                    fill.lineTo(x(samples[start].distanceM), bottom)
                    fill.close()
                    drawPath(fill, line.copy(alpha = 0.22f))
                    drawPath(stroke, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                start = end + 1
            }
            // Stops, as ticks on the baseline.
            for (d in profile.stopDistancesM) drawLine(line.copy(alpha = 0.6f), Offset(x(d), bottom - 6.dp.toPx()), Offset(x(d), bottom), 2.dp.toPx())
            // Peaks and valleys; the highest and lowest are labelled with their elevation.
            val highest = markers.filter { it.kind == ExtremumKind.Peak }.maxByOrNull { it.elevationM }
            val lowest = markers.filter { it.kind == ExtremumKind.Valley }.minByOrNull { it.elevationM }
            for (m in markers) {
                val c = Offset(x(samples[m.index].distanceM), y(m.elevationM))
                drawCircle(if (m.kind == ExtremumKind.Peak) peakColor else valleyColor, 5.dp.toPx(), c)
                if (m == highest || m == lowest) {
                    val text = measurer.measure(fmt.elevation(m.elevationM), labelStyle)
                    val tx = (c.x - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                    val ty = if (m.kind == ExtremumKind.Peak) (c.y - 8.dp.toPx() - text.size.height).coerceAtLeast(0f) else (c.y + 8.dp.toPx()).coerceAtMost(size.height - text.size.height)
                    drawText(text, topLeft = Offset(tx, ty))
                }
            }
            shown?.let { s ->
                val px = x(s.distanceM)
                drawLine(line.copy(alpha = 0.7f), Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
                s.elevationM?.let { drawCircle(line, 4.5.dp.toPx(), Offset(px, y(it))) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt.distance(0.0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmt.distance(profile.totalDistanceM), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
