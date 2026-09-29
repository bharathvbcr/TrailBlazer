package com.example.trailblazer.ui.tools

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.components.TrailIcons
import com.example.trailblazer.ui.nav.AltimeterRoute
import com.example.trailblazer.ui.nav.DiagnosticsRoute
import com.example.trailblazer.ui.nav.LevelRoute
import com.example.trailblazer.ui.nav.Navigator
import com.example.trailblazer.ui.nav.Route
import com.example.trailblazer.ui.nav.SettingsRoute
import com.example.trailblazer.ui.nav.SosRoute

private data class Tool(val title: String, val body: String, val icon: ImageVector, val route: Route)

private val tools = listOf(
    Tool("Level & tilt", "Bubble level, and pitch, roll and gradient for vehicles", TrailIcons.Level, LevelRoute),
    Tool("Altimeter", "Barometric altitude, calibration, boiling point, density altitude", TrailIcons.Mountain, AltimeterRoute),
    Tool("SOS", "Torch morse, distress whistle and screen signal", TrailIcons.Torch, SosRoute),
    Tool("Sensors", "Every sensor on this phone, satellites, 3D motion plot and sound meter", TrailIcons.Sensors, DiagnosticsRoute),
)

@Composable
fun ToolsScreen(nav: Navigator) {
    ScreenScaffold(
        title = "Tools",
        actions = { IconButton(onClick = { nav.go(SettingsRoute) }) { Icon(TrailIcons.Settings, "Settings") } },
    ) {
        tools.chunked(2).forEach { pair ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { t ->
                        GlassCard(Modifier.weight(1f).height(168.dp), onClick = { nav.go(t.route) }, onClickLabel = "Open ${t.title}") {
                            Icon(t.icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(t.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Keeps the screen on while this composable is shown (level, SOS, camera sighting). */
@Composable
fun KeepScreenOn() {
    val view: View = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
