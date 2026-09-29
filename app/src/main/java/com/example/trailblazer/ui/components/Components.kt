package com.example.trailblazer.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trailblazer.container
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.ui.label
import com.trailblazer.core.math.angleDiff
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
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
                Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        when (reading) {
            is Reading.Value -> {
                val text = format(reading.value)
                Text(
                    text,
                    style = MaterialTheme.typography.titleLarge,
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
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge.merge(TextStyle(fontFeatureSettings = "tnum")))
    }
}

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

/** Line chart scaled to the data's own range (with a minimum span so flat noise is not exaggerated). */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, minSpan: Double = 0.0, color: Color = MaterialTheme.colorScheme.primary) {
    if (values.size < 2) return
    val lo0 = values.min()
    val hi0 = values.max()
    val pad = ((minSpan - (hi0 - lo0)).coerceAtLeast(0.0)) / 2
    val lo = lo0 - pad
    val hi = hi0 + pad
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(64.dp)) {
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = (size.height * (1 - (v - lo) / span)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A bearing marker drawn on the dial rim (sun, moon, waypoint). */
data class DialMarker(val bearingDeg: Double, val color: Color, val label: String)

/**
 * Compass rose. The card rotates so the top of the screen is the current heading; rotation always takes
 * the short way round (359° → 1° turns 2°, not 358°).
 */
@Composable
fun Dial(headingDeg: Double?, markers: List<DialMarker>, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    // Unwrapped (continuous) angle so the animation never spins the long way across north.
    val unwrapped = remember { floatArrayOf(headingDeg?.toFloat() ?: 0f) }
    if (headingDeg != null) unwrapped[0] += angleDiff(unwrapped[0].toDouble(), headingDeg).toFloat()
    val animated by animateFloatAsState(unwrapped[0], spring(stiffness = 120f), label = "dial")
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.titleMedium
    val smallStyle = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant)
    Canvas(modifier.aspectRatio(1f).alpha(if (dimmed) 0.45f else 1f)) {
        val r = min(size.width, size.height) / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(cs.surfaceContainerHighest.copy(alpha = 0.5f), r, c)
        drawCircle(cs.outlineVariant, r, c, style = Stroke(1.dp.toPx()))
        rotate(-animated, c) {
            for (d in 0 until 360 step 5) {
                val major = d % 30 == 0
                val len = if (major) r * 0.11f else r * 0.05f
                val a = Math.toRadians(d.toDouble() - 90)
                val o = Offset(c.x + (r - 4) * cos(a).toFloat(), c.y + (r - 4) * sin(a).toFloat())
                val i = Offset(c.x + (r - 4 - len) * cos(a).toFloat(), c.y + (r - 4 - len) * sin(a).toFloat())
                drawLine(if (d == 0) cs.error else cs.onSurfaceVariant, i, o, if (major) 2.dp.toPx() else 1.dp.toPx())
            }
            for ((d, t) in listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")) {
                val a = Math.toRadians(d.toDouble() - 90)
                val p = Offset(c.x + r * 0.72f * cos(a).toFloat(), c.y + r * 0.72f * sin(a).toFloat())
                val layout = measurer.measure(t, labelStyle.copy(color = if (d == 0) cs.error else cs.onSurface))
                rotate(animated, p) {
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
            }
            for (m in markers) {
                val a = Math.toRadians(m.bearingDeg - 90)
                val p = Offset(c.x + r * 0.88f * cos(a).toFloat(), c.y + r * 0.88f * sin(a).toFloat())
                drawCircle(m.color, 7.dp.toPx(), p)
                val layout = measurer.measure(m.label, smallStyle)
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
        drawPath(path, cs.primary)
        drawLine(cs.primary, Offset(c.x, c.y - r * 0.25f), Offset(c.x, c.y - r + 6), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(cs.primary, 5.dp.toPx(), c)
    }
}

/** Two-line empty state for lists. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
