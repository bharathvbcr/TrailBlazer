package com.example.trailblazer.ui.tools

import android.hardware.SensorManager
import android.location.GnssStatus
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trailblazer.container
import com.example.trailblazer.location.GnssSnapshot
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.sensors.SensorInfo
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.components.PermissionGate
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.SectionTitle
import com.example.trailblazer.ui.label
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.trailblazer.core.motion.GForce
import com.trailblazer.core.motion.GSeverity
import com.trailblazer.core.motion.OrbitCamera
import com.trailblazer.core.motion.STANDARD_GRAVITY
import com.trailblazer.core.motion.Vec3
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
fun DiagnosticsScreen(nav: Navigator) {
    val ctx = LocalContext.current
    val c = ctx.container
    val sensors = remember { c.sensorSource.all().sortedBy { it.type } }
    val rates = remember { mutableStateMapOf<Int, Double>() }
    val scope = rememberCoroutineScope()
    val gnss by c.gnss.status.collectAsStateWithLifecycle()
    var showPlot by rememberSaveable { mutableStateOf(false) }
    var showMic by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]

    ScreenScaffold(title = "Sensors", onBack = { nav.back() }) {
        item { SectionTitle("3D motion (accelerometer)") }
        item {
            GlassCard {
                if (showPlot) AccelPlot() else TextButton(onClick = { showPlot = true }) { Text("Start 3D plot") }
            }
        }
        item { SectionTitle("Sound level (microphone)") }
        item {
            if (showMic) {
                PermissionGate(AppPermission.Microphone, "The sound meter reads the microphone level. Audio is measured in memory and immediately discarded — nothing is recorded or sent.") {
                    SoundMeter()
                }
            } else GlassCard { TextButton(onClick = { showMic = true }) { Text("Start sound meter") } }
        }
        item { SectionTitle("Satellites") }
        item {
            PermissionGate(AppPermission.Location, "Satellite status needs precise location permission.") {
                GnssList(gnss)
            }
        }
        item { SectionTitle("${sensors.size} hardware sensors") }
        items(sensors, key = { "${it.type}-${it.name}" }) { s ->
            GlassCard(padding = 12.dp) {
                Text(s.name, style = MaterialTheme.typography.titleSmall)
                Text("${typeName(s.type)} · ${s.vendor} v${s.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabelValue("Range / resolution", "${fmt(s.maxRange, locale)} / ${fmt(s.resolution, locale)}")
                LabelValue("Power", "${fmt(s.powerMa, locale)} mA${if (s.isWakeUp) " · wake-up" else ""}")
                LabelValue("Fastest rate", if (s.minDelayUs > 0) "${fmt(1_000_000f / s.minDelayUs, locale)} Hz" else if (s.minDelayUs == 0) "On change" else "One-shot")
                val measured = rates[s.type]
                Row {
                    Text(measured?.let { "Measured: ${String.format(locale, "%.1f", it)} Hz at game rate" } ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (s.minDelayUs > 0) TextButton(onClick = { scope.launch { rates[s.type] = measureRate(c.sensorSource, s) } }) { Text("Measure") }
                }
            }
        }
    }
}

private fun fmt(v: Float, locale: Locale) = String.format(locale, if (v >= 100) "%.0f" else if (v >= 1) "%.2f" else "%.4f", v)

/** Counts samples for 2 s at the game rate. */
private suspend fun measureRate(source: com.example.trailblazer.sensors.SensorSource, s: SensorInfo): Double {
    var n = 0
    var first = 0L
    var last = 0L
    val deadline = System.nanoTime() + 2_000_000_000L
    // Bounded even when the sensor never emits (takeWhile alone only checks on emissions).
    withTimeoutOrNull(2_500L) {
        source.samples(s.type, SensorManager.SENSOR_DELAY_GAME).takeWhile { System.nanoTime() < deadline }.collect {
            if (n == 0) first = it.timestampNs
            last = it.timestampNs
            n++
        }
    }
    return if (n > 1 && last > first) (n - 1) / ((last - first) / 1e9) else 0.0
}

private fun typeName(t: Int): String = when (t) {
    android.hardware.Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
    android.hardware.Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
    android.hardware.Sensor.TYPE_GYROSCOPE -> "Gyroscope"
    android.hardware.Sensor.TYPE_LIGHT -> "Light"
    android.hardware.Sensor.TYPE_PRESSURE -> "Barometer"
    android.hardware.Sensor.TYPE_PROXIMITY -> "Proximity"
    android.hardware.Sensor.TYPE_GRAVITY -> "Gravity (fused)"
    android.hardware.Sensor.TYPE_LINEAR_ACCELERATION -> "Linear acceleration (fused)"
    android.hardware.Sensor.TYPE_ROTATION_VECTOR -> "Rotation vector (fused)"
    android.hardware.Sensor.TYPE_RELATIVE_HUMIDITY -> "Humidity"
    android.hardware.Sensor.TYPE_AMBIENT_TEMPERATURE -> "Temperature"
    android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game rotation vector"
    android.hardware.Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic rotation vector"
    android.hardware.Sensor.TYPE_STEP_COUNTER -> "Step counter"
    android.hardware.Sensor.TYPE_STEP_DETECTOR -> "Step detector"
    android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant motion"
    else -> "Type $t"
}

@Composable
private fun GnssList(gnss: Reading<GnssSnapshot>) {
    GlassCard {
        when (gnss) {
            is Reading.Unavailable -> Text(gnss.reason.label("GNSS status"))
            Reading.Acquiring -> Text("Waiting for satellites… (go outside with a clear sky view)")
            is Reading.Value -> {
                val s = gnss.value
                LabelValue("In view / used in fix", "${s.inView} / ${s.used}")
                s.satellites.groupBy { it.constellation }.toSortedMap().forEach { (k, list) ->
                    LabelValue(constellation(k), "${list.count { it.usedInFix }} of ${list.size} used")
                }
                val top = s.satellites.sortedByDescending { it.cn0DbHz }.take(12)
                val good = LocalStatusColors.current.good
                val cs = MaterialTheme.colorScheme
                Canvas(Modifier.fillMaxWidth().height(90.dp).semantics { contentDescription = "Signal strength bars" }) {
                    val w = size.width / max(1, top.size)
                    top.forEachIndexed { i, sat ->
                        val h = (sat.cn0DbHz / 50.0).coerceIn(0.0, 1.0).toFloat() * size.height
                        drawRect(if (sat.usedInFix) good else cs.outline, Offset(i * w + 2, size.height - h), Size(w - 4, h))
                    }
                }
                Text("Signal strength (C/N₀, dB-Hz) of the strongest 12; green = used in fix.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun constellation(t: Int) = when (t) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "Other"
}

/** Live accelerometer vector in 3D with a 1 g reference ring; drag to orbit the camera. */
@Composable
private fun AccelPlot() {
    val ctx = LocalContext.current
    val accel by ctx.container.motion.acceleration.collectAsStateWithLifecycle()
    var yaw by rememberSaveable { mutableDoubleStateOf(OrbitCamera.ISO.yawDeg) }
    var pitch by rememberSaveable { mutableDoubleStateOf(OrbitCamera.ISO.pitchDeg) }
    val trail = remember { mutableStateListOf<Vec3>() }
    var peakG by remember { mutableDoubleStateOf(0.0) }
    val v = (accel as? Reading.Value)?.value
    LaunchedEffect(v) {
        if (v != null) {
            trail.add(v)
            if (trail.size > 60) trail.removeAt(0)
            GForce.of(v.magnitude)?.let { peakG = max(peakG, it) }
        }
    }
    val cs = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val locale = LocalConfiguration.current.locales[0]
    when (val r = accel) {
        is Reading.Unavailable -> Text(r.reason.label("accelerometer"))
        Reading.Acquiring -> Text("Starting…")
        is Reading.Value -> {
            val g = GForce.of(r.value.magnitude) ?: 0.0
            val color = when (GForce.severity(g)) { GSeverity.Normal -> cs.primary; GSeverity.Elevated -> status.caution; GSeverity.High -> status.danger }
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(16.dp))
                    .pointerInput(Unit) { detectDragGestures { _, d -> yaw += d.x / 3; pitch = (pitch + d.y / 3).coerceIn(-89.0, 89.0) } }
                    .semantics { contentDescription = "Accelerometer vector plot; drag to rotate" },
            ) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.2f)) {
                    val cam = OrbitCamera(yaw, pitch, size.minDimension / (2.6 * STANDARD_GRAVITY))
                    val cx = size.width / 2.0
                    val cy = size.height / 2.0
                    fun p(v: Vec3) = cam.project(v, cx, cy).let { Offset(it.x.toFloat(), it.y.toFloat()) }
                    val axis = STANDARD_GRAVITY * 1.2
                    drawLine(cs.error, p(Vec3(0.0, 0.0, 0.0)), p(Vec3(axis, 0.0, 0.0)), 2f)
                    drawLine(status.good, p(Vec3(0.0, 0.0, 0.0)), p(Vec3(0.0, axis, 0.0)), 2f)
                    drawLine(cs.secondary, p(Vec3(0.0, 0.0, 0.0)), p(Vec3(0.0, 0.0, axis)), 2f)
                    val ring = Path()
                    for (i in 0..48) {
                        val a = i * 2 * Math.PI / 48
                        val q = p(Vec3(STANDARD_GRAVITY * cos(a), STANDARD_GRAVITY * sin(a), 0.0))
                        if (i == 0) ring.moveTo(q.x, q.y) else ring.lineTo(q.x, q.y)
                    }
                    drawPath(ring, cs.outline, style = Stroke(1.5f))
                    if (trail.size > 1) {
                        val tp = Path()
                        trail.forEachIndexed { i, t -> val q = p(t); if (i == 0) tp.moveTo(q.x, q.y) else tp.lineTo(q.x, q.y) }
                        drawPath(tp, color.copy(alpha = 0.4f), style = Stroke(2f))
                    }
                    drawLine(color, p(Vec3(0.0, 0.0, 0.0)), p(r.value), 5f)
                    drawCircle(color, 8f, p(r.value))
                }
            }
            LabelValue("Magnitude", String.format(locale, "%.2f m/s² · %.2f g", r.value.magnitude, g))
            LabelValue("x / y / z", String.format(locale, "%.2f / %.2f / %.2f", r.value.x, r.value.y, r.value.z))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelValue("Peak", String.format(locale, "%.2f g", peakG), Modifier.weight(1f))
                TextButton(onClick = { peakG = 0.0 }) { Text("Reset") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((name, cam) in listOf("Iso" to OrbitCamera.ISO, "Top" to OrbitCamera.TOP, "Front" to OrbitCamera.FRONT, "Side" to OrbitCamera.SIDE)) {
                    FilterChip(selected = yaw == cam.yawDeg && pitch == cam.pitchDeg, onClick = { yaw = cam.yawDeg; pitch = cam.pitchDeg }, label = { Text(name) })
                }
            }
        }
    }
}

@Composable
private fun SoundMeter() {
    val ctx = LocalContext.current
    val flow = remember { ctx.container.acoustic.level() }
    val level by flow.collectAsStateWithLifecycle(Reading.Acquiring)
    val locale = LocalConfiguration.current.locales[0]
    GlassCard {
        when (val l = level) {
            is Reading.Unavailable -> Text(l.reason.label("microphone"))
            Reading.Acquiring -> Text("Starting microphone…")
            is Reading.Value -> {
                val frac = ((l.value.rmsDbfs + 90) / 90).coerceIn(0.0, 1.0).toFloat()
                val cs = MaterialTheme.colorScheme
                Text(String.format(locale, "%.0f dBFS", l.value.rmsDbfs), style = MaterialTheme.typography.headlineMedium)
                Canvas(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp))) {
                    drawRect(cs.surfaceContainerHighest)
                    drawRect(cs.primary, size = Size(size.width * frac, size.height))
                }
                Text(
                    String.format(locale, "Peak %.0f dBFS. Relative to the loudest sound the microphone can record; phones are not calibrated for dB SPL.", l.value.peakDbfs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(360.dp),
                )
            }
        }
    }
}
