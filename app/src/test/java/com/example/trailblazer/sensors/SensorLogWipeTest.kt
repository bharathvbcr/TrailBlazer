package com.example.trailblazer.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.trailblazer.TrailApp
import com.example.trailblazer.WipeResult
import com.example.trailblazer.container
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor
import java.io.File

/**
 * Raw GNSS with UTC timestamps is location history, so "delete all data" has to take the sensor recordings too: stop a
 * running one first, and delete the files (including a finished one nobody saved).
 */
@RunWith(AndroidJUnit4::class)
class SensorLogWipeTest {
    private val app = ApplicationProvider.getApplicationContext<TrailApp>()
    private val logs = File(app.noBackupFilesDir, "sensorlogs")

    @Before
    fun anAccelerometerExists() {
        shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(robolectricAccelerometer())
    }

    private fun wipe(): WipeResult {
        val deletion = app.container.scope.async { app.container.deleteAllData() }
        waitFor("the wipe to finish") {
            shadowOf(Looper.getMainLooper()).idle()
            deletion.isCompleted
        }
        return deletion.getCompleted()
    }

    @Test
    fun deletingAllDataStopsARunningSensorRecordingAndRemovesItsFile() {
        val logger = app.container.sensorLogger
        assertNull(logger.start())
        assertTrue("a recording file exists", logs.listFiles().orEmpty().isNotEmpty())
        assertTrue(logger.state.value is LoggerState.Recording)

        assertEquals(WipeResult.Done, wipe())

        assertTrue("no sensor recording file is left: ${logs.listFiles()?.toList()}", logs.listFiles().orEmpty().isEmpty())
        assertEquals(LoggerState.Idle, logger.state.value)
    }

    @Test
    fun aFinishedRecordingThatWasNeverSavedIsDeletedToo() {
        val logger = app.container.sensorLogger
        assertNull(logger.start())
        logger.stop()
        val done = logger.awaitFinished()
        assertTrue(done.log.file.exists())

        assertEquals(WipeResult.Done, wipe())

        assertTrue(logs.listFiles().orEmpty().isEmpty())
        assertEquals(LoggerState.Idle, logger.state.value)
    }
}
