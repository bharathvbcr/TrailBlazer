package com.example.trailblazer.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trailblazer.container
import com.example.trailblazer.sensors.LogStats
import com.example.trailblazer.sensors.LoggerState
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.LabelValue
import com.example.trailblazer.ui.theme.LocalStatusColors
import java.util.Locale

/**
 * The Record control of the Diagnostics screen. The recorder lives in `AppContainer`, so this only observes it: leaving
 * the screen does not stop a recording, and the screen holds no sensor listener. Saving uses the system file picker
 * (`CreateDocument("text/csv")`), so the app needs no storage permission.
 */
@Composable
fun SensorRecorderCard() {
    val logger = LocalContext.current.container.sensorLogger
    val state by logger.state.collectAsStateWithLifecycle()
    val notice by logger.notice.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> logger.export(uri) }
    SensorRecorderContent(
        state = state,
        notice = notice,
        onToggle = { on -> if (on) logger.start() else logger.stop() },
        onSave = { name -> picker.launch(name) },
        onCancelSave = logger::cancelExport,
        onDiscard = { logger.discard() },
        onDismissNotice = logger::clearNotice,
    )
}

/** Stateless body of [SensorRecorderCard], so the toggle states can be tested without the app container. */
@Composable
internal fun SensorRecorderContent(
    state: LoggerState,
    notice: String?,
    onToggle: (Boolean) -> Unit,
    onSave: (suggestedName: String) -> Unit,
    onCancelSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val status = LocalStatusColors.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val recording = state as? LoggerState.Recording
    val toggleEnabled = state !is LoggerState.Finished && recording?.stopping != true
    GlassCard {
        Row(
            Modifier.fillMaxWidth().testTag("record-toggle")
                .toggleable(value = recording != null, enabled = toggleEnabled, role = Role.Switch, onValueChange = onToggle)
                .semantics {
                    contentDescription = "Record sensor data to a CSV file"
                    stateDescription = when {
                        recording == null -> "Off"
                        recording.stopping -> "Stopping"
                        else -> "Recording"
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Record sensor data", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = recording != null, onCheckedChange = null, enabled = toggleEnabled)
        }
        when (state) {
            LoggerState.Idle -> Text(
                "Records raw accelerometer and gyroscope at up to 200 Hz, the barometer, and raw GNSS measurements when the " +
                    "phone and the location permission allow, to a CSV file with UTC timestamps. While a track is " +
                    "recording (its notification is showing) the sensors keep running with the screen off. Otherwise " +
                    "Android pauses them while the app is in the background, and the file marks those stretches. A " +
                    "banner on every screen shows the recording until you stop it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is LoggerState.Recording -> {
                RecordingStats(state.stats, locale)
                Text(
                    (if (state.stopping) "Finishing the file… " else "Recording: ") + state.sources.joinToString(", ") + ".",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.skipped.forEach {
                    Text("Not recorded: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is LoggerState.Finished -> {
                Text(state.reason.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                if (state.stats != null) RecordingStats(state.stats, locale)
                else LabelValue("File size", formatBytes(state.log.bytes, locale))
                if (state.exporting) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saving…", Modifier.weight(1f))
                        TextButton(onClick = onCancelSave) { Text("Cancel") }
                    }
                } else {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { onSave(state.log.file.name.removeSuffix(".part")) },
                            modifier = Modifier.semantics { contentDescription = "Save the recording as a CSV file" },
                        ) { Text("Save as CSV…") }
                        TextButton(
                            onClick = { confirmDiscard = true },
                            modifier = Modifier.semantics { contentDescription = "Discard the recording" },
                        ) { Text("Discard") }
                    }
                }
            }
        }
        if (notice != null) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, style = MaterialTheme.typography.bodySmall, color = status.caution, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissNotice) { Text("OK") }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this recording?") },
            text = { Text("The recorded data will be deleted from this phone. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDiscard = false; onDiscard() },
                    modifier = Modifier.semantics { contentDescription = "Confirm discard" },
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun RecordingStats(stats: LogStats, locale: Locale) {
    LabelValue("Elapsed", formatElapsed(stats.elapsedMs))
    LabelValue("Samples written", String.format(locale, "%,d", stats.written))
    if (stats.dropped == 0L) {
        LabelValue("Dropped", "0")
    } else {
        // A lossy log must not look complete: the count is in the file too.
        LabelValue("Dropped", String.format(locale, "%,d", stats.dropped))
        Text(
            "The phone could not write fast enough, so some samples were lost. The file marks where.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalStatusColors.current.danger,
        )
    }
    LabelValue("File size", formatBytes(stats.bytes, locale))
}

/** `m:ss`, or `h:mm:ss` from an hour. */
internal fun formatElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0L)) / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

internal fun formatBytes(bytes: Long, locale: Locale): String = when {
    bytes < 1024 -> String.format(locale, "%d B", bytes)
    bytes < 1024 * 1024 -> String.format(locale, "%.1f KB", bytes / 1024.0)
    else -> String.format(locale, "%.1f MB", bytes / 1_048_576.0)
}
