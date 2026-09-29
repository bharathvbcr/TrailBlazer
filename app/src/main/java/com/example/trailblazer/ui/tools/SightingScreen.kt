package com.example.trailblazer.ui.tools

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trailblazer.container
import com.example.trailblazer.permissions.AppPermission
import com.example.trailblazer.sensors.Declination
import com.example.trailblazer.sensors.Hold
import com.example.trailblazer.sensors.Reading
import com.example.trailblazer.ui.components.GlassChrome
import com.example.trailblazer.ui.components.PermissionGate
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.nav.Navigator
import com.trailblazer.core.astro.LunarPosition
import com.trailblazer.core.astro.SolarPosition
import com.trailblazer.core.geo.Geo
import com.trailblazer.core.math.angleDiff
import com.trailblazer.core.math.cardinal16
import com.trailblazer.core.math.mod360
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/** Field of view of the back camera, from focal length and physical sensor size (long and short sensor axes). */
data class CameraFov(val longDeg: Double, val shortDeg: Double)

@OptIn(ExperimentalCamera2Interop::class)
private fun fovOf(info: CameraInfo): CameraFov? {
    val c2 = Camera2CameraInfo.from(info)
    val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
    val s = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    if (f <= 0f) return null
    val long = max(s.width, s.height).toDouble()
    val short = minOf(s.width, s.height).toDouble()
    return CameraFov(Math.toDegrees(2 * atan(long / (2 * f))), Math.toDegrees(2 * atan(short / (2 * f))))
}

/**
 * Horizontal FOV actually visible in a FILL_CENTER preview of [view] size: the 4:3 camera image is scaled to
 * cover the view, so part of it is cropped off the sides.
 */
fun visibleHorizontalFov(fov: CameraFov, view: IntSize, portrait: Boolean): Double {
    val hFull = if (portrait) fov.shortDeg else fov.longDeg
    val (imgW, imgH) = if (portrait) 3.0 to 4.0 else 4.0 to 3.0
    if (view.width <= 0 || view.height <= 0) return hFull
    val scale = max(view.width / imgW, view.height / imgH)
    val frac = (view.width / (imgW * scale)).coerceIn(0.05, 1.0)
    return Math.toDegrees(2 * atan(frac * tan(Math.toRadians(hFull / 2))))
}

@Composable
fun SightingScreen(nav: Navigator) {
    KeepScreenOn()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PermissionGate(
            AppPermission.Camera,
            "Sighting shows the camera view with a compass strip and the directions of the sun, moon and your target. The image is never saved or sent.",
            Modifier.align(Alignment.Center).padding(24.dp),
        ) { CameraSighting() }
        GlassChrome(Modifier.align(Alignment.TopStart).padding(12.dp).windowInsetsPadding(WindowInsets.safeDrawing), shape = androidx.compose.foundation.shape.CircleShape) {
            IconButton(onClick = { nav.back() }) { Icon(TrailIcons.Back, "Back") }
        }
    }
}

@Composable
private fun CameraSighting() {
    val ctx = LocalContext.current
    val c = ctx.container
    val lifecycle = LocalLifecycleOwner.current
    val portrait = LocalConfiguration.current.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var fov by remember { mutableStateOf<CameraFov?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val orientationFlow = remember { c.orientation.orientation(Hold.Upright) }
    val orientation by orientationFlow.collectAsStateWithLifecycle(Reading.Acquiring)
    val fix by c.location.fix.collectAsStateWithLifecycle()
    val settings = c.prefs.settings.collectAsStateWithLifecycle(null).value
    val waypoints by c.waypoints.all.collectAsStateWithLifecycle(emptyList())

    DisposableEffect(lifecycle) {
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                fov = fovOf(cam.cameraInfo)
            } catch (e: Exception) {
                // IllegalArgumentException (no back camera) or ExecutionException from the provider.
                error = "Camera unavailable: ${e.javaClass.simpleName}"
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
        onDispose { provider?.unbind(preview) }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        AndroidView({ previewView }, Modifier.fillMaxSize())
        val f = (fix as? Reading.Value)?.value
        val o = (orientation as? Reading.Value)?.value
        val decl = f?.let { Declination.degrees(it) }
        val heading = o?.let { mod360(it.azimuthDeg + (decl ?: 0.0)) }
        val hfov = fov?.let { visibleHorizontalFov(it, viewSize, portrait) }
        val now = remember(f?.timeMs) { System.currentTimeMillis() }
        val marks = buildList {
            if (f != null) {
                val sun = SolarPosition.horizontal(now, f.position.lat, f.position.lon)
                add(Triple("Sun", sun.azimuthDeg, Color(0xFFFFC857)))
                val moon = LunarPosition.horizontal(now, f.position.lat, f.position.lon)
                add(Triple("Moon", moon.azimuthDeg, Color(0xFFD8E1FF)))
                settings?.targetWaypointId?.let { id -> waypoints.firstOrNull { it.id == id } }?.let { w ->
                    add(Triple(w.name.take(12), Geo.initialBearing(f.position, w.position), Color(0xFF7CE0A0)))
                }
            }
        }
        if (heading != null && hfov != null) Strip(heading, hfov, marks)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            GlassChrome(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        heading?.let { "${it.roundToInt() % 360}° ${cardinal16(it)} ${if (decl != null) "true" else "magnetic"}" } ?: "Starting compass…",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    o?.let { Text("Camera tilt ${(-it.pitchDeg).roundToInt()}°", style = MaterialTheme.typography.bodyMedium) }
                    hfov?.let { Text("Strip spans the ${it.roundToInt()}° the camera sees", style = MaterialTheme.typography.bodySmall) }
                    if (fov == null && error == null) Text("Field of view not reported: strip hidden", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (f == null) Text("Sun, moon and target markers need a location fix", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Compass strip across the top of the preview: 1 screen width = the camera's visible horizontal FOV. */
@Composable
private fun Strip(heading: Double, hfov: Double, marks: List<Triple<String, Double, Color>>) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = Color.White, fontSize = 12.sp)
    Canvas(Modifier.fillMaxWidth().height(120.dp).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val pxPerDeg = size.width / hfov
        val cx = size.width / 2
        drawRect(Color.Black.copy(alpha = 0.35f), size = size.copy(height = 56.dp.toPx()))
        val start = (heading - hfov / 2).toInt() - 1
        val end = (heading + hfov / 2).toInt() + 1
        for (d in start..end) {
            val x = (cx + angleDiff(heading, d.toDouble()) * pxPerDeg).toFloat()
            val m = mod360(d.toDouble()).toInt()
            if (m % 5 != 0) continue
            val major = m % 15 == 0
            drawLine(Color.White, Offset(x, 0f), Offset(x, if (major) 18.dp.toPx() else 9.dp.toPx()), if (major) 3f else 1.5f)
            if (major) {
                val label = if (m % 45 == 0) cardinal16(m.toDouble()) else "$m"
                val l = measurer.measure(label, style)
                drawText(l, topLeft = Offset(x - l.size.width / 2f, 22.dp.toPx()))
            }
        }
        drawLine(Color(0xFFFF5A4F), Offset(cx, 0f), Offset(cx, 56.dp.toPx()), 4f)
        for ((name, az, color) in marks) {
            val off = angleDiff(heading, az)
            if (kotlin.math.abs(off) > hfov / 2) continue
            val x = (cx + off * pxPerDeg).toFloat()
            drawCircle(color, 8.dp.toPx(), Offset(x, 72.dp.toPx()))
            val l = measurer.measure(name, style)
            drawText(l, topLeft = Offset(x - l.size.width / 2f, 84.dp.toPx()))
        }
    }
}
