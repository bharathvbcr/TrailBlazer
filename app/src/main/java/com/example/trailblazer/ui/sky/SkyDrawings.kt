package com.example.trailblazer.ui.sky

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.astro.Horizontal
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The colours of the Sun and its sky, or the red night palette. */
internal data class SunColors(val sun: Color, val core: Color, val glow: Color, val golden: Color, val night: Boolean, val accent: Color)

@Composable
internal fun sunColors(): SunColors {
    val cs = MaterialTheme.colorScheme
    return if (LocalStatusColors.current.isNight) {
        SunColors(cs.primary, cs.primary, cs.primary.copy(alpha = 0.3f), cs.primary.copy(alpha = 0.7f), true, cs.primary)
    } else {
        SunColors(Color(0xFFFFB627), Color(0xFFFFF4C2), Color(0x66FFB300), Color(0xFFFF8F1F), false, cs.primary)
    }
}

/**
 * The sky's colour for a Sun altitude: day blue, the warm band near the horizon at sunrise and sunset, then civil,
 * nautical and astronomical twilight to night. Returns (zenith colour, horizon colour).
 */
internal fun skyFor(sunAltDeg: Double, night: Boolean, accent: Color): Pair<Color, Color> {
    if (night) {
        val a = ((sunAltDeg + 18) / 30).coerceIn(0.0, 1.0).toFloat()
        return accent.copy(alpha = 0.05f + 0.25f * a) to accent.copy(alpha = 0.08f + 0.3f * a)
    }
    val stops = listOf(
        -18.0 to (Color(0xFF070B1A) to Color(0xFF0D1330)),
        -12.0 to (Color(0xFF0B1230) to Color(0xFF1C2550)),
        -6.0 to (Color(0xFF16245A) to Color(0xFF4A4F8C)),
        -2.0 to (Color(0xFF26407E) to Color(0xFFD9785A)),
        3.0 to (Color(0xFF3A66B0) to Color(0xFFFFB36B)),
        10.0 to (Color(0xFF2F72C9) to Color(0xFFA9D2F5)),
        40.0 to (Color(0xFF2A6CC4) to Color(0xFFB9DCF8)),
    )
    if (sunAltDeg <= stops.first().first) return stops.first().second
    if (sunAltDeg >= stops.last().first) return stops.last().second
    val hi = stops.indexOfFirst { it.first >= sunAltDeg }
    val (a0, c0) = stops[hi - 1]
    val (a1, c1) = stops[hi]
    val f = ((sunAltDeg - a0) / (a1 - a0)).toFloat()
    return lerp(c0.first, c1.first, f) to lerp(c0.second, c1.second, f)
}

/** A sun: a soft halo, twelve rays in two lengths and a disc bright at the centre. [dim] draws it below the horizon. */
internal fun DrawScope.sunGlyph(c: Offset, r: Float, colors: SunColors, dim: Boolean = false) {
    val a = if (dim) 0.4f else 1f
    drawCircle(Brush.radialGradient(listOf(colors.glow.copy(alpha = colors.glow.alpha * a), Color.Transparent), c, r * 3f), r * 3f, c)
    for (i in 0 until 12) {
        val t = i * PI / 6
        val long = i % 2 == 0
        val r0 = r * 1.3f
        val r1 = r * (if (long) 2.0f else 1.65f)
        drawLine(
            colors.sun.copy(alpha = a),
            Offset(c.x + r0 * cos(t).toFloat(), c.y + r0 * sin(t).toFloat()),
            Offset(c.x + r1 * cos(t).toFloat(), c.y + r1 * sin(t).toFloat()),
            strokeWidth = r * (if (long) 0.26f else 0.18f),
            cap = StrokeCap.Round,
        )
    }
    drawCircle(Brush.radialGradient(listOf(colors.core.copy(alpha = a), colors.sun.copy(alpha = a)), c, r), r, c)
}

/** A few lunar seas, as fractions of the radius (x right, y down), for texture on the lit part. */
private val maria = listOf(
    Triple(-0.28f, -0.30f, 0.22f), Triple(0.05f, -0.42f, 0.16f), Triple(0.30f, -0.12f, 0.20f),
    Triple(-0.05f, 0.05f, 0.14f), Triple(-0.35f, 0.18f, 0.12f), Triple(0.18f, 0.38f, 0.10f),
)

/**
 * The Moon at [illumination], lit on the right or left as the observer sees it. The lit area is half a disc plus or
 * minus half an ellipse, so its share of the disc equals [illumination]; seas are drawn only inside the lit part.
 */
internal fun DrawScope.drawMoon(c: Offset, r: Float, illumination: Double, litOnRight: Boolean, lit: Color, shade: Color, alpha: Float = 1f) {
    drawCircle(shade.copy(alpha = shade.alpha * alpha), r, c)
    val k = (1 - 2 * illumination.coerceIn(0.0, 1.0)).toFloat()
    val w = r * k
    val litPath = Path().apply {
        moveTo(c.x, c.y - r)
        arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, 180f, false)
        // Back up the terminator from the bottom: a crescent (w > 0) bulges into the lit half (through the right,
        // sweep −180°), a gibbous (w < 0) bulges out through the left. The opposite sweep drew 1 − k lit.
        arcTo(Rect(c.x - abs(w), c.y - r, c.x + abs(w), c.y + r), 90f, if (w >= 0) -180f else 180f, false)
        close()
    }
    scale(if (litOnRight) 1f else -1f, 1f, pivot = c) {
        drawPath(litPath, lit.copy(alpha = alpha))
        if (r >= 8.dp.toPx()) {
            val sea = lerp(lit, Color(0xFF9A8F72), 0.2f).copy(alpha = alpha)
            clipPath(litPath) {
                // Mirrored back so the seas stay where they are on the real Moon whichever side is lit.
                scale(if (litOnRight) 1f else -1f, 1f, pivot = c) {
                    for ((dx, dy, rr) in maria) drawCircle(sea, r * rr, Offset(c.x + dx * r, c.y + dy * r))
                }
            }
        }
    }
}

/**
 * A body's height through the day: the sky coloured by where the Sun is at each moment (day, sunset glow, twilight,
 * night with stars), a ridge on the horizon, the body's path (solid above the horizon, dashed below, gold in the
 * golden hour when [golden] is set), hour ticks, and the body drawn where it is at [atMs].
 */
@Composable
internal fun SkyArc(
    path: List<Horizontal>,
    sunPath: List<Horizontal>,
    windowStartMs: Long,
    stepMs: Long,
    atMs: Long,
    fmt: Fmt,
    lineColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    golden: Color? = null,
    fillAlpha: Float = 0.35f,
    body: DrawScope.(Offset, Float, Boolean) -> Unit,
) {
    if (path.size < 2) return
    val colors = sunColors()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = Color.White.copy(alpha = 0.75f))
    val peak = path.maxOf { it.altitudeDeg }
    val top = max(peak, 20.0) + 14.0
    val bottom = -26.0
    // Fixed star field: positions do not jump as the minute ticks.
    val stars = remember { List(70) { i -> Triple(((i * 0.618034) % 1.0).toFloat(), ((i * 0.41421 + 0.13) % 1.0).toFloat(), 0.5f + (i % 3) * 0.35f) } }
    val hours = remember(windowStartMs) { listOf(6, 12, 18).map { windowStartMs + it * 3_600_000L } }
    val currentAtMs by rememberUpdatedState(atMs)
    val hourLabels = remember(hours, fmt, measurer, labelStyle) {
        hours.map { t -> t to measurer.measure(fmt.time(t), labelStyle) }
    }
    val peakLabel = remember(peak, measurer, labelStyle) {
        if (peak > 0) measurer.measure("${peak.toInt()}°", labelStyle.copy(color = Color.White, fontSize = 11.sp)) else null
    }
    val peakIndex = remember(path) { if (peak > 0) path.indices.maxBy { path[it].altitudeDeg } else -1 }
    val n = path.size
    val skies = remember(sunPath, colors.night, colors.accent) {
        List(n) { i -> skyFor(sunPath.getOrNull(i)?.altitudeDeg ?: -90.0, colors.night, colors.accent) }
    }
    Spacer(
        modifier.semantics { contentDescription = description }
            .drawWithCache {
                fun y(alt: Double) = (size.height * ((top - alt.coerceIn(bottom, top)) / (top - bottom))).toFloat()
                fun x(i: Double) = (size.width * i / (n - 1)).toFloat()
                val horizon = y(0.0)
                val bands = 64
                val bandDraws = (0 until bands).map { b ->
                    val f = (b + 0.5f) / bands
                    val topPx = kotlin.math.floor(horizon * b / bands)
                    val bottomPx = kotlin.math.ceil(horizon * (b + 1) / bands) + 1f
                    val brush = Brush.horizontalGradient(skies.map { (zenith, low) -> lerp(zenith, low, f * f) }, 0f, size.width)
                    Triple(brush, Offset(0f, topPx), Size(size.width, (bottomPx - topPx).coerceAtMost(horizon - topPx)))
                }
                val fill = Path().apply {
                    moveTo(0f, horizon)
                    path.forEachIndexed { i, h -> lineTo(x(i.toDouble()), y(max(h.altitudeDeg, 0.0))) }
                    lineTo(size.width, horizon)
                    close()
                }
                val fillBrush = Brush.verticalGradient(listOf(lineColor.copy(alpha = fillAlpha), lineColor.copy(alpha = 0.02f)), y(peak), horizon)
                val ridge = Path().apply {
                    moveTo(0f, size.height)
                    var px = 0f
                    while (px <= size.width) {
                        val f = px / size.width
                        val hh = (sin(f * 13.0) * 0.35 + sin(f * 29.0 + 1.3) * 0.2 + sin(f * 5.0 + 0.4) * 0.45).toFloat() * 5.dp.toPx()
                        lineTo(px, horizon - 3.dp.toPx() - abs(hh))
                        px += 4f
                    }
                    lineTo(size.width, size.height)
                    close()
                }
                val ground = if (colors.night) Color.Black else Color(0xFF0E1424)
                val whole = Path().apply { path.forEachIndexed { i, h -> if (i == 0) moveTo(x(0.0), y(h.altitudeDeg)) else lineTo(x(i.toDouble()), y(h.altitudeDeg)) } }
                val wholeStroke = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f)))

                onDrawBehind {
                    for ((brush, offset, sz) in bandDraws) {
                        drawRect(brush, offset, sz)
                    }
                    // Stars where the sky is dark.
                    for ((sx, sy, sr) in stars) {
                        val i = (sx * (n - 1)).toInt().coerceIn(0, n - 1)
                        val sunAlt = sunPath.getOrNull(i)?.altitudeDeg ?: -90.0
                        val dark = ((-sunAlt - 8) / 10).coerceIn(0.0, 1.0).toFloat()
                        if (dark > 0f) drawCircle(Color.White.copy(alpha = 0.75f * dark), sr.dp.toPx(), Offset(sx * size.width, sy * horizon * 0.92f))
                    }
                    // Altitude guides.
                    var g = 30.0
                    while (g < top) {
                        drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, y(g)), Offset(size.width, y(g)), 1f)
                        if (y(g) - 13.dp.toPx() >= 0f) drawText(measurer, "${g.toInt()}°", Offset(4.dp.toPx(), y(g) - 13.dp.toPx()), labelStyle)
                        g += 30.0
                    }
                    // Soft fill under the daylit path.
                    drawPath(fill, fillBrush)
                    // Ground: a low ridge silhouette.
                    drawRect(ground, Offset(0f, horizon), Size(size.width, size.height - horizon))
                    drawPath(ridge, ground)
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, horizon), Offset(size.width, horizon), 1.dp.toPx())
                    // The path: dashed everywhere, solid (and gold low down) above the horizon.
                    drawPath(whole, Color.White.copy(alpha = 0.3f), style = wholeStroke)
                    for (i in 0 until n - 1) {
                        val a = path[i]
                        val b = path[i + 1]
                        if (a.altitudeDeg < -0.8 && b.altitudeDeg < -0.8) continue
                        val low = (a.altitudeDeg + b.altitudeDeg) / 2 < 6.0
                        drawLine(
                            if (golden != null && low) golden else lineColor,
                            Offset(x(i.toDouble()), y(a.altitudeDeg)), Offset(x(i + 1.0), y(b.altitudeDeg)),
                            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                        )
                    }
                    // Hour ticks along the bottom.
                    for ((t, m) in hourLabels) {
                        val hx = ((t - windowStartMs).toFloat() / (stepMs * (n - 1))) * size.width
                        if (hx <= 0f || hx >= size.width) continue
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(hx, size.height - 5.dp.toPx()), Offset(hx, size.height), 1.dp.toPx())
                        drawText(m, topLeft = Offset(hx - m.size.width / 2f, size.height - 6.dp.toPx() - m.size.height))
                    }
                    if (peak > 0 && peakLabel != null) {
                        val px = (x(peakIndex.toDouble()) - peakLabel.size.width / 2f).coerceIn(0f, size.width - peakLabel.size.width)
                        drawText(peakLabel, topLeft = Offset(px, (y(peak) - peakLabel.size.height - 10.dp.toPx()).coerceAtLeast(0f)))
                    }
                    // The body at the chosen time, interpolated between samples and kept whole inside the frame.
                    val atTime = currentAtMs
                    val f = ((atTime - windowStartMs).toDouble() / stepMs).coerceIn(0.0, n - 1.0)
                    val lo = f.toInt().coerceAtMost(n - 2)
                    val alt = path[lo].altitudeDeg + (path[lo + 1].altitudeDeg - path[lo].altitudeDeg) * (f - lo)
                    val br = 7.dp.toPx()
                    drawLine(Color.White.copy(alpha = 0.25f), Offset(x(f), 0f), Offset(x(f), size.height), 1.dp.toPx())
                    body(Offset(x(f).coerceIn(br * 2, size.width - br * 2), y(alt).coerceIn(br * 2, size.height - br * 2)), br, alt < -0.8)
                }
            }
    )
}

/**
 * Looking straight up: the horizon is the rim, the zenith the centre, north at the top and east on the right as on a
 * map (the same way as the satellite sky plot). The body's path above the horizon is drawn with a dot on each hour,
 * arrows where it rises (pointing in) and sets (pointing out), and the body where it is now: on the rim and dimmed
 * when it is below the horizon.
 */
@Composable
internal fun HorizonCompass(
    path: List<Horizontal>,
    riseAzDeg: Double?,
    setAzDeg: Double?,
    now: Horizontal?,
    bodyColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    body: DrawScope.(Offset, Float, Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val night = LocalStatusColors.current.isNight
    val measurer = rememberTextMeasurer()
    val cardinal = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant)
    val minor = TextStyle(fontSize = 8.sp, color = cs.onSurfaceVariant.copy(alpha = 0.7f))
    val domeTop = if (night) cs.primary.copy(alpha = 0.12f) else Color(0xFF5B9BE0)
    val domeRim = if (night) cs.primary.copy(alpha = 0.04f) else Color(0xFF1F3F75)
    val compassLabels = remember(measurer, cardinal, minor, cs.error) {
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0, "NE" to 45.0, "SE" to 135.0, "SW" to 225.0, "NW" to 315.0).map { (label, az) ->
            val main = label.length == 1
            val style = if (label == "N") cardinal.copy(color = cs.error) else if (main) cardinal else minor
            Triple(label, az, measurer.measure(label, style))
        }
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val pad = 16.dp.toPx()
        val r = size.minDimension / 2 - pad
        val c = center
        fun polar(az: Double, rr: Float): Offset {
            val t = Math.toRadians(az)
            return Offset((c.x + rr * sin(t)).toFloat(), (c.y - rr * cos(t)).toFloat())
        }
        fun at(az: Double, alt: Double) = polar(az, r * ((90.0 - alt.coerceIn(0.0, 90.0)) / 90.0).toFloat())
        drawCircle(Brush.radialGradient(listOf(domeTop, domeRim), c, r), r, c)
        drawCircle(cs.outline.copy(alpha = 0.6f), r, c, style = Stroke(1.dp.toPx()))
        for (ring in listOf(30.0, 60.0)) drawCircle(Color.White.copy(alpha = 0.18f), r * ((90 - ring) / 90).toFloat(), c, style = Stroke(1f))
        // Sixteen ticks round the rim, longer on the cardinal points.
        for (i in 0 until 16) {
            val az = i * 22.5
            val len = when {
                i % 4 == 0 -> 7.dp.toPx()
                i % 2 == 0 -> 5.dp.toPx()
                else -> 3.dp.toPx()
            }
            drawLine(cs.onSurfaceVariant.copy(alpha = 0.6f), polar(az, r), polar(az, r + len), 1.dp.toPx())
        }
        for ((label, az, m) in compassLabels) {
            val main = label.length == 1
            val p = polar(az, r + (if (main) 12.dp.toPx() else 10.dp.toPx()))
            if (!main && r < 50.dp.toPx()) continue
            drawText(m, topLeft = Offset(p.x - m.size.width / 2f, p.y - m.size.height / 2f))
        }
        // The path above the horizon, in runs (it can rise and set more than once in a window), with hourly dots.
        var run: Path? = null
        path.forEachIndexed { i, h ->
            if (h.altitudeDeg >= 0) {
                val p = at(h.azimuthDeg, h.altitudeDeg)
                if (run == null) run = Path().apply { moveTo(p.x, p.y) } else run!!.lineTo(p.x, p.y)
            } else {
                run?.let { drawPath(it, bodyColor.copy(alpha = 0.8f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }
                run = null
            }
        }
        run?.let { drawPath(it, bodyColor.copy(alpha = 0.8f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }
        path.forEachIndexed { i, h -> if (i % 4 == 0 && h.altitudeDeg >= 0) drawCircle(Color.White.copy(alpha = 0.85f), 1.8.dp.toPx(), at(h.azimuthDeg, h.altitudeDeg)) }
        // Rise arrow points in, set arrow points out.
        fun arrow(az: Double, inward: Boolean) {
            val tip = polar(az, if (inward) r - 9.dp.toPx() else r + 7.dp.toPx())
            val base = polar(az, if (inward) r + 3.dp.toPx() else r - 5.dp.toPx())
            val t = Math.toRadians(az)
            val side = Offset(cos(t).toFloat(), sin(t).toFloat()) * 4.dp.toPx()
            drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(base.x + side.x, base.y + side.y); lineTo(base.x - side.x, base.y - side.y); close() }, bodyColor)
        }
        riseAzDeg?.let { arrow(it, inward = true) }
        setAzDeg?.let { arrow(it, inward = false) }
        if (now != null) body(at(now.azimuthDeg, now.altitudeDeg), 6.5.dp.toPx(), now.altitudeDeg < 0)
    }
}

/** A small weather picture for a WMO code: sun, cloud, rain, snow, fog or lightning, drawn rather than an icon font. */
@Composable
internal fun WeatherGlyph(code: Int?, modifier: Modifier = Modifier) {
    val colors = sunColors()
    val cs = MaterialTheme.colorScheme
    val night = LocalStatusColors.current.isNight
    val cloud = if (night) cs.primary.copy(alpha = 0.6f) else Color(0xFFDCE3EC)
    val cloudDark = if (night) cs.primary.copy(alpha = 0.4f) else Color(0xFF8C98A8)
    val rain = if (night) cs.primary else Color(0xFF4FA3F7)
    val bolt = if (night) cs.primary else Color(0xFFFFD54F)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun cloudAt(cx: Float, cy: Float, s: Float, color: Color) {
            drawCircle(color, s * 0.22f, Offset(cx - s * 0.2f, cy))
            drawCircle(color, s * 0.3f, Offset(cx + s * 0.05f, cy - s * 0.12f))
            drawCircle(color, s * 0.2f, Offset(cx + s * 0.3f, cy + s * 0.02f))
            drawRoundRect(color, Offset(cx - s * 0.42f, cy), Size(s * 0.92f, s * 0.22f), androidx.compose.ui.geometry.CornerRadius(s * 0.11f))
        }
        when (code) {
            0 -> sunGlyph(Offset(w / 2, h / 2), w * 0.2f, colors)
            1, 2 -> {
                sunGlyph(Offset(w * 0.36f, h * 0.36f), w * 0.15f, colors)
                cloudAt(w * 0.56f, h * 0.6f, w * (if (code == 1) 0.55f else 0.72f), cloud)
            }
            3 -> {
                cloudAt(w * 0.42f, h * 0.42f, w * 0.6f, cloudDark)
                cloudAt(w * 0.55f, h * 0.58f, w * 0.72f, cloud)
            }
            45, 48 -> for (k in 0 until 4) {
                val yy = h * (0.3f + k * 0.14f)
                drawLine(cloud, Offset(w * (0.18f + (k % 2) * 0.08f), yy), Offset(w * (0.82f - (k % 2) * 0.08f), yy), h * 0.06f, StrokeCap.Round)
            }
            in 51..67, in 80..82 -> {
                cloudAt(w * 0.5f, h * 0.38f, w * 0.72f, cloudDark)
                val heavy = code == 65 || code == 67 || code == 82
                for (k in 0 until (if (heavy) 4 else 3)) {
                    val xx = w * (0.3f + k * (if (heavy) 0.13f else 0.2f))
                    drawLine(rain, Offset(xx, h * 0.66f), Offset(xx - w * 0.06f, h * 0.84f), w * 0.05f, StrokeCap.Round)
                }
            }
            in 71..77, 85, 86 -> {
                cloudAt(w * 0.5f, h * 0.38f, w * 0.72f, cloudDark)
                for (k in 0 until 3) drawCircle(cloud, w * 0.05f, Offset(w * (0.3f + k * 0.2f), h * (0.72f + (k % 2) * 0.08f)))
            }
            in 95..99 -> {
                cloudAt(w * 0.5f, h * 0.36f, w * 0.72f, cloudDark)
                val p = Path().apply {
                    moveTo(w * 0.52f, h * 0.5f); lineTo(w * 0.4f, h * 0.7f); lineTo(w * 0.5f, h * 0.7f); lineTo(w * 0.44f, h * 0.9f)
                    lineTo(w * 0.62f, h * 0.64f); lineTo(w * 0.52f, h * 0.64f); close()
                }
                drawPath(p, bolt)
            }
            else -> cloudAt(w * 0.5f, h * 0.5f, w * 0.7f, cloud)
        }
    }
}
