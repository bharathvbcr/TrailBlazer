package com.example.trailblazer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.trailblazer.sensors.LoggerState
import com.example.trailblazer.ui.theme.LocalStatusColors
import com.example.trailblazer.ui.tools.formatElapsed

/**
 * "Recording sensors · 1:02 · Stop", shown on every screen while the sensor logger records, so a recording started in
 * Diagnostics is never running unnoticed (it holds the motion sensors at 200 Hz and, with location allowed, raw GNSS).
 * Draws nothing in any other state.
 */
@Composable
fun RecordingIndicator(state: LoggerState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val recording = state as? LoggerState.Recording ?: return
    val danger = LocalStatusColors.current.danger
    Surface(
        modifier = modifier.testTag("recordingIndicator"),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(danger, CircleShape))
            Text(
                (if (recording.stopping) "Finishing sensor recording" else "Recording sensors") + " · " + formatElapsed(recording.stats.elapsedMs),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            TextButton(
                onClick = onStop,
                enabled = !recording.stopping,
                modifier = Modifier.semantics { contentDescription = "Stop recording sensors" },
            ) { Text("Stop") }
        }
    }
}
