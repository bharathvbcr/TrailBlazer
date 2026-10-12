package com.example.trailblazer.ui.tools

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.data.ThemeMode
import com.example.trailblazer.sensors.LogStats
import com.example.trailblazer.sensors.LoggerState
import com.example.trailblazer.sensors.PendingLog
import com.example.trailblazer.sensors.StopReason
import com.example.trailblazer.ui.theme.TrailTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class SensorRecorderCardTest {
    @get:Rule
    val rule = createComposeRule()

    private val toggles = ArrayList<Boolean>()
    private val saves = ArrayList<String>()
    private var discards = 0
    private var cancels = 0
    private var dismissals = 0

    private fun show(state: LoggerState, notice: String? = null) = rule.setContent {
        TrailTheme(mode = ThemeMode.Dark, dynamicColor = false, nightRed = false) {
            SensorRecorderContent(
                state = state,
                notice = notice,
                onToggle = { toggles += it },
                onSave = { saves += it },
                onCancelSave = { cancels++ },
                onDiscard = { discards++ },
                onDismissNotice = { dismissals++ },
            )
        }
    }

    private val recording = LoggerState.Recording(
        stats = LogStats(elapsedMs = 3_725_000, written = 1_234_567, dropped = 0, bytes = 150_994_944),
        sources = listOf("Accelerometer", "Gyroscope"),
        skipped = listOf("Barometer: no such sensor"),
    )

    private val finished = LoggerState.Finished(
        log = PendingLog(File("/data/trailblazer-sensors-20231114T221320Z.csv"), 4_096, interrupted = false),
        stats = LogStats(61_000, 500, 0, 4_096),
        reason = StopReason(StopReason.Kind.UserStopped),
    )

    @Test
    fun idleShowsAnOffToggleAndStartsOnTap() {
        show(LoggerState.Idle)
        rule.onNodeWithTag("record-toggle").assertIsOff().assertIsEnabled()
        rule.onNodeWithText("Samples written").assertDoesNotExist()
        rule.onNodeWithContentDescription("Record sensor data to a CSV file").performClick()
        assertEquals(listOf(true), toggles)
    }

    @Test
    fun recordingShowsTheToggleOnWithElapsedSamplesDroppedAndSize() {
        Locale.setDefault(Locale.US)
        show(recording)
        rule.onNodeWithTag("record-toggle").assertIsOn()
        rule.onNodeWithText("1:02:05").assertIsDisplayed()
        rule.onNodeWithText("1,234,567").assertIsDisplayed()
        rule.onNodeWithText("Dropped").assertIsDisplayed()
        rule.onNodeWithText("0").assertIsDisplayed()
        rule.onNodeWithText("144.0 MB").assertIsDisplayed()
        rule.onNodeWithText("Recording: Accelerometer, Gyroscope.").assertIsDisplayed()
        rule.onNodeWithText("Not recorded: Barometer: no such sensor").assertIsDisplayed()
        rule.onNodeWithText("Save as CSV…").assertDoesNotExist()
        rule.onNodeWithTag("record-toggle").performClick()
        assertEquals(listOf(false), toggles)
    }

    @Test
    fun droppedSamplesAreCalledOutSoALossyLogIsNotTakenForComplete() {
        show(recording.copy(stats = recording.stats.copy(dropped = 4_321)))
        rule.onNodeWithText("4,321").assertIsDisplayed()
        rule.onNodeWithText("The phone could not write fast enough, so some samples were lost. The file marks where.").assertIsDisplayed()
    }

    @Test
    fun stoppingDisablesTheToggleWhileTheLastSamplesAreWritten() {
        show(recording.copy(stopping = true))
        rule.onNodeWithTag("record-toggle").assertIsNotEnabled()
        rule.onNodeWithText("Finishing the file… Accelerometer, Gyroscope.").assertIsDisplayed()
    }

    @Test
    fun finishedOffersSaveAndDiscardWithTheReasonAndLocksTheToggle() {
        show(finished)
        rule.onNodeWithTag("record-toggle").assertIsOff().assertIsNotEnabled()
        rule.onNodeWithText("Stopped by you.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save the recording as a CSV file").performClick()
        assertEquals(listOf("trailblazer-sensors-20231114T221320Z.csv"), saves)
        rule.onNodeWithContentDescription("Discard the recording").performClick()
        assertEquals("a confirmation comes first", 0, discards)
        rule.onNodeWithText("Discard this recording?").assertIsDisplayed()
        rule.onNodeWithContentDescription("Confirm discard").performClick()
        assertEquals(1, discards)
    }

    @Test
    fun keepingInTheDiscardDialogDeletesNothing() {
        show(finished)
        rule.onNodeWithContentDescription("Discard the recording").performClick()
        rule.onNodeWithText("Keep").performClick()
        assertEquals(0, discards)
        rule.onNodeWithText("Discard this recording?").assertDoesNotExist()
    }

    @Test
    fun anAutomaticStopSaysWhy() {
        show(finished.copy(reason = StopReason(StopReason.Kind.SizeCap, "200.0 MB")))
        rule.onNodeWithText("Stopped automatically: the recording reached its size limit (200.0 MB).").assertIsDisplayed()
    }

    @Test
    fun aRecordingFoundAfterARestartIsOfferedWithoutStats() {
        show(finished.copy(stats = null, reason = StopReason(StopReason.Kind.Interrupted)))
        rule.onNodeWithText("The app was closed or stopped while this was recording, so it may end early.").assertIsDisplayed()
        rule.onNodeWithText("File size").assertIsDisplayed()
        rule.onNodeWithText("Save as CSV…").assertIsDisplayed()
    }

    @Test
    fun savingShowsProgressAndCanBeCancelled() {
        show(finished.copy(exporting = true))
        rule.onNodeWithText("Saving…").assertIsDisplayed()
        rule.onNodeWithText("Save as CSV…").assertDoesNotExist()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun aNoticeIsShownAndDismissed() {
        show(LoggerState.Idle, notice = "Saved 1.0 MB to the location you chose.")
        rule.onNodeWithText("Saved 1.0 MB to the location you chose.").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, dismissals)
    }

    @Test
    fun elapsedAndSizeFormatting() {
        assertEquals("0:00", formatElapsed(-5))
        assertEquals("0:59", formatElapsed(59_999))
        assertEquals("1:00", formatElapsed(60_000))
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("512 B", formatBytes(512, Locale.US))
        assertEquals("1.5 KB", formatBytes(1536, Locale.US))
        assertEquals("1,5 MB", formatBytes(1_572_864, Locale.GERMANY))
    }
}
