package com.example.trailblazer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.trailblazer.core.plot.RangeSample
import com.trailblazer.core.plot.SeriesPlot
import com.trailblazer.core.plot.SkyBody
import com.trailblazer.core.plot.SkyPolar

/** Daily high/low bars. Tap a bar to read that day; missing days leave a gap. */
@Composable
fun RangeBars(
    lows: List<Double?>,
    highs: List<Double?>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    minSpan: Double = 1.0,
    color: Color = MaterialTheme.colorScheme.primary,
    /** When set, each bar carries its high above and its low below. */
    valueLabel: ((Double) -> String)? = null,
    caption: (Int, RangeSample) -> String,
) {
    val parsed = remember(lows, highs, minSpan) { SeriesPlot.ranges(lows, highs, minSpan) } ?: return
    val (frame, bars) = parsed
    var selected by remember(bars) { mutableIntStateOf(bars.first().index) }
    val bar = bars.firstOrNull { it.index == selected } ?: bars.first()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier) {
        Text(caption(bar.index, bar), style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(bottom = 4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (valueLabel != null) 128.dp else 96.dp)
                .semantics { contentDescription = "Temperature range, ${bars.size} days" }
                .scrubX(frame.count, bars) { f ->
                    if (frame.count <= 0) return@scrubX
                    val i = SeriesPlot.scrubIndex(f, frame.count)
                    if (bars.any { it.index == i }) selected = i
                },
        ) {
            val n = frame.count.coerceAtLeast(1)
            val slot = size.width / n
            // Slim bars: a wide bar over a small range rounds into a dot and stops reading as a range.
            val barW = minOf(slot * 0.45f, 14.dp.toPx())
            val pad = if (valueLabel != null) 18.dp.toPx() else 0f
            drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
            for (b in bars) {
                val span = size.height - 2 * pad
                val top = pad + span * (1f - SeriesPlot.yFraction(b.high, frame.lo, frame.hi).toFloat())
                val bot = pad + span * (1f - SeriesPlot.yFraction(b.low, frame.lo, frame.hi).toFloat())
                val left = slot * b.index + (slot - barW) / 2f
                val h = (bot - top).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = if (b.index == bar.index) color else color.copy(alpha = 0.45f),
                    topLeft = Offset(left, top),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
                if (valueLabel != null) {
                    val cx = left + barW / 2f
                    val hi = measurer.measure(valueLabel(b.high), labelStyle)
                    val lo = measurer.measure(valueLabel(b.low), labelStyle)
                    drawText(hi, topLeft = Offset(cx - hi.size.width / 2f, top - hi.size.height - 2.dp.toPx()))
                    drawText(lo, topLeft = Offset(cx - lo.size.width / 2f, top + h + 2.dp.toPx()))
                }
            }
        }
        if (labels.isNotEmpty()) {
            // One label per bar slot, centred under its bar (a joined string drifted out of line after two bars).
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until frame.count) {
                    Text(
                        labels.getOrNull(i).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == bar.index) color else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** North-up sky plot. Tap a satellite to read it. Bodies below the horizon are omitted. */
@Composable
fun SatelliteSky(bodies: List<SkyBody>, modifier: Modifier = Modifier) {
    val marks = remember(bodies) { SkyPolar.layout(bodies) }
    if (marks.isEmpty()) return
    var picked by remember(bodies) { mutableStateOf<Int?>(null) }
    val label = picked?.let { bodies.getOrNull(it)?.label }
    val cs = MaterialTheme.colorScheme
    var sizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(bottom = 4.dp))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics { contentDescription = "Satellite sky, ${marks.size} above the horizon" }
                .onSizeChanged { sizePx = it }
                .pickPoint(marks) { p ->
                    val r = minOf(sizePx.width, sizePx.height) / 2f
                    if (r <= 0f) return@pickPoint
                    val x = (p.x - sizePx.width / 2f) / r
                    val y = (sizePx.height / 2f - p.y) / r
                    picked = SkyPolar.nearest(marks, x.toDouble(), y.toDouble(), 0.12)?.index
                },
        ) {
            val r = size.minDimension / 2f * 0.92f
            val c = center
            drawCircle(cs.surfaceContainerHighest.copy(alpha = 0.45f), r, c)
            drawCircle(cs.outlineVariant, r, c, style = Stroke(1.dp.toPx()))
            drawCircle(cs.outlineVariant.copy(alpha = 0.6f), r * 0.5f, c, style = Stroke(1.dp.toPx()))
            drawLine(cs.outlineVariant, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
            drawLine(cs.outlineVariant, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
            for (m in marks) {
                val body = bodies[m.index]
                val p = Offset(c.x + m.x.toFloat() * r, c.y - m.y.toFloat() * r)
                if (body.used) drawCircle(cs.primary, 5.dp.toPx(), p)
                else drawCircle(cs.onSurfaceVariant, 4.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                if (m.index == picked) drawCircle(cs.primary, 8.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Wind direction the air is coming from, arrow pointing downwind. */
@Composable
fun WindArrow(fromDeg: Double, modifier: Modifier = Modifier) {
    if (!fromDeg.isFinite()) return
    val color = MaterialTheme.colorScheme.secondary
    Canvas(modifier.semantics { contentDescription = "Wind from ${fromDeg.toInt()} degrees" }) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(color.copy(alpha = 0.15f), r, c)
        rotate(fromDeg.toFloat(), c) {
            val tip = Offset(c.x, c.y + r * 0.72f)
            val tail = Offset(c.x, c.y - r * 0.55f)
            drawLine(color, tail, tip, 2.5.dp.toPx())
            val head = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - 6.dp.toPx(), tip.y - 10.dp.toPx())
                lineTo(tip.x + 6.dp.toPx(), tip.y - 10.dp.toPx())
                close()
            }
            drawPath(head, color)
        }
    }
}
