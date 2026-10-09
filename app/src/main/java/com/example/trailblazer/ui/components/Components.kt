package com.example.trailblazer.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.example.trailblazer.ui.theme.LocalStatusColors
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trailblazer.container
import com.example.trailblazer.permissions.AppPermission
import com.trailblazer.core.sensors.Reading
import com.example.trailblazer.ui.label
import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.plot.SeriesPlot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/**
 * A labelled reading. Unavailable readings say why instead of showing a number; stale readings are
 * dimmed and marked; nothing ever shows a placeholder number.
 */
@Composable
fun <T> ValueTile(
    label: String,
    reading: Reading<T>,
    what: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    caption: ((Reading.Value<T>) -> String?)? = null,
    onClick: (() -> Unit)? = null,
    format: (T) -> String,
) {
    GlassCard(modifier, corner = 20.dp, padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        when (reading) {
            is Reading.Value -> {
                val text = format(reading.value)
                Text(
                    text,
                    style = MaterialTheme.typography.titleLarge.merge(TextStyle(fontFeatureSettings = "tnum")),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (reading.stale) 0.5f else 1f).semantics { contentDescription = "$label $text" },
                )
                val cap = if (reading.stale) "Last known value" else caption?.invoke(reading)
                if (cap != null) Text(cap, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Reading.Acquiring -> Text("Waiting for $what…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is Reading.Unavailable -> Text(reading.reason.label(what), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    // A plain Row measures the value first, so a very long value (a sensor range of 3.4 × 10³⁸ printed in full) took
    // every pixel and squeezed the label into one letter per line. The label keeps its natural width up to
    // [LABEL_MIN_SHARE] of the row; the value gets the rest and wraps, right-aligned.
    Layout(
        content = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge.merge(TextStyle(fontFeatureSettings = "tnum")), textAlign = TextAlign.End)
        },
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) { measurables, constraints ->
        val gap = 12.dp.roundToPx()
        if (!constraints.hasBoundedWidth) {
            // Unbounded (a horizontal scroll, an intrinsic-size query): both at their natural widths, side by side.
            val l = measurables[0].measure(Constraints())
            val v = measurables[1].measure(Constraints())
            val h = maxOf(l.height, v.height)
            return@Layout layout(l.width + gap + v.width, h) {
                l.placeRelative(0, (h - l.height) / 2)
                v.placeRelative(l.width + gap, (h - v.height) / 2)
            }
        }
        val width = constraints.maxWidth
        val labelKeeps = min(measurables[0].maxIntrinsicWidth(Int.MAX_VALUE), (width * LABEL_MIN_SHARE).toInt())
        val valueMax = (width - labelKeeps - gap).coerceAtLeast(0)
        val v = measurables[1].measure(Constraints(maxWidth = valueMax))
        val l = measurables[0].measure(Constraints(maxWidth = (width - v.width - gap).coerceAtLeast(0)))
        val h = maxOf(l.height, v.height)
        layout(width, h) {
            l.placeRelative(0, (h - l.height) / 2)
            v.placeRelative(width - v.width, (h - v.height) / 2)
        }
    }
}

/** The share of a [LabelValue] row its label may always keep, however long the value. */
internal const val LABEL_MIN_SHARE = 0.45f

/**
 * Asks for [permission] in context. Shows [rationale] and an Allow button; after a permanent denial it
 * offers the app's system settings page instead, since Android will no longer show the dialog.
 */
@Composable
fun PermissionGate(permission: AppPermission, rationale: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val perms = ctx.container.permissions
    val granted by perms.granted.collectAsStateWithLifecycle()
    var asked by rememberSaveable(permission) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        perms.refresh()
    }
    if (permission in granted || permission.manifest.isEmpty()) {
        content()
        return
    }
    GlassCard(modifier.fillMaxWidth()) {
        Text(rationale, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(permission.manifest) }) { Text("Allow") }
            if (asked) {
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open settings") }
            }
        }
    }
}

/**
 * Filled line chart. Null and non-finite samples break the line instead of collapsing the axis.
 * Drag or tap to read one value.
 */
@Composable
fun Sparkline(
    values: List<Double?>,
    modifier: Modifier = Modifier,
    minSpan: Double = 0.0,
    color: Color = MaterialTheme.colorScheme.primary,
    valueText: ((Double) -> String)? = null,
    /** Names a sample by its index and value ("22:00 · 17°"); shown from the first sample until you scrub. */
    caption: ((index: Int, value: Double) -> String)? = null,
    /** Labels spread evenly under the chart, first at the left edge and last at the right. */
    axis: List<String> = emptyList(),
) {
    val frame = remember(values, minSpan) { SeriesPlot.frame(values, minSpan) } ?: return
    var scrub by remember(frame) { mutableStateOf<Int?>(null) }
    val shown = scrub?.let { i -> frame.samples.minByOrNull { abs(it.index - i) } } ?: if (caption != null) frame.samples.first() else null
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        if (shown != null && (caption != null || valueText != null)) {
            Text(
                caption?.invoke(shown.index, shown.value) ?: valueText!!.invoke(shown.value),
                style = MaterialTheme.typography.labelLarge,
                color = color,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .semantics { contentDescription = "Chart, ${frame.samples.size} points" }
                .scrubX(frame.count) { f -> scrub = SeriesPlot.scrubIndex(f, frame.count) },
        ) {
            fun xy(index: Int, value: Double): Offset {
                val x = if (frame.count <= 1) 0f else size.width * index / (frame.count - 1)
                val y = size.height * (1f - SeriesPlot.yFraction(value, frame.lo, frame.hi).toFloat())
                return Offset(x, y)
            }
            val runs = ArrayList<List<IndexedValue>>()
            var run = ArrayList<IndexedValue>()
            for (s in frame.samples) {
                if (run.isNotEmpty() && s.index != run.last().index + 1) {
                    runs += run.toList()
                    run = ArrayList()
                }
                run += IndexedValue(s.index, s.value)
            }
            if (run.isNotEmpty()) runs += run.toList()
            drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
            for (segment in runs) {
                if (segment.size == 1) {
                    drawCircle(color, 3.dp.toPx(), xy(segment[0].index, segment[0].value))
                    continue
                }
                val line = Path()
                val fill = Path()
                segment.forEachIndexed { i, s ->
                    val p = xy(s.index, s.value)
                    if (i == 0) {
                        line.moveTo(p.x, p.y)
                        fill.moveTo(p.x, p.y)
                    } else {
                        line.lineTo(p.x, p.y)
                        fill.lineTo(p.x, p.y)
                    }
                }
                val first = xy(segment.first().index, segment.first().value)
                val last = xy(segment.last().index, segment.last().value)
                fill.lineTo(last.x, size.height)
                fill.lineTo(first.x, size.height)
                fill.close()
                drawPath(fill, color.copy(alpha = 0.22f))
                drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            shown?.let { s ->
                val p = xy(s.index, s.value)
                drawLine(color.copy(alpha = 0.7f), Offset(p.x, 0f), Offset(p.x, size.height), 1.5.dp.toPx())
                drawCircle(color, 4.5.dp.toPx(), p)
            }
        }
        if (axis.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (label in axis) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class IndexedValue(val index: Int, val value: Double)

/** A bearing marker drawn on the dial rim (sun, moon, waypoint). */
data class DialMarker(val bearingDeg: Double, val color: Color, val label: String)

/**
 * Compass rose. The card rotates so the top of the screen is the current heading; rotation always takes
 * the short way round (359° → 1° turns 2°, not 358°).
 *
 * When held flat ([upright] = false), a spirit-level reticle and bubble in the center provide visual
 * feedback to keep the phone level for maximum magnetic heading accuracy.
 * An optional [accuracyDeg] draws a confidence wedge around the lubber line (Google Maps / Pixel style).
 */
@Composable
fun Dial(
    headingDeg: Double?,
    markers: List<DialMarker>,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    pitchDeg: Double? = null,
    rollDeg: Double? = null,
    accuracyDeg: Double? = null,
    upright: Boolean = false,
) {
    // Unwrapped (continuous) angle so the animation never spins the long way across north.
    val unwrapped = remember { floatArrayOf(headingDeg?.toFloat() ?: 0f) }
    if (headingDeg != null) unwrapped[0] += angleDiff(unwrapped[0].toDouble(), headingDeg).toFloat()
    val animated by animateFloatAsState(unwrapped[0], spring(stiffness = 120f), label = "dial")
    val cs = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.titleMedium
    val smallStyle = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant)
    val cardinalLayouts = remember(measurer, labelStyle, cs.error, cs.onSurface) {
        listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").map { (d, t) ->
            d to measurer.measure(t, labelStyle.copy(color = if (d == 0) cs.error else cs.onSurface))
        }
    }
    val markerLayouts = remember(markers, measurer, smallStyle) {
        markers.map { m -> m to measurer.measure(m.label, smallStyle) }
    }
    Canvas(modifier.aspectRatio(1f).alpha(if (dimmed) 0.45f else 1f)) {
        val r = min(size.width, size.height) / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(cs.surfaceContainerHighest.copy(alpha = 0.5f), r, c)
        drawCircle(cs.outlineVariant, r, c, style = Stroke(1.dp.toPx()))

        // Confidence wedge / accuracy beam (Google Maps / Pixel style) along the lubber line (top = -90°).
        if (accuracyDeg != null && accuracyDeg.isFinite() && accuracyDeg > 0.0) {
            val acc = accuracyDeg.toFloat().coerceIn(1.5f, 45f)
            val beamR = r * 0.96f
            drawArc(
                brush = Brush.radialGradient(
                    colors = listOf(cs.primary.copy(alpha = 0.16f), cs.primary.copy(alpha = 0.03f), Color.Transparent),
                    center = c,
                    radius = beamR,
                ),
                startAngle = -90f - acc,
                sweepAngle = 2f * acc,
                useCenter = true,
                topLeft = Offset(c.x - beamR, c.y - beamR),
                size = Size(beamR * 2, beamR * 2),
            )
            drawArc(
                color = cs.primary.copy(alpha = 0.45f),
                startAngle = -90f - acc,
                sweepAngle = 2f * acc,
                useCenter = false,
                topLeft = Offset(c.x - r + 3.dp.toPx(), c.y - r + 3.dp.toPx()),
                size = Size((r - 3.dp.toPx()) * 2, (r - 3.dp.toPx()) * 2),
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        rotate(-animated, c) {
            for (d in 0 until 360 step 5) {
                val major = d % 30 == 0
                val len = if (major) r * 0.11f else r * 0.05f
                val a = Math.toRadians(d.toDouble() - 90)
                val o = Offset(c.x + (r - 4) * cos(a).toFloat(), c.y + (r - 4) * sin(a).toFloat())
                val i = Offset(c.x + (r - 4 - len) * cos(a).toFloat(), c.y + (r - 4 - len) * sin(a).toFloat())
                drawLine(if (d == 0) cs.error else cs.onSurfaceVariant, i, o, if (major) 2.dp.toPx() else 1.dp.toPx())
            }
            for ((d, layout) in cardinalLayouts) {
                val a = Math.toRadians(d.toDouble() - 90)
                val p = Offset(c.x + r * 0.72f * cos(a).toFloat(), c.y + r * 0.72f * sin(a).toFloat())
                rotate(animated, p) {
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
            }
            for ((m, layout) in markerLayouts) {
                val a = Math.toRadians(m.bearingDeg - 90)
                val p = Offset(c.x + r * 0.88f * cos(a).toFloat(), c.y + r * 0.88f * sin(a).toFloat())
                drawCircle(m.color, 7.dp.toPx(), p)
                val q = Offset(c.x + r * 0.55f * cos(a).toFloat(), c.y + r * 0.55f * sin(a).toFloat())
                rotate(animated, q) { drawText(layout, topLeft = Offset(q.x - layout.size.width / 2f, q.y - layout.size.height / 2f)) }
            }
        }
        // Lubber line: the direction the phone points.
        val tip = Offset(c.x, c.y - r + 2)
        val path = Path().apply {
            moveTo(tip.x, tip.y - 2)
            lineTo(tip.x - 9.dp.toPx(), tip.y - 2 - 14.dp.toPx())
            lineTo(tip.x + 9.dp.toPx(), tip.y - 2 - 14.dp.toPx())
            close()
        }
        val isLevelFlat = !upright && pitchDeg != null && rollDeg != null &&
            pitchDeg.isFinite() && rollDeg.isFinite() &&
            kotlin.math.abs(pitchDeg) <= 2.0 && kotlin.math.abs(rollDeg) <= 2.0
        val lubberColor = if (isLevelFlat) status.good else cs.primary
        drawPath(path, lubberColor)
        drawLine(lubberColor, Offset(c.x, c.y - r * 0.25f), Offset(c.x, c.y - r + 6), 3.dp.toPx(), StrokeCap.Round)

        // Center level reticle (Pixel / bubble level style) when flat, or simple pivot when upright / no tilt data.
        if (!upright && pitchDeg != null && rollDeg != null && pitchDeg.isFinite() && rollDeg.isFinite()) {
            val reticleR = r * 0.20f
            val targetR = reticleR * 0.38f
            val bubbleR = targetR * 0.80f
            val levelColor = status.good

            // Outer reticle circle
            drawCircle(cs.outlineVariant.copy(alpha = 0.55f), reticleR, c, style = Stroke(1.dp.toPx()))

            // Crosshair tick marks on outer reticle
            val tic = 4.dp.toPx()
            drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(c.x - reticleR, c.y), Offset(c.x - reticleR + tic, c.y), 1.dp.toPx())
            drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(c.x + reticleR, c.y), Offset(c.x + reticleR - tic, c.y), 1.dp.toPx())
            drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(c.x, c.y - reticleR), Offset(c.x, c.y - reticleR + tic), 1.dp.toPx())
            drawLine(cs.outlineVariant.copy(alpha = 0.6f), Offset(c.x, c.y + reticleR), Offset(c.x, c.y + reticleR - tic), 1.dp.toPx())

            // Inner target ring & level glow
            if (isLevelFlat) {
                // Soft glow aura when snapped level (Pixel camera style)
                drawCircle(levelColor.copy(alpha = 0.18f), targetR * 1.55f, c)
            }
            drawCircle(if (isLevelFlat) levelColor else cs.outline.copy(alpha = 0.5f), targetR, c, style = Stroke(if (isLevelFlat) 2.dp.toPx() else 1.dp.toPx()))

            // Target crosshair
            val cross = targetR * 0.45f
            drawLine(if (isLevelFlat) levelColor else cs.outlineVariant, Offset(c.x - cross, c.y), Offset(c.x + cross, c.y), 1.dp.toPx())
            drawLine(if (isLevelFlat) levelColor else cs.outlineVariant, Offset(c.x, c.y - cross), Offset(c.x, c.y + cross), 1.dp.toPx())

            // Floating bubble (floats uphill / towards opposite of tilt)
            if (isLevelFlat) {
                // Snapped to dead center when level, glowing in good status color
                drawCircle(levelColor.copy(alpha = 0.90f), bubbleR, c)
            } else {
                val maxTilt = 8.0 // 8° tilt reaches the outer reticle limit
                val travel = reticleR - bubbleR
                val bx = (-rollDeg / maxTilt).coerceIn(-1.0, 1.0).toFloat() * travel
                val by = (pitchDeg / maxTilt).coerceIn(-1.0, 1.0).toFloat() * travel
                val bubblePos = Offset(c.x + bx, c.y + by)
                drawCircle(cs.primary.copy(alpha = 0.25f), bubbleR, bubblePos)
                drawCircle(cs.primary, bubbleR, bubblePos, style = Stroke(1.5.dp.toPx()))
            }
        } else {
            drawCircle(cs.primary, 5.dp.toPx(), c)
        }
    }
}

/** Two-line empty state for lists, with optional icon container. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
