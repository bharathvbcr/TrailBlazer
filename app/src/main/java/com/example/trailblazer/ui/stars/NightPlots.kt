package com.example.trailblazer.ui.stars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.scrubX
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.astro.Interval
import com.trailblazer.core.astro.NightPlan
import com.trailblazer.core.astro.Planet
import com.trailblazer.core.plot.SeriesPlot
import kotlin.math.roundToInt

/** One body's altitude across the night, drawn in [color]. */
data class PlotSeries(val name: String, val color: Color, val track: AltitudeTrack)

/** The colours of the darkness bands, shared by the night bar and the altitude plot so they read as one scale. */
private data class NightPalette(val day: Color, val twilight: Color, val dark: Color, val moonFree: Color, val ink: Color, val grid: Color)

@Composable
private fun nightPalette(): NightPalette {
    val cs = MaterialTheme.colorScheme
    return if (LocalStatusColors.current.isNight) {
        NightPalette(cs.primary.copy(alpha = 0.45f), cs.primary.copy(alpha = 0.28f), cs.primary.copy(alpha = 0.14f), Color.Black, cs.primary, cs.primary.copy(alpha = 0.35f))
    } else {
        NightPalette(Color(0xFF8FB8E8), Color(0xFF3E5A92), Color(0xFF1B2448), Color(0xFF070A18), Color(0xFFD8DEF0), Color(0xFF3A4670))
    }
}

/** Planet colours close to how each looks through the eye; in night-red every series is a shade of the one red. */
@Composable
fun planetColor(planet: Planet, index: Int): Color {
    if (LocalStatusColors.current.isNight) return MaterialTheme.colorScheme.primary.copy(alpha = listOf(1f, 0.8f, 0.62f)[index % 3])
    return when (planet) {
        Planet.Mercury -> Color(0xFFC9C3B8)
        Planet.Venus -> Color(0xFFFFF1B0)
        Planet.Mars -> Color(0xFFFF7A59)
        Planet.Jupiter -> Color(0xFFF2C38E)
        Planet.Saturn -> Color(0xFFE6D27A)
        Planet.Uranus -> Color(0xFF86E1EA)
        Planet.Neptune -> Color(0xFF8EA6FF)
    }
}

@Composable
fun galacticColor(): Color = if (LocalStatusColors.current.isNight) MaterialTheme.colorScheme.primary else Color(0xFFD9C8FF)

/**
 * Noon-to-noon strip: daylight, twilight, full darkness and the moon-free part of it, with the hour marks under it
 * and a legend. The spans are in the description for screen readers, so no table of times is needed.
 */
@Composable
fun NightBar(plan: NightPlan, nowMs: Long?, fmt: Fmt) {
    val pal = nightPalette()
    val now = MaterialTheme.colorScheme.error
    fun spans(list: List<Interval>) = if (list.isEmpty()) "none" else list.joinToString(", ") { "${fmt.time(it.startMs)} to ${fmt.time(it.endMs)}" }
    val description = "Darkness from noon to noon. Fully dark: ${spans(plan.astronomicalDark)}. Moon-free and dark: ${spans(plan.moonlessDark)}."
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp)).semantics { contentDescription = description }) {
            fun x(ms: Long) = timeX(plan, ms)
            drawDarkness(plan, pal)
            nowMs?.takeIf { it in plan.windowStartMs until plan.windowEndMs }?.let { drawRect(now, Offset(x(it) - 1.5f, 0f), Size(3f, size.height)) }
        }
        HourTicks(plan.windowStartMs, plan.windowEndMs, fmt)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Swatch(pal.twilight, "Twilight")
            Swatch(pal.dark, "Dark")
            Swatch(pal.moonFree, "No Moon", outline = true)
        }
    }
}

/** Horizontal position of [ms] in a noon-to-noon window drawn across the full width. */
private fun DrawScope.timeX(plan: NightPlan, ms: Long): Float {
    val span = (plan.windowEndMs - plan.windowStartMs).toFloat()
    return ((ms - plan.windowStartMs) / span * size.width).coerceIn(0f, size.width)
}

/** Daylight, then twilight, full darkness and moon-free darkness painted over it, full height. */
private fun DrawScope.drawDarkness(plan: NightPlan, pal: NightPalette) {
    fun band(list: List<Interval>, c: Color) = list.forEach {
        drawRect(c, Offset(timeX(plan, it.startMs), 0f), Size(timeX(plan, it.endMs) - timeX(plan, it.startMs), size.height))
    }
    drawRect(pal.day)
    band(plan.nauticalDark, pal.twilight)
    band(plan.astronomicalDark, pal.dark)
    band(plan.moonlessDark, pal.moonFree)
}

@Composable
private fun Swatch(color: Color, label: String, outline: Boolean = false) {
    val ring = MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(color)
            if (outline) drawCircle(ring, style = Stroke(1.dp.toPx()))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 18:00, 00:00 and 06:00 marks under a noon-to-noon strip, as actual clock times so DST nights stay honest. */
@Composable
private fun HourTicks(startMs: Long, endMs: Long, fmt: Fmt) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val labels = listOf(6, 12, 18).map { startMs + it * 3_600_000L }.filter { it < endMs }.map { it to fmt.time(it) }
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val span = (endMs - startMs).toFloat()
        for ((t, text) in labels) {
            val l = measurer.measure(text, style)
            val cx = (t - startMs) / span * size.width
            drawText(l, topLeft = Offset((cx - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), 2f))
        }
    }
}

/**
 * Altitude of each body across the night over the darkness bands. Tap or drag to read every body's altitude at
 * that moment; the readout below the plot is plain text, so screen readers get it too.
 */
@Composable
fun NightPlot(plan: NightPlan, series: List<PlotSeries>, nowMs: Long?, fmt: Fmt, height: Dp = 170.dp) {
    // Curves and labels sit on top of the bands here, so daylight is a dim blue, not the bright strip of the night bar.
    val pal = nightPalette().let { p ->
        if (LocalStatusColors.current.isNight) p.copy(day = p.moonFree.copy(alpha = 1f).compositeOverSelf(p.ink, 0.22f), twilight = p.moonFree.compositeOverSelf(p.ink, 0.14f), dark = p.moonFree.compositeOverSelf(p.ink, 0.07f))
        else p.copy(day = Color(0xFF34507E), twilight = Color(0xFF25386A))
    }
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontSize = 10.sp, color = pal.ink.copy(alpha = 0.75f))
    val count = series.firstOrNull()?.track?.altitudesDeg?.size ?: 0
    var cursor by remember(plan.windowStartMs, count) {
        mutableStateOf(nowMs?.takeIf { it in plan.windowStartMs until plan.windowEndMs && count > 1 }?.let { ((it - plan.windowStartMs) / (series[0].track.stepMs)).toInt().coerceIn(0, count - 1) })
    }
    val summary = series.joinToString("; ") { s ->
        val best = s.track.altitudesDeg.withIndex().maxByOrNull { it.value }
        if (best == null || best.value < 0f) "${s.name} stays below the horizon"
        else "${s.name} highest ${best.value.roundToInt()} degrees at ${fmt.time(s.track.startMs + best.index * s.track.stepMs)}"
    }
    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp))
                .semantics { contentDescription = "Altitude through the night. $summary" }
                .scrubX(count) { f -> if (count >= 2) cursor = SeriesPlot.scrubIndex(f, count) },
        ) {
            fun x(ms: Long) = timeX(plan, ms)
            val top = 8.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            fun y(alt: Float) = bottom - (alt.coerceIn(0f, 90f) / 90f) * (bottom - top)
            drawDarkness(plan, pal)
            for (alt in listOf(30f, 60f)) {
                drawLine(pal.grid, Offset(0f, y(alt)), Offset(size.width, y(alt)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                val l = measurer.measure("${alt.toInt()}°", axis)
                drawText(l, topLeft = Offset(4.dp.toPx(), y(alt) - l.size.height - 1f))
            }
            drawLine(pal.grid, Offset(0f, bottom), Offset(size.width, bottom), 1.5f)
            val placed = ArrayList<Rect>()
            for (s in series) {
                val tr = s.track
                val path = Path()
                var pen = false
                tr.altitudesDeg.forEachIndexed { i, alt ->
                    val px = x(tr.startMs + i * tr.stepMs)
                    if (alt >= 0f) {
                        if (pen) path.lineTo(px, y(alt)) else path.moveTo(px, y(alt))
                        pen = true
                    } else pen = false
                }
                drawPath(path, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                val peak = tr.altitudesDeg.withIndex().filter { it.value > 3f }.maxByOrNull { it.value } ?: continue
                val l = measurer.measure(s.name, TextStyle(fontSize = 11.sp, color = s.color))
                val px = x(tr.startMs + peak.index * tr.stepMs)
                // Planets often peak together; step a label down under the one it would cover instead of overprinting it.
                var box = Rect(Offset((px - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), (y(peak.value) - l.size.height - 2f).coerceAtLeast(0f)), Size(l.size.width.toFloat(), l.size.height.toFloat()))
                var tries = 0
                while (placed.any { it.overlaps(box) } && tries++ < 6) box = box.translate(0f, l.size.height.toFloat())
                placed += box
                drawText(l, topLeft = box.topLeft)
            }
            nowMs?.takeIf { it in plan.windowStartMs until plan.windowEndMs }?.let { drawLine(cs.error, Offset(x(it), 0f), Offset(x(it), size.height), 2f) }
            cursor?.let { i ->
                val t = series[0].track.startMs + i * series[0].track.stepMs
                drawLine(pal.ink, Offset(x(t), 0f), Offset(x(t), size.height), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                for (s in series) {
                    val alt = s.track.altitudesDeg.getOrNull(i) ?: continue
                    if (alt >= 0f) drawCircle(s.color, 4.dp.toPx(), Offset(x(t), y(alt)))
                }
            }
        }
        HourTicks(plan.windowStartMs, plan.windowEndMs, fmt)
        val i = cursor
        val readout = if (i == null) "Tap or drag the plot to read altitudes." else {
            val t = series[0].track.startMs + i * series[0].track.stepMs
            fmt.time(t) + " · " + series.joinToString(" · ") { s ->
                val alt = s.track.altitudesDeg.getOrNull(i)
                if (alt == null || alt < 0f) "${s.name} down" else "${s.name} ${alt.roundToInt()}°"
            }
        }
        Text(readout, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** A meteor shower as a bar: the full track is its peak ZHR, the filled part what you can expect from here. */
@Composable
fun RateBar(fraction: Float, potential: Float, color: Color) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val ghost = color.copy(alpha = 0.3f)
    Canvas(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        drawRect(track)
        drawRect(ghost, size = Size(size.width * potential.coerceIn(0f, 1f), size.height))
        drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}

/** The dark-adaptation timer as a ring that fills as your eyes adapt. */
@Composable
fun AdaptRing(progress: Float, done: Boolean, centre: String) {
    val cs = MaterialTheme.colorScheme
    val fill = if (done) LocalStatusColors.current.good else cs.primary
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp).semantics { contentDescription = "Dark adaptation ${(progress * 100).roundToInt()} percent" }) {
            val w = 12.dp.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(cs.onSurface.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(fill, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(centre, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    }
}

/** This colour with [other] laid over it at [alpha]: an opaque mix, so bands never let the card show through. */
private fun Color.compositeOverSelf(other: Color, alpha: Float): Color = Color(
    red = red + (other.red - red) * alpha,
    green = green + (other.green - green) * alpha,
    blue = blue + (other.blue - blue) * alpha,
)
