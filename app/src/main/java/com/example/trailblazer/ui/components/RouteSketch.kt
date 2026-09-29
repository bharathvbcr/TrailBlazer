package com.example.trailblazer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.math.DEG
import com.trailblazer.core.trip.Stop
import com.trailblazer.core.trip.StopKind
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Letter label for the i-th stop: A…Z, then AA, AB… */
fun stopLetter(i: Int): String = if (i < 26) ('A' + i).toString() else stopLetter(i / 26 - 1) + ('A' + i % 26)

/**
 * Offline route sketch: stops projected onto a local plane (north up), straight legs between them,
 * a scale bar and a north arrow. No map tiles, so nothing is downloaded and nothing leaks.
 */
@Composable
fun RouteSketch(stops: List<Stop>, distanceLabel: (Double) -> String, modifier: Modifier = Modifier) {
    if (stops.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant)
    val letter = TextStyle(fontSize = 11.sp, color = cs.onPrimary)
    Canvas(modifier.fillMaxWidth().aspectRatio(1.4f).semantics { contentDescription = "Route sketch with ${stops.size} stops" }) {
        val lat0 = stops.map { it.position.lat }.average()
        val lon0 = stops.first().position.lon
        val kx = cos(lat0 * DEG) * Geo.EARTH_RADIUS_M * DEG
        val ky = Geo.EARTH_RADIUS_M * DEG
        fun wrapLon(d: Double) = ((d + 540.0) % 360.0) - 180.0
        val pts = stops.map { (wrapLon(it.position.lon - lon0) * kx) to (it.position.lat - lat0) * ky }
        val minX = pts.minOf { it.first }
        val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }
        val maxY = pts.maxOf { it.second }
        val pad = 28.dp.toPx()
        val spanM = max(max(maxX - minX, maxY - minY), 200.0)
        val scale = (minOf(size.width, size.height) - 2 * pad) / spanM
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        fun screen(p: Pair<Double, Double>) = Offset(
            (size.width / 2 + (p.first - cx) * scale).toFloat(),
            (size.height / 2 - (p.second - cy) * scale).toFloat(),
        )
        val sp = pts.map(::screen)
        for (i in 0 until sp.size - 1) {
            drawLine(cs.primary, sp[i], sp[i + 1], 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
            if (sp.size <= 9) {
                val mid = Offset((sp[i].x + sp[i + 1].x) / 2, (sp[i].y + sp[i + 1].y) / 2)
                val layout = measurer.measure(distanceLabel(Geo.distanceM(stops[i].position, stops[i + 1].position)), small)
                drawText(layout, topLeft = Offset(mid.x + 4, mid.y - layout.size.height - 2))
            }
        }
        sp.forEachIndexed { i, p ->
            val color = when (stops[i].kind) {
                StopKind.Start -> cs.primary
                StopKind.End -> cs.error
                StopKind.Night -> cs.secondary
                StopKind.Visit -> cs.tertiary
            }
            drawCircle(color, 11.dp.toPx(), p)
            val layout = measurer.measure(stopLetter(i), letter)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
        // North arrow.
        val n = Offset(size.width - 18.dp.toPx(), 26.dp.toPx())
        val arrow = Path().apply {
            moveTo(n.x, n.y - 12.dp.toPx()); lineTo(n.x - 6.dp.toPx(), n.y + 6.dp.toPx()); lineTo(n.x, n.y + 2.dp.toPx()); lineTo(n.x + 6.dp.toPx(), n.y + 6.dp.toPx()); close()
        }
        drawPath(arrow, cs.onSurface)
        val nl = measurer.measure("N", small)
        drawText(nl, topLeft = Offset(n.x - nl.size.width / 2f, n.y + 8.dp.toPx()))
        // Scale bar: a 1-2-5 length near a quarter of the width.
        val target = (size.width / 4) / scale
        val mag = 10.0.pow(floor(log10(target)))
        val nice = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.last { it <= target * 1.5 }
        val len = (nice * scale).toFloat()
        val y = size.height - 12.dp.toPx()
        val x0 = 12.dp.toPx()
        drawLine(cs.onSurface, Offset(x0, y), Offset(x0 + len, y), 2.dp.toPx())
        drawLine(cs.onSurface, Offset(x0, y - 5), Offset(x0, y + 5), 2.dp.toPx())
        drawLine(cs.onSurface, Offset(x0 + len, y - 5), Offset(x0 + len, y + 5), 2.dp.toPx())
        val sl = measurer.measure(distanceLabel(nice), small)
        drawText(sl, topLeft = Offset(x0, y - sl.size.height - 6))
        drawRect(cs.outlineVariant, style = Stroke(1f))
    }
}
